package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.putBackgroundShellTask
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.rikkahub.service.BackgroundShellOutput
import me.rerere.rikkahub.service.BackgroundShellStatus
import me.rerere.rikkahub.service.BackgroundShellTask
import kotlin.uuid.Uuid

const val LOCAL_SHELL_TOOL_NAME = "local_shell"
const val LOCAL_SHELL_JOB_TOOL_NAME = "local_shell_job"

private const val DEFAULT_JOB_READ_BYTES = 16 * 1024
private const val MAX_JOB_READ_BYTES = 64 * 1024
private const val MAX_JOB_WAIT_MS = 60_000L

/** [defaultRoot] 仅在设备上存在 su 时生效, 模型仍可显式传 identity 覆盖 */
fun buildLocalShellTool(
    runner: LocalShellRunner,
    conversationId: Uuid,
    defaultRoot: Boolean,
    needsApproval: Boolean = true,
): Tool {
    val defaultIdentity = if (defaultRoot && LocalShellRuntime.isRootAvailable) "root" else "user"
    val defaultCwd = runner.defaultCwd.absolutePath
    return Tool(
        name = LOCAL_SHELL_TOOL_NAME,
        description = """
            Run shell commands on this Android device itself, not in the Linux workspace sandbox.
            The shell is $SYSTEM_SHELL (mksh) with toybox utilities; setsid is required for safe process isolation.
            bash, apt, Python or Node are not available unless
            installed separately, and the app usually cannot read arbitrary /sdcard paths without storage permission.
            Default working directory: $defaultCwd. cwd may be absolute or relative to it.
            Default identity: $defaultIdentity. With root (su), commands can use pm, am, dumpsys, settings, appops and
            read protected files; the first root command may show a system superuser authorization dialog.
            For shell state across commands, call action=open first, then pass session_id to action=exec; cd, environment
            variables and shell variables persist. action=close closes a session and action=list shows this chat's sessions.
            Each exec without session_id runs in a fresh shell; background processes left with & are killed when the
            command returns. Session commands read stdin from /dev/null, so use pipes or heredocs for input; a command
            that times out or is cancelled closes the whole session. Descriptor 3 is reserved for the control protocol;
            do not close or redirect it. exec that replaces the shell and exit end the session.
            Use local_shell_job for long-running or background commands.
            Output is capped at 32768 characters per stream (merged stdout/stderr in sessions).
            Excess output is discarded; redirect large output to a file and read it in pieces.
            Returns JSON with exitCode, stdout, stderr, timedOut and truncated.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("exec"))
                            add(JsonPrimitive("open"))
                            add(JsonPrimitive("close"))
                            add(JsonPrimitive("list"))
                            add(JsonPrimitive("check_root"))
                        })
                        put(
                            "description",
                            "exec (default) runs a command; open creates a persistent shell; close closes one; " +
                                "list shows this chat's sessions; check_root runs su to test current root authorization."
                        )
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Shell command to run. Required for exec.")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Working directory, absolute or relative to the default directory. In a session it changes " +
                                "the session's current directory. Defaults to $defaultCwd."
                        )
                    })
                    put("timeout", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Timeout in seconds. Defaults to $DEFAULT_TIMEOUT_SECONDS, max $MAX_TIMEOUT_SECONDS."
                        )
                    })
                    put("identity", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("user"))
                            add(JsonPrimitive("root"))
                        })
                        put(
                            "description",
                            "Execution identity. Defaults to $defaultIdentity. For a session it must match the identity " +
                                "the session was opened with."
                        )
                    })
                    put("session_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Session id returned by action=open; used by exec and close.")
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input ->
            needsApproval && when (input.jsonObject.actionOrNull()) {
                "exec", "open", "check_root" -> true
                else -> false
            }
        },
        execute = { input ->
            executeLocalShellTool(runner, conversationId, input.jsonObject, defaultIdentity)
        },
    )
}

/** 后台任务由 [BackgroundShellManager] 管理, 回复结束后继续运行并发送完成通知 */
fun buildLocalShellJobTool(
    runner: LocalShellRunner,
    manager: BackgroundShellManager,
    conversationId: Uuid,
    defaultRoot: Boolean,
    needsApproval: Boolean = true,
): Tool {
    val defaultIdentity = if (defaultRoot && LocalShellRuntime.isRootAvailable) "root" else "user"
    return Tool(
        name = LOCAL_SHELL_JOB_TOOL_NAME,
        description = """
            Start and manage background shell commands on this Android device. action=start returns a job id
            immediately and the command keeps running after the reply; a system notification is sent when it finishes.
            Logs are written to a file and output_path is returned, so use local_shell to read the full log if the
            preview is truncated. action=list shows jobs, read returns new output, wait blocks until the job finishes
            or wait_ms elapses, and stop terminates the job with its child processes.
            When the command shell exits, remaining processes in its group are killed before completion is reported.
            Do not daemonize or detach with setsid; detached daemons and recovery after app restart are not supported.
            The command runs in its own shell and does not inherit local_shell session state.
            Use this for dev servers, builds, downloads and other long-running commands instead of a foreground exec.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("start"))
                            add(JsonPrimitive("list"))
                            add(JsonPrimitive("read"))
                            add(JsonPrimitive("wait"))
                            add(JsonPrimitive("stop"))
                        })
                    })
                    put("command", buildJsonObject {
                        put("type", "string")
                        put("description", "Command to run in the background. Required for start.")
                    })
                    put("cwd", buildJsonObject {
                        put("type", "string")
                        put("description", "Working directory, absolute or relative to the default directory.")
                    })
                    put("identity", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("user"))
                            add(JsonPrimitive("root"))
                        })
                        put("description", "Execution identity. Defaults to $defaultIdentity.")
                    })
                    put("job_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Job id returned by start; used by read, wait and stop.")
                    })
                    put("active_only", buildJsonObject {
                        put("type", "boolean")
                        put("description", "For list: only return running jobs. Defaults to true.")
                    })
                    put("max_bytes", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "For read/wait: maximum log bytes to return. Defaults to $DEFAULT_JOB_READ_BYTES, " +
                                "max $MAX_JOB_READ_BYTES."
                        )
                    })
                    put("wait_ms", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "For wait: maximum time to wait for completion, default 30000, max $MAX_JOB_WAIT_MS."
                        )
                    })
                },
                required = listOf("action"),
            )
        },
        needsApproval = { input -> needsApproval && input.jsonObject.actionOrNull() == "start" },
        execute = { input ->
            executeLocalShellJobTool(runner, manager, conversationId, input.jsonObject, defaultIdentity)
        },
    )
}

