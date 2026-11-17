package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceSearchMatch
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

private const val DEFAULT_LIST_LIMIT = 200
private const val MAX_LIST_LIMIT = 1_000
private const val DEFAULT_GLOB_DEPTH = 6
private const val MAX_GLOB_DEPTH = 20
private const val DEFAULT_SEARCH_RESULTS = 100
private const val MAX_SEARCH_RESULTS = 1_000
private const val GREP_OUTPUT_LINES = 2_000
private const val SEARCH_TIMEOUT_MS = 30_000L

/**
 * 只读的目录浏览与内容检索工具。
 *
 * 目录列举沿用工作区后端的文件访问; glob 与 grep 在 Rootfs 内用 find/grep 执行,
 * 本地与 SSH 工作区的语义一致, 也不会把整个目录树读进内存。
 */
internal fun createWorkspaceSearchTools(
    workspaceId: String,
    layout: WorkspaceLayout,
    workspaceRepository: WorkspaceRepository,
): List<Tool> = listOf(
    createListDirectoryTool(workspaceId, layout, workspaceRepository),
    createGlobTool(workspaceId, layout, workspaceRepository),
    createGrepTool(workspaceId, layout, workspaceRepository),
)

private fun createListDirectoryTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_list_directory",
    description = """
        List the entries of a directory inside the assistant's bound workspace.
        Paths must be absolute; use ${layout.workspaceDir} for the workspace files area.
        Returns name, type, size and modification time, with directories first.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("limit", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum entries to return. Default $DEFAULT_LIST_LIMIT, max $MAX_LIST_LIMIT."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val limit = params.intValue("limit", DEFAULT_LIST_LIMIT, 1, MAX_LIST_LIMIT)
        val entries = workspaceRepository.listAbsolute(workspaceId, path)
        val payload = buildJsonObject {
            put("path", path)
            put("count", minOf(entries.size, limit))
            put("total", entries.size)
            put("truncated", entries.size > limit)
            put("entries", buildJsonArray { entries.take(limit).forEach { add(it.toJson()) } })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createGlobTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_glob",
    description = """
        Find files and directories whose path matches a glob pattern (Java glob syntax).
        The pattern is matched against the path relative to the search root; a pattern without "/" such as *.kt
        matches file names at any depth. Paths must be absolute; the search root defaults to ${layout.workspaceDir}.
        Returns absolute paths, directories and files together.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject {
                    put("type", "string")
                    put("description", "Glob pattern, for example **/*.kt or src/**/Test*.kt.")
                })
                putPathProperty(layout, required = false)
                put("max_depth", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum directory depth to walk. Default $DEFAULT_GLOB_DEPTH, max $MAX_GLOB_DEPTH."
                    )
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum matching paths to return. Default $DEFAULT_SEARCH_RESULTS, max $MAX_SEARCH_RESULTS."
                    )
                })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val params = it.jsonObject
        val pattern = params.string("pattern")?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("pattern is required")
        val base = params.string("path")?.takeIf { it.isNotBlank() }
            ?.let { params.absolutePath("path") }
            ?: layout.workspaceDir
        val depth = params.intValue("max_depth", DEFAULT_GLOB_DEPTH, 1, MAX_GLOB_DEPTH)
        val maxResults = params.intValue("max_results", DEFAULT_SEARCH_RESULTS, 1, MAX_SEARCH_RESULTS)
        val result = workspaceRepository.executeCommand(
            workspaceId,
            command = buildFindCommand(base, depth),
            timeoutMillis = SEARCH_TIMEOUT_MS,
        )
        if (result.timedOut) error("Glob search timed out after ${SEARCH_TIMEOUT_MS / 1000}s: $base")
        val paths = parseFindOutput(result.stdout, truncated = result.truncated)
        if (paths.isEmpty() && result.stderr.isNotBlank()) {
            error("Glob search failed: ${result.stderr.trim()}")
        }
        val (matched, truncated) = matchGlobPaths(base, pattern, paths, maxResults)
        val payload = buildJsonObject {
            put("path", base)
            put("pattern", pattern)
            put("count", matched.size)
            put("truncated", truncated)
            put("paths", buildJsonArray { matched.forEach { add(it) } })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createGrepTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_grep",
    description = """
        Search file contents in the assistant's bound workspace with grep and return matching lines.
        Paths must be absolute; the search root defaults to ${layout.workspaceDir}.
        Binary files and unreadable files are skipped. Use regex=false for a fixed string.
        Each match returns the absolute file path, the line number and the full line.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Text or regular expression to search for.")
                })
                putPathProperty(layout, required = false)
                put("regex", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Interpret query as a regular expression (POSIX ERE). Defaults to false.")
                })
                put("ignore_case", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Case-insensitive search. Defaults to true.")
                })
                put("include", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional glob to restrict files, for example *.kt or src/**/*.md.")
                })
                put("max_results", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Maximum matches to return. Default $DEFAULT_SEARCH_RESULTS, max $MAX_SEARCH_RESULTS."
                    )
                })
            },
            required = listOf("query"),
        )
    },
    execute = {
        val params = it.jsonObject
        val query = params.string("query")?.takeIf { it.isNotEmpty() } ?: error("query is required")
        val base = params.string("path")?.takeIf { it.isNotBlank() }
            ?.let { params.absolutePath("path") }
            ?: layout.workspaceDir
        val regex = params.string("regex")?.toBooleanStrictOrNull() ?: false
        val ignoreCase = params.string("ignore_case")?.toBooleanStrictOrNull() ?: true
        val include = params.string("include")?.trim()?.takeIf { it.isNotEmpty() }
        val maxResults = params.intValue("max_results", DEFAULT_SEARCH_RESULTS, 1, MAX_SEARCH_RESULTS)
        val result = workspaceRepository.executeCommand(
            workspaceId,
            command = buildGrepCommand(base, query, regex = regex, ignoreCase = ignoreCase),
            timeoutMillis = SEARCH_TIMEOUT_MS,
        )
        if (result.timedOut) error("Grep timed out after ${SEARCH_TIMEOUT_MS / 1000}s: $base")
        var matches = parseGrepOutput(result.stdout, truncated = result.truncated)
        if (matches.isEmpty() && result.exitCode !in setOf(0, 1) && result.stderr.isNotBlank()) {
            error("Grep failed: ${result.stderr.trim()}")
        }
        if (include != null) {
            matches = matches.filter { globMatchesPath(include, base, it.path) }
        }
        val truncated = matches.size > maxResults || result.truncated ||
            result.stdout.count { it == '\n' } >= GREP_OUTPUT_LINES
        val limited = matches.take(maxResults)
        val payload = buildJsonObject {
            put("path", base)
            put("query", query)
            put("count", limited.size)
            put("truncated", truncated)
            put(
                "matches",
                buildJsonArray {
                    limited.forEach { match ->
                        add(buildJsonObject {
                            put("path", match.path)
                            put("line", match.line)
                            put("text", match.text)
                        })
                    }
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun kotlinx.serialization.json.JsonObject.intValue(
    name: String,
    default: Int,
    min: Int,
    max: Int,
): Int = this[name]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(min, max) ?: default

internal fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"

internal fun buildFindCommand(base: String, maxDepth: Int): String =
    "find ${base.shellQuote()} -maxdepth $maxDepth \\( -type f -o -type d \\) -print0 2>/dev/null"

internal fun buildGrepCommand(base: String, query: String, regex: Boolean, ignoreCase: Boolean): String {
    val flags = buildString {
        append("-r -n -I")
        if (ignoreCase) append(" -i")
        append(if (regex) " -E" else " -F")
    }
    return "grep $flags -e ${query.shellQuote()} ${base.shellQuote()} 2>/dev/null | head -n $GREP_OUTPUT_LINES"
}

/** find 以 NUL 分隔输出; 输出被截断时丢掉最后一条可能不完整的记录 */
internal fun parseFindOutput(stdout: String, truncated: Boolean): List<String> {
    if (stdout.isEmpty()) return emptyList()
    val records = stdout.split('\u0000')
    val complete = if (truncated) records.dropLast(1) else records
    return complete.filter { it.isNotBlank() }
}

private val GREP_MATCH_LINE = Regex("^(.+):(\\d+):(.*)$")

/**
 * 解析 `grep -r -n` 的输出。路径从行首贪婪匹配到最后一个 `:<数字>:` 之前,
 * 这样路径里的冒号不会截断文件名; 行内容里恰好出现 `:<数字>:` 时仍可能误切, 属于已知限制。
 */
internal fun parseGrepOutput(stdout: String, truncated: Boolean): List<WorkspaceSearchMatch> {
    if (stdout.isEmpty()) return emptyList()
    val lines = stdout.lineSequence().filter { it.isNotEmpty() }.toList()
    val usable = if (truncated) lines.dropLast(1) else lines
    return usable.mapNotNull { line ->
        val match = GREP_MATCH_LINE.matchEntire(line) ?: return@mapNotNull null
        WorkspaceSearchMatch(
            path = match.groupValues[1],
            line = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null,
            text = match.groupValues[3],
        )
    }
}

/** 返回命中的路径与是否因为 [limit] 截断 */
internal fun matchGlobPaths(
    base: String,
    pattern: String,
    paths: List<String>,
    limit: Int,
): Pair<List<String>, Boolean> {
    val matchers = globMatchers(pattern)
    val matchBaseName = '/' !in pattern
    val basePrefix = base.trimEnd('/')
    val matched = paths.filter { path ->
        val relative = path.removePrefix(basePrefix).trimStart('/').ifEmpty { "." }
        val relativePath = FileSystems.getDefault().getPath(relative)
        val name = relativePath.fileName ?: relativePath
        matchers.any { it.matches(relativePath) } ||
            (matchBaseName && matchers.any { it.matches(name) })
    }
    return matched.take(limit) to (matched.size > limit)
}

private fun globMatchesPath(pattern: String, base: String, path: String): Boolean {
    val matchers = globMatchers(pattern)
    val matchBaseName = '/' !in pattern
    val relative = path.removePrefix(base.trimEnd('/')).trimStart('/').ifEmpty { "." }
    val relativePath = FileSystems.getDefault().getPath(relative)
    val name = relativePath.fileName ?: relativePath
    return matchers.any { it.matches(relativePath) } ||
        (matchBaseName && matchers.any { it.matches(name) })
}

// Java 的 glob 里 "**/" 至少要求一层目录, 顶层的文件也要能命中
private fun globMatchers(pattern: String): List<PathMatcher> = buildList {
    add(globMatcher(pattern))
    if (pattern.startsWith("**/")) add(globMatcher(pattern.removePrefix("**/")))
}

private fun globMatcher(pattern: String) = try {
    FileSystems.getDefault().getPathMatcher("glob:$pattern")
} catch (e: Exception) {
    throw IllegalArgumentException("Invalid glob pattern: $pattern", e)
}
