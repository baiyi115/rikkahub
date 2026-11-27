package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellTask
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceCapabilities
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024

// 分页读取缓存的上限: 再大的文件每次重读反而比占着内存划算
private const val MAX_CACHED_FILE_BYTES = 2L * 1024 * 1024

private const val DEFAULT_FILE_READ_CHARS = 30_000

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

/** 直接写文件的工具, 只在支持 [WorkspaceCapabilities.fileWriteTools] 的工作区提供 */
val WorkspaceFileWriteToolNames: Set<String> = setOf("workspace_write_file", "workspace_edit_file")

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

/**
 * shell 的 cwd 只接受文件区内的路径。
 *
 * 宿主机校验和 PRoot 的 cwd 映射方式不同: 前者把路径当 filesDir 下的相对路径,
 * 后者会拼到 /workspace 下。这里先把绝对路径归一化, 并拒绝 .. 逃逸,
 * 避免命令在校验过的目录之外执行。
 */
internal fun workspaceCwd(layout: WorkspaceLayout, path: String): String {
    val relative = layout.relativeToWorkspace(path)
    require(relative.isEmpty() || (!relative.startsWith("/") && relative.split('/').none { it == ".." })) {
        "cwd must stay inside the workspace files root: $path"
    }
    return relative
}

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    tempDir: File,
    cwd: String? = null,
    conversationId: Uuid? = null,
    backgroundShellManager: BackgroundShellManager? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val layout = workspaceRepository.layout(workspaceId) ?: return emptyList()
    val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
    val approvalOverrides = workspace.toolApprovalOverrides()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    // 会话里保存的默认 cwd 可能来自旧版本或已失效的路径; 不在建工具时抛错, 回退到文件区根目录
    val shellCwd = cwd?.let { runCatching { workspaceCwd(layout, it) }.getOrNull() }
    // 后台命令按会话归属, 没有会话时不提供
    val background = backgroundShellManager
        ?.takeIf { workspace.capabilities().backgroundShell }
        ?.let { manager -> conversationId?.let { BackgroundShell(manager, it) } }

    return buildList {
        add(createReadFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        add(createReadDocumentTool(workspaceId, layout, workspaceRepository, tempDir))
        addAll(createWorkspaceSearchTools(workspaceId, layout, workspaceRepository))
        if (workspace.capabilities().fileWriteTools) {
            add(createWriteFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
            add(createEditFileTool(workspaceId, layout, ::needsApproval, workspaceRepository))
        }
        add(createShellTool(workspaceId, layout, ::needsApproval, workspaceRepository, shellCwd, background))
        if (background != null) {
            add(createShellOutputTool(background))
            add(createShellKillTool(background))
        }
    }
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

/**
 * 分页读取时同一份文件常被连续读好几次, 每次都整份读入再解码很浪费。
 *
 * First pages always read from the backend. Continuations can reuse one small local file
 * if the mapping and file attributes still match. SSH has no local file to validate.
 */
private class ReadFileCache {
    private data class Entry(val path: String, val stamp: ReadFileStamp, val text: String)

    private val entry = AtomicReference<Entry?>(null)

    suspend fun load(
        repository: WorkspaceRepository,
        workspaceId: String,
        path: String,
        offset: Int,
    ): String = withContext(Dispatchers.IO) {
        val before = repository.readFileStamp(workspaceId, path)
        val cached = entry.get()
        if (cached != null && cached.path == path) {
            if (offset > 0 && before == cached.stamp) return@withContext cached.text
            entry.compareAndSet(cached, null)
        }
        val text = repository.readTextInRootfs(workspaceId, path)
        if (before != null && before == repository.readFileStamp(workspaceId, path)) {
            entry.set(Entry(path, before, text))
        }
        text
    }
}

private data class ReadFileStamp(val file: File, val fileKey: Any?, val length: Long, val modified: FileTime)

private suspend fun WorkspaceRepository.readFileStamp(workspaceId: String, path: String): ReadFileStamp? {
    val file = resolveAbsoluteFile(workspaceId, path) ?: return null
    val attrs = try {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    }
    val modified = attrs.lastModifiedTime()
    if (!attrs.isRegularFile || attrs.size() > MAX_CACHED_FILE_BYTES || modified.toMillis() == 0L) return null
    return ReadFileStamp(file, attrs.fileKey(), attrs.size(), modified)
}

private fun createReadFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
): Tool {
    val readFileCache = ReadFileCache()
    return Tool(
        name = "workspace_read_file",
        description = """
            Read a file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
            Use ${layout.workspaceDir} for the workspace files area.
            Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
            Long files are returned in pages. Pass next_offset as offset while truncated is true.
            The JSON size limit can shorten a page. at_end is true if offset is at or past the end.
            For PDF, DOCX, PPTX, XLSX and EPUB documents use workspace_read_document instead.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putPathProperty(layout, required = true)
                    put("offset", buildJsonObject {
                        put("type", "integer")
                        put("description", "Start offset in UTF-16 code units (default 0). Offsets inside a pair round down.")
                    })
                    put("max_chars", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Requested page length in UTF-16 code units. Default and maximum $DEFAULT_FILE_READ_CHARS."
                        )
                    })
                },
                required = listOf("path"),
            )
        },
        needsApproval = { needsApproval("workspace_read_file") },
        execute = {
            val params = it.jsonObject
            val path = params.absolutePath("path")
            if (path.isImagePath()) {
                workspaceRepository.readImageInRootfs(workspaceId, path)
            } else {
                val offset = params.workspaceReadOffset()
                val maxChars = (params.string("max_chars")?.toIntOrNull() ?: DEFAULT_FILE_READ_CHARS)
                    .coerceIn(1, DEFAULT_FILE_READ_CHARS)
                val text = readFileCache.load(workspaceRepository, workspaceId, path, offset)
                val page = workspaceTextPage(path, text, offset, maxChars, workspaceReadOutputLimit())
                listOf(UIMessagePart.Text(page))
            }
        },
    )
}

private fun createWriteFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { needsApproval("workspace_write_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, text, overwrite)
        // 写操作回读校验: 确认落盘内容与请求内容一致, 避免静默截断或写到别的位置
        requireWriteVerified(path, text, workspaceRepository.readTextInRootfs(workspaceId, path))
        listOf(UIMessagePart.Text(entry.toJson { put("verified", true) }.toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file using the assistant's bound workspace Rootfs. Paths must be absolute inside Rootfs.
        Use ${layout.workspaceDir} for the workspace files area.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { needsApproval("workspace_edit_file") || it.pathOutsideWritableRoots(layout, "path") },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        require(oldText.isNotEmpty()) { "old_text must not be empty" }

        val original = workspaceRepository.readTextInRootfs(workspaceId, path)
        // 逐级尝试 exact -> line_trimmed -> block_anchor 替换器, 见 TextReplacers.kt
        val result = try {
            replaceText(original, oldText, newText, replaceAll)
        } catch (e: IllegalArgumentException) {
            error("${e.message} (path: $path)")
        }
        val entry = workspaceRepository.writeAbsoluteText(workspaceId, path, result.updated, overwrite = true)
        requireWriteVerified(path, result.updated, workspaceRepository.readTextInRootfs(workspaceId, path))
        val diff = generateUnifiedDiff(original, result.updated, entry.path)
        listOf(
            UIMessagePart.Text(
                text = buildJsonObject {
                    put("path", entry.path)
                    put("replacements", result.replacements)
                    if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    put("verified", true)
                }.toString(),
                // diff 存入 metadata 供 UI 渲染 diff view, 不会随工具结果发送给 API
                metadata = diff?.let { d -> DiffMetadata(diff = d).toMetadata() },
            )
        )
    },
)

private class BackgroundShell(val manager: BackgroundShellManager, val conversationId: Uuid)

private fun createShellTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String? = null,
    background: BackgroundShell? = null,
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append(
            "Run a shell command in the assistant's bound workspace Rootfs. The workspace files area is mounted at ${layout.workspaceDir}. "
        )
        append("Use cwd for a path relative to the workspace files root. ")
        if (!defaultCwd.isNullOrBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        if (background != null) {
            append(
                "Set run_in_background for long-running commands such as dev servers, watchers or long builds; " +
                    "processes left running with `&` are killed when the command returns. "
            )
        }
        append("Requires Rootfs to be installed and ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("description", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        "Clear, concise description of what this command does in active voice, usually 5-10 words, " +
                            "written in the user's language. It is shown to the user in place of the raw command."
                    )
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (!defaultCwd.isNullOrBlank()) {
                            "Working directory relative to the workspace files root. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to the workspace files root. Defaults to root."
                        }
                    )
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        buildString {
                            append("Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS.")
                            if (background != null) append(" Ignored when running in the background.")
                        }
                    )
                })
                if (background != null) {
                    put("run_in_background", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Start the command in the background and return a task id immediately instead of waiting for it. " +
                                "You will be notified with its exit code and the last lines of its output when it finishes, so do not poll it. " +
                                "Use workspace_shell_output to check on it earlier and workspace_shell_kill to stop it. Defaults to false."
                        )
                    })
                }
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = workspaceCwd(layout, params.string("cwd") ?: defaultCwd.orEmpty())
        val runInBackground = params.string("run_in_background")?.toBooleanStrictOrNull() ?: false
        val output = if (runInBackground && background != null) {
            background.manager.start(background.conversationId, workspaceId, command, cwd).toJson()
        } else {
            val timeoutMillis = params.string("timeout")?.toLongOrNull()
                ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
            val result = workspaceRepository.executeCommand(workspaceId, command, cwd, timeoutMillis)
            buildJsonObject {
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                if (result.truncated) put("truncated", true)
            }
        }
        listOf(UIMessagePart.Text(output.toString()))
    },
)

private fun createShellOutputTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_output",
    description = """
        Read the output of a background command started by workspace_shell with run_in_background.
        Returns the combined stdout and stderr produced since the previous read, the command status,
        and the exit code once it has finished. You are notified automatically when a background command
        finishes, so only use this when you need its output before then, such as checking that a server started.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
                put("wait", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Seconds to wait for the command to finish before returning. Defaults to 0 (return immediately), max $SHELL_TIMEOUT_MAX_SECONDS."
                    )
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val params = it.jsonObject
        val taskId = params.string("task_id") ?: error("task_id is required")
        val waitMillis = (params.string("wait")?.toLongOrNull() ?: 0L)
            .coerceIn(0L, SHELL_TIMEOUT_MAX_SECONDS) * 1_000L
        val result = background.manager.readOutput(background.conversationId, taskId, waitMillis)
        listOf(
            UIMessagePart.Text(
                result.task.toJson {
                    put("output", result.output)
                    if (result.skippedBytes > 0) {
                        put("skippedBytes", result.skippedBytes)
                        put("note", "Earlier output was omitted. The full log is at ${result.task.logPath}.")
                    }
                }.toString()
            )
        )
    },
)

private fun createShellKillTool(background: BackgroundShell) = Tool(
    name = "workspace_shell_kill",
    description = "Stop a background command started by workspace_shell with run_in_background, together with every process it started.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("task_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Task id returned by workspace_shell")
                })
            },
            required = listOf("task_id"),
        )
    },
    execute = {
        val taskId = it.jsonObject.string("task_id") ?: error("task_id is required")
        val task = background.manager.kill(background.conversationId, taskId)
        listOf(UIMessagePart.Text(task.toJson().toString()))
    },
)