private suspend fun executeLocalShellTool(
    runner: LocalShellRunner,
    conversationId: Uuid,
    params: JsonObject,
    defaultIdentity: String,
): List<UIMessagePart> {
    val action = params.actionOrNull()
    val identity = params.resolveIdentity(defaultIdentity) ?: return localShellError(
        "identity must be 'user' or 'root'"
    )
    val timeoutSeconds = params.longValue("timeout")?.coerceIn(1L, MAX_TIMEOUT_SECONDS)
        ?: DEFAULT_TIMEOUT_SECONDS
    return try {
        when (action) {
            "exec" -> execInLocalShell(runner, conversationId, params, identity, timeoutSeconds * 1_000L)
            "open" -> {
                val session = runner.openSession(conversationId, root = identity == "root", cwd = params.stringValue("cwd"))
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("action", "open")
                            put("session_id", session.id)
                            put("identity", session.identity)
                            put("cwd", session.cwd)
                        }.toString()
                    )
                )
            }

            "close" -> {
                val sessionId = params.stringValue("session_id")
                if (sessionId.isNullOrBlank()) {
                    localShellError("session_id is required for action=close")
                } else {
                    val closed = withContext(Dispatchers.IO) { runner.closeSession(conversationId, sessionId) }
                    listOf(
                        UIMessagePart.Text(
                            buildJsonObject {
                                put("ok", closed)
                                put("action", "close")
                                put("session_id", sessionId)
                                put("closed", closed)
                                if (!closed) put("error", "Session not found: $sessionId")
                            }.toString()
                        )
                    )
                }
            }

            "list" -> listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("ok", true)
                        put("action", "list")
                        putJsonArray("sessions") {
                            runner.listSessions(conversationId).forEach { session ->
                                addJsonObject {
                                    put("session_id", session.id)
                                    put("identity", session.identity)
                                    put("cwd", session.cwd)
                                    put("alive", session.alive)
                                }
                            }
                        }
                    }.toString()
                )
            )

            "check_root" -> rootCheckResult(runner, timeoutSeconds * 1_000L)
            else -> localShellError("Unknown action: $action")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        localShellError(e.message ?: e.javaClass.simpleName)
    }
}

private suspend fun execInLocalShell(
    runner: LocalShellRunner,
    conversationId: Uuid,
    params: JsonObject,
    identity: String,
    timeoutMillis: Long,
): List<UIMessagePart> {
    val command = params.stringValue("command").orEmpty().trim()
    if (command.isEmpty()) return localShellError("command is required for action=exec")
    val sessionId = params.stringValue("session_id")
    val cwd = params.stringValue("cwd")

    if (!sessionId.isNullOrBlank()) {
        val session = runner.findSession(conversationId, sessionId)
            ?: return localShellError("Session not found or already closed: $sessionId")
        if (params.stringValue("identity") != null && identity != session.identity) {
            return localShellError(
                "Session $sessionId runs as ${session.identity}; open a new session for identity=$identity"
            )
        }
        val result = runner.executeSession(conversationId, sessionId, command, cwd, timeoutMillis)
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("action", "exec")
                    put("session_id", sessionId)
                    put("identity", session.identity)
                    put("cwd", session.cwd)
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    put("sessionClosed", result.sessionClosed)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    }

    val result = runner.execute(
        command = command,
        cwd = cwd,
        timeoutMillis = timeoutMillis,
        root = identity == "root",
    )
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", true)
                put("action", "exec")
                put("identity", identity)
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
                if (result.truncated) put("truncated", true)
            }.toString()
        )
    )
}

