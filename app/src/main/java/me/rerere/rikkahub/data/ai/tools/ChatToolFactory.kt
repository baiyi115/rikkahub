package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.service.BackgroundShellManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 子代理没有可读的 /tool_outputs 路径，嵌套工具直接截断，避免大文件撑爆上下文
private const val SUBAGENT_MAX_TOOL_OUTPUT_CHARS = 16 * 1024
private const val SUBAGENT_TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

// 只读、不共享可变状态的工具: 连续调用时 AgentLoop 可以并发执行
private val CONCURRENCY_SAFE_TOOL_NAMES = setOf(
    "search_web",
    "scrape_web",
    "workspace_read_file",
    "workspace_read_document",
    "workspace_glob",
    "workspace_grep",
    "workspace_list_directory",
    "memory_read",
    "memory_list",
    "calendar_query",
    "get_screen_time",
    "get_time_info",
    "recent_chats",
    "conversation_read",
    "conversation_search",
    "use_skill",
    // 只读的控制工具: 等待和查询不改变子代理状态, 可以和其它只读调用一起并发
    SUBAGENT_WAIT_TOOL_NAME,
    SUBAGENT_LIST_TOOL_NAME,
)

/**
 * 工具输出截断时保留头尾。
 *
 * 只保留开头会丢掉命令末尾的错误和最终结果; 头尾各留一半, 中间用省略标记代替。
 */
internal fun toolOutputPreview(text: String, maxChars: Int): String {
    require(maxChars > 0) { "maxChars must be positive" }
    if (text.length <= maxChars) return text
    val head = maxChars / 2
    val tail = maxChars - head
    return buildString {
        append(text.take(head))
        appendLine()
        appendLine("... [${text.length - maxChars} characters omitted] ...")
        append(text.takeLast(tail))
    }
}

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val backgroundShellManager: BackgroundShellManager,
    private val providerManager: ProviderManager,
    private val usageRecorder: UsageRecorder,
    private val subagentManager: SubagentManager,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        conversationId: Uuid,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val workspaceTools =
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd, conversationId)
        val baseTools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(
                localTools.getTools(
                    options = assistant.localTools,
                    conversationId = conversationId,
                    backgroundShellManager = backgroundShellManager,
                    localShellUseRoot = assistant.localShellUseRoot,
                    localShellNeedsApproval = assistant.localShellNeedsApproval,
                )
            )
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        // 服务端用 readOnlyHint 声明的工具不共享可变状态, 可以和其它只读调用一起并发
                        isConcurrencySafe = { tool.readOnly },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }.map { tool ->
            if (tool.name in CONCURRENCY_SAFE_TOOL_NAMES) tool.copy(isConcurrencySafe = { true }) else tool
        }
        // 子代理工具在完整工具集之后创建: 它拿到的是去掉自身和交互式工具的一份, 避免递归调用。
        // 嵌套工具先做子代理专用截断: 子代理读不到 /tool_outputs, 不能沿用主对话的落盘方案。
        val tools = if (LocalToolOption.Subagent in assistant.localTools) {
            baseTools +
                createSubagentTool(
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    conversationId = conversationId,
                    tools = baseTools.map { withSubagentOutputCap(it) },
                    providerManager = providerManager,
                    usageRecorder = usageRecorder,
                    manager = subagentManager,
                ) +
                createSubagentControlTools(subagentManager, conversationId)
        } else {
            baseTools
        }
        // 过长的输出存成文件让模型用 shell 按需读取；没有 shell 时模型读不到文件，只能原样返回
        val localShellEnabled = LocalToolOption.LocalShell in assistant.localTools
        val canReadToolOutputs = localShellEnabled ||
            (workspaceTools.isNotEmpty() &&
                assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs ==
                true)
        return if (canReadToolOutputs) tools.map { withOutputTruncation(it, localShellEnabled) } else tools
    }

    private fun withOutputTruncation(tool: Tool, includeHostPath: Boolean): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it), includeHostPath) })
    }

    /**
     * 子代理内部的工具输出只保留一段预览。
     *
     * 主对话的 [withOutputTruncation] 会把完整输出写到 `/tool_outputs`，但子代理拿不到这个路径
     * (Shell 工具已被排除)，所以这里只截断、不落盘，让模型改用更窄的读取或搜索。
     */
    private fun withSubagentOutputCap(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { args ->
            val output = execute(args)
            val textParts = output.filterIsInstance<UIMessagePart.Text>()
            val totalChars = textParts.sumOf { it.text.length }
            if (totalChars <= SUBAGENT_MAX_TOOL_OUTPUT_CHARS) {
                output
            } else {
                val fullText = textParts.joinToString("\n") { it.text }
                listOf(
                    UIMessagePart.Text(
                        buildString {
                            appendLine("[Tool output truncated for the subagent: $totalChars characters total]")
                            appendLine(
                                "Head and tail ($SUBAGENT_TOOL_OUTPUT_PREVIEW_CHARS characters) are shown. " +
                                    "Narrow the request if you need more."
                            )
                            appendLine()
                            append(toolOutputPreview(fullText, SUBAGENT_TOOL_OUTPUT_PREVIEW_CHARS))
                        }
                    )
                ) + output.filter { it !is UIMessagePart.Text }
            }
        })
    }

    private fun truncateOutput(output: List<UIMessagePart>, includeHostPath: Boolean): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    if (includeHostPath) {
                        appendLine("Local shell path: ${File(outputDir, fileName).absolutePath}")
                    }
                    val shellPath = if (includeHostPath) {
                        File(
                            outputDir,
                            fileName
                        ).absolutePath
                    } else {
                        "/tool_outputs/$fileName"
                    }
                    appendLine("Use shell to read: `cat '$shellPath'`")
                    appendLine("Use shell to search: `grep \"pattern\" '$shellPath'`")
                    appendLine()
                    append(toolOutputPreview(fullText, TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String?,
        conversationId: Uuid,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId,
            workspaceRepository,
            context.cacheDir,
            cwd,
            conversationId,
            backgroundShellManager,
        )
    }
}