private fun BackgroundShellTask.toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    putBackgroundShellTask(this@toJson)
    extra()
}

internal fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.workspaceReadOffset(): Int =
    (string("offset")?.toLongOrNull() ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

internal suspend fun WorkspaceRepository.readTextInRootfs(
    workspaceId: String,
    path: String,
): String = withContext(Dispatchers.IO) {
    readRootfsBuffer(workspaceId, path).toString(Charsets.UTF_8.name())
}

/**
 * 按环境内绝对路径读入内存。路径映射交给工作区后端, 本地工作区由它统一处理
 * /workspace、bind mount 与 Rootfs 内部路径。
 */
private suspend fun WorkspaceRepository.readRootfsBuffer(
    workspaceId: String,
    path: String,
): ByteArrayOutputStream {
    val message = "File is too large to read: $path (max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). " +
        "Use shell commands like head, tail, or grep to read parts of it."
    return ByteArrayOutputStream().also { exportBoundedRead(workspaceId, path, it, MAX_READ_FILE_BYTES, message) }
}

/** Enforce the limit on incoming bytes, including when remote metadata is inaccurate. */
internal suspend fun WorkspaceRepository.exportBoundedRead(
    workspaceId: String,
    path: String,
    output: OutputStream,
    maxBytes: Long,
    limitMessage: String,
) {
    val bounded = object : FilterOutputStream(output) {
        private var remaining = maxBytes

        private fun reserve(byteCount: Int) {
            if (byteCount > remaining) throw IOException(limitMessage)
            remaining -= byteCount
        }

        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            out.write(bytes, offset, length)
        }
    }
    exportAbsoluteFile(workspaceId, path, bounded)
}