private suspend fun rootCheckResult(runner: LocalShellRunner, timeoutMillis: Long): List<UIMessagePart> {
    if (!LocalShellRuntime.isRootAvailable) {
        return listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("ok", true)
                    put("action", "check_root")
                    put("rootAvailable", false)
                    put("rootGranted", false)
                    put("message", "su was not found on this device")
                }.toString()
            )
        )
    }
    val result = runner.checkRoot(timeoutMillis)
    return listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", true)
                put("action", "check_root")
                put("rootAvailable", true)
                put("rootGranted", result.exitCode == 0 && result.stdout.trim() == "0")
                put("exitCode", result.exitCode)
                put("stdout", result.stdout)
                put("stderr", result.stderr)
                put("timedOut", result.timedOut)
            }.toString()
        )
    )
}

private suspend fun executeLocalShellJobTool(
    runner: LocalShellRunner,
    manager: BackgroundShellManager,
    conversationId: Uuid,
    params: JsonObject,
    defaultIdentity: String,
): List<UIMessagePart> {
    val action = params.actionOrNull()
    val identity = params.resolveIdentity(defaultIdentity) ?: return localShellError(
        "identity must be 'user' or 'root'"
    )
    return try {
        when (action) {
            "start" -> {
                val command = params.stringValue("command").orEmpty().trim()
                if (command.isEmpty()) {
                    localShellError("command is required for action=start")
                } else {
                    val task = manager.startLocal(
                        conversationId = conversationId,
                        runner = runner,
                        command = command,
                        cwd = params.stringValue("cwd"),
                        root = identity == "root",
                    )
                    listOf(UIMessagePart.Text(task.toJobJson(extra = { put("ok", true); put("action", "start") })
                        .toString()))
                }
            }

            "list" -> {
                val activeOnly = params.booleanValue("active_only") ?: true
                val tasks = manager.tasksOf(conversationId)
                    .filter { it.workspaceId == LOCAL_SHELL_WORKSPACE_ID }
                    .filter { !activeOnly || it.status == BackgroundShellStatus.RUNNING }
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("ok", true)
                            put("action", "list")
                            putJsonArray("jobs") {
                                tasks.forEach { task -> addJsonObject { task.writeJobFields(this) } }
                            }
                        }.toString()
                    )
                )
            }

            "read", "wait" -> {
                val jobId = params.stringValue("job_id")
                if (jobId.isNullOrBlank()) {
                    localShellError("job_id is required for action=$action")
                } else {
                    val waitMillis = if (action == "wait") {
                        (params.longValue("wait_ms") ?: 30_000L).coerceIn(0L, MAX_JOB_WAIT_MS)
                    } else {
                        0L
                    }
                    val maxBytes = (params.longValue("max_bytes")?.toInt() ?: DEFAULT_JOB_READ_BYTES)
                        .coerceIn(1, MAX_JOB_READ_BYTES)
                    val output = manager.readOutput(conversationId, jobId, waitMillis, maxBytes)
                    listOf(UIMessagePart.Text(output.toJobOutputJson(action).toString()))
                }
            }

            "stop" -> {
                val jobId = params.stringValue("job_id")
                if (jobId.isNullOrBlank()) {
                    localShellError("job_id is required for action=stop")
                } else {
                    val task = manager.kill(conversationId, jobId)
                    listOf(UIMessagePart.Text(task.toJobJson(extra = { put("ok", true); put("action", "stop") })
                        .toString()))
                }
            }

            else -> localShellError("Unknown action: $action")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        localShellError(e.message ?: e.javaClass.simpleName)
    }
}

private fun localShellError(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()
    )
)

private fun BackgroundShellTask.toJobJson(
    extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): kotlinx.serialization.json.JsonObject = buildJsonObject {
    writeJobFields(this)
    extra()
}

private fun BackgroundShellTask.writeJobFields(builder: kotlinx.serialization.json.JsonObjectBuilder) {
    builder.putBackgroundShellTask(this)
    builder.put("job_id", id)
    builder.put("command", command)
    builder.put("startedAt", startedAt)
    endedAt?.let { builder.put("endedAt", it) }
    builder.put("output_path", logPath)
}

private fun BackgroundShellOutput.toJobOutputJson(action: String): kotlinx.serialization.json.JsonObject =
    buildJsonObject {
        put("ok", true)
        put("action", action)
        task.writeJobFields(this)
        put("stdout", output)
        if (skippedBytes > 0) put("skippedBytes", skippedBytes)
    }

private fun JsonObject.actionOrNull(): String =
    stringValue("action")?.lowercase()?.ifBlank { "exec" } ?: "exec"

/** 返回 null 表示非法值 */
private fun JsonObject.resolveIdentity(defaultIdentity: String): String? =
    when (val requested = stringValue("identity")?.lowercase()) {
        "root" -> "root"
        "user" -> "user"
        null, "" -> defaultIdentity
        else -> null
    }

private fun JsonObject.stringValue(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.longValue(key: String): Long? =
    stringValue(key)?.toLongOrNull()

private fun JsonObject.booleanValue(key: String): Boolean? =
    stringValue(key)?.toBooleanStrictOrNull()