private suspend fun WorkspaceRepository.readImageInRootfs(
    workspaceId: String,
    path: String,
): List<UIMessagePart> {
    val bytes = readRootfsBuffer(workspaceId, path).toByteArray()

    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
}

internal fun kotlinx.serialization.json.JsonObject.absolutePath(name: String): String {
    val path = string(name)?.replace('\\', '/')?.trim() ?: error("$name is required")
    require(path.isNotBlank()) { "$name is required" }
    require(path.startsWith("/")) { "$name must be an absolute path inside Rootfs" }
    require(!path.contains('\u0000')) { "$name contains invalid character" }
    return path
}

// 免强制审批的可写安全区由工作区后端给出, 本地为工作区文件目录、临时目录和技能目录
private fun kotlinx.serialization.json.JsonElement.pathOutsideWritableRoots(
    layout: WorkspaceLayout,
    name: String,
): Boolean =
    runCatching {
        !layout.isWritable(jsonObject.absolutePath(name))
    }.getOrDefault(true)

internal fun JsonObjectBuilder.putPathProperty(layout: WorkspaceLayout, required: Boolean) {
    put("path", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (required) {
                "Absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            } else {
                "Optional absolute path inside Rootfs. Use ${layout.workspaceDir} for the workspace files area."
            }
        )
    })
}

internal fun WorkspaceFileEntry.toJson(extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
    extra()
}

/** 写入后回读, 内容不一致时报错, 让模型知道这次写入没有生效 */
internal fun requireWriteVerified(path: String, expected: String, actual: String) {
    require(expected == actual) {
        "Write verification failed for $path: wrote ${expected.toByteArray(Charsets.UTF_8).size} bytes " +
            "but read back ${actual.toByteArray(Charsets.UTF_8).size} bytes"
    }
}
