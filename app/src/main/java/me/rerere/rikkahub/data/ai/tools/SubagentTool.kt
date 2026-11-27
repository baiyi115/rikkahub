package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.UsageRecorder
import me.rerere.rikkahub.data.ai.UsageSource
import me.rerere.rikkahub.data.ai.UsageTag
import me.rerere.rikkahub.data.ai.retryPolicy
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

internal const val SUBAGENT_TOOL_NAME = "subagent"

private const val SUBAGENT_MAX_STEPS = 24
private const val SUBAGENT_MAX_STEPS_LIMIT = 64
private const val SUBAGENT_DEFAULT_TIMEOUT_SECONDS = 600L
private const val SUBAGENT_MAX_TIMEOUT_SECONDS = 1_800L
private const val SUBAGENT_DEFAULT_TOKEN_BUDGET = 1_000_000
private const val SUBAGENT_MAX_TOKEN_BUDGET = 4_000_000
private const val SUBAGENT_MAX_TASK_CHARS = 16_000

private class SubagentTokenBudgetExceededException :
    RuntimeException("Subagent token budget exceeded")

/** 子代理默认只读: 写文件和其它有副作用的工具需要主代理显式允许 */
private val SUBAGENT_SIDE_EFFECT_TOOLS = setOf(
    "workspace_write_file",
    "workspace_edit_file",
    "calendar_create",
    "clipboard_tool",
    "text_to_speech",
    "eval_javascript",
    "chart_display",
) + MemoryToolNames.WRITES

/**
 * 需要用户介入、会接管界面或共享会话状态的工具不能交给子代理:
 * 嵌套运行没有审批卡片, 也不该弹出问答; Shell 会话和后台任务按 conversationId 归属,
 * 子代理与主代理共用同一个 conversationId, 交给它就能关闭主代理的会话或终止主代理的任务;
 * 浏览器是进程内复用的单个 WebView, 当前页面由所有调用方共享, 并发运行会互相覆盖。
 */
private val SUBAGENT_EXCLUDED_TOOLS = setOf(
    SUBAGENT_TOOL_NAME,
    "ask_user",
    "phone_use",
    "workspace_shell",
    "workspace_shell_output",
    "workspace_shell_kill",
    "local_shell",
    "local_shell_job",
    "browser_open",
    "browser_read",
)

/**
 * 子代理可用的工具: 去掉递归调用、交互式工具和共享会话状态的 Shell 工具;
 * 默认还去掉写文件、MCP 等有副作用的工具, 其余工具在子代理内一律不触发审批。
 * 需要审批的调用改为返回错误结果, 模型能据此换一种做法。
 */
internal fun subagentTools(tools: List<Tool>, allowSideEffects: Boolean = false): List<Tool> =
    tools
        .filterNot { it.name in SUBAGENT_EXCLUDED_TOOLS }
        .filterNot { !allowSideEffects && (it.name in SUBAGENT_SIDE_EFFECT_TOOLS || it.name.startsWith("mcp__")) }
        .map { it.withoutUserApproval() }

private fun subagentAllowSideEffects(args: JsonElement): Boolean =
    args.jsonObject["allow_side_effects"]?.jsonPrimitive?.booleanOrNull == true

/** 只读子代理不需要审批; 允许副作用时由用户确认 */
internal fun subagentNeedsApproval(args: JsonElement): Boolean = subagentAllowSideEffects(args)

/** 只读子代理彼此独立，可以和其它只读工具并发；允许副作用的调用不能 */
internal fun subagentIsConcurrencySafe(args: JsonElement): Boolean = !subagentAllowSideEffects(args)

private fun Tool.withoutUserApproval(): Tool {
    val delegate = this
    return copy(
        needsApproval = { false },
        execute = { args ->
            if (delegate.needsApproval(args)) {
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("error", "APPROVAL_REQUIRED")
                            put(
                                "message",
                                "Tool $name requires user approval and is not available inside a subagent."
                            )
                            put("retryable", false)
                        }.toString()
                    )
                )
            } else {
                delegate.execute(args)
            }
        }
    )
}

/**
 * 这条消息累计消耗的 token。
 *
 * 有的 Provider 只填 prompt/completion，totalTokens 留空，这时用两者之和兜底。
 */
private fun UIMessage.tokenCount(): Int {
    val used = usage ?: return 0
    return used.totalTokens.takeIf { it > 0 } ?: (used.promptTokens + used.completionTokens)
}

/**
 * 子代理的最终报告: 优先取最后一次工具调用之后的文本, 那才是这一轮的结论。
 *
 * 模型可能停在工具调用上 (例如刚好达到步数上限), 这时 [UIMessage.toText] 为空,
 * 但最后执行的工具结果往往就是已经完成的工作, 退回它比丢掉整次运行更有用。
 */
internal fun UIMessage.subagentReport(): String {
    // 最后一条工具调用之后的文本才是这一轮的结论; 前面的文字通常只是过渡说明
    val lastToolIndex = parts.indexOfLast { it is UIMessagePart.Tool && it.isExecuted }
    if (lastToolIndex >= 0) {
        val trailing = parts.drop(lastToolIndex + 1)
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .trim()
        if (trailing.isNotEmpty()) return trailing
    }
    val text = toText().trim()
    if (text.isNotEmpty()) return text
    return getTools()
        .asReversed()
        .firstOrNull { it.isExecuted }
        ?.output
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()
}

/**
 * 把一段独立任务交给隔离上下文的子代理执行, 只把最终报告带回主对话。
 *
 * 子代理复用当前会话的模型与参数, 工具集默认只读: 去掉子代理、交互式工具和共享会话状态的
 * Shell/浏览器, 需要写文件等副作用时由主代理显式允许 (并经过用户审批);
 * 用量记在 [UsageSource.SUBAGENT] 下, 方便区分主对话与子代理的开销。
 */
internal fun createSubagentTool(
    settings: Settings,
    assistant: Assistant,
    model: Model,
    conversationId: Uuid,
    tools: List<Tool>,
    providerManager: ProviderManager,
    usageRecorder: UsageRecorder,
): Tool {
    val readOnlyTools = subagentTools(tools)
    val sideEffectTools = subagentTools(tools, allowSideEffects = true)
    return Tool(
        name = SUBAGENT_TOOL_NAME,
        description = """
            Run a focused subagent with a fresh, isolated context to handle one subtask and return its report.
            Use it for self-contained work such as researching several sources, summarizing a long document,
            or exploring the workspace in an isolated context. Do not use it for a single quick lookup you can do
            yourself. The call runs synchronously: you wait for the report.
            Put every known path, constraint and the expected output format in the task; the subagent starts with
            no conversation history. It is read-only by default: set allow_side_effects=true only when the subtask
            must modify files, change memory/calendar/clipboard, speak, run JavaScript or call MCP tools; that
            requires user approval. It cannot run shell commands, use the shared in-app browser, ask the user
            questions, or use tools that need user approval.
            Its report is returned to you only, so relay what matters in your own reply.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Complete instructions for the subagent, including context and the expected report format."
                        )
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "A short 3-5 word description of the subtask, used as the step title.")
                    })
                    put("allow_side_effects", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "Allow file writes, memory/calendar/clipboard changes, TTS, JavaScript and MCP tools. " +
                                "Requires user approval. Defaults to false (read-only)."
                        )
                    })
                    put("max_steps", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum model steps for the subagent. Defaults to $SUBAGENT_MAX_STEPS, max $SUBAGENT_MAX_STEPS_LIMIT."
                        )
                    })
                    put("timeout_seconds", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Wall-clock timeout in seconds. Defaults to $SUBAGENT_DEFAULT_TIMEOUT_SECONDS, " +
                                "max $SUBAGENT_MAX_TIMEOUT_SECONDS."
                        )
                    })
                    put("max_total_tokens", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Maximum total tokens the subagent may use. Defaults to " +
                                "$SUBAGENT_DEFAULT_TOKEN_BUDGET, max $SUBAGENT_MAX_TOKEN_BUDGET."
                        )
                    })
                },
                required = listOf("task"),
            )
        },
        needsApproval = { args -> subagentNeedsApproval(args) },
        // 只读子代理彼此独立，可以和其它只读工具并发；允许副作用的调用仍按顺序执行
        isConcurrencySafe = { args -> subagentIsConcurrencySafe(args) },
        execute = { args ->
            val task = args.jsonObject["task"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("task is required")
            require(task.length <= SUBAGENT_MAX_TASK_CHARS) {
                "task is too long (${task.length} characters, max $SUBAGENT_MAX_TASK_CHARS)"
            }
            val allowSideEffects = subagentAllowSideEffects(args)
            val maxSteps = (args.jsonObject["max_steps"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: SUBAGENT_MAX_STEPS).coerceIn(1, SUBAGENT_MAX_STEPS_LIMIT)
            val timeoutSeconds = (args.jsonObject["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?: SUBAGENT_DEFAULT_TIMEOUT_SECONDS).coerceIn(1L, SUBAGENT_MAX_TIMEOUT_SECONDS)
            val maxTotalTokens = (args.jsonObject["max_total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: SUBAGENT_DEFAULT_TOKEN_BUDGET).coerceIn(1, SUBAGENT_MAX_TOKEN_BUDGET)
            val nestedTools = if (allowSideEffects) sideEffectTools else readOnlyTools
            val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
            val provider = usageRecorder.wrap(
                providerManager.getProviderByType(providerSetting),
                UsageTag(UsageSource.SUBAGENT, assistant.id, conversationId),
            )
            val systemPrompt = buildString {
                if (assistant.systemPrompt.isNotBlank()) {
                    appendLine(assistant.systemPrompt.trim())
                    appendLine()
                }
                append(SUBAGENT_SYSTEM_PROMPT)
                nestedTools.forEach { tool ->
                    appendLine()
                    append(tool.systemPrompt(model, listOf(UIMessage.user(task))))
                }
            }
            val messages = listOf(UIMessage.system(systemPrompt), UIMessage.user(task))
            val params = TextGenerationParams(
                model = model,
                temperature = assistant.temperature,
                topP = assistant.topP,
                maxTokens = assistant.maxTokens,
                reasoningLevel = assistant.reasoningLevel,
                customHeaders = assistant.customHeaders + model.customHeaders,
                customBody = assistant.customBodies + model.customBodies,
                sessionId = conversationId.toString(),
            )
            val hooks = object : AgentHooks {
                override suspend fun prepareStep(messages: List<UIMessage>) = StepRequest(messages, params)
            }
            val request = AgentRequest(
                provider = provider,
                providerSetting = providerSetting,
                messages = messages,
                tools = nestedTools,
                stream = false,
                maxSteps = maxSteps,
                retry = settings.retryPolicy(),
            )
            var finalMessage: UIMessage? = null
            var finishReason: AgentFinishReason? = null
            var timedOut = false
            var budgetExceeded = false
            var usedTokens = 0
            var failure: Exception? = null
            val startedAt = System.currentTimeMillis()
            try {
                withTimeout(timeoutSeconds * 1_000L) {
                    runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                        when (event) {
                            is AgentEvent.MessageUpdated -> {
                                finalMessage = event.message
                                usedTokens = maxOf(usedTokens, event.message.tokenCount())
                                if (usedTokens > maxTotalTokens) {
                                    throw SubagentTokenBudgetExceededException()
                                }
                            }

                            is AgentEvent.Finished -> {
                                // 已经结束的运行不再检查预算: 最后一步的回复本身就是结果
                                finalMessage = event.message
                                finishReason = event.reason
                                usedTokens = maxOf(usedTokens, event.message.tokenCount())
                            }

                            is AgentEvent.Retrying -> Unit
                        }
                    }
                }
            } catch (e: SubagentTokenBudgetExceededException) {
                budgetExceeded = true
            } catch (e: TimeoutCancellationException) {
                timedOut = true
            } catch (e: CancellationException) {
                // 用户停止生成时必须向上传播, 不能当成子代理失败
                throw e
            } catch (e: Exception) {
                failure = e
            }
            val report = finalMessage?.subagentReport().orEmpty()
            val failureMessage = failure?.let { e ->
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            // 嵌套调用没有单独的界面, 把调用过的工具名带回主对话, 至少能看出它做了什么
            val toolTrace = finalMessage?.getTools()?.map { tool ->
                buildJsonObject {
                    put("tool", tool.toolName)
                    put("executed", tool.isExecuted)
                }
            }
            val payload = buildJsonObject {
                put(
                    "status",
                    when {
                        timedOut -> "timeout"
                        budgetExceeded -> "token_limit_reached"
                        failureMessage != null -> "failed"
                        finishReason == AgentFinishReason.StepLimitReached -> "step_limit_reached"
                        else -> "completed"
                    }
                )
                put(
                    "result",
                    report.ifBlank {
                        when {
                            timedOut -> "The subagent timed out before writing a final report."
                            budgetExceeded ->
                                "The subagent reached its token budget before writing a final report."

                            failureMessage != null ->
                                "The subagent failed before writing a final report: $failureMessage"

                            finishReason == AgentFinishReason.StepLimitReached ->
                                "The subagent reached its step limit before writing a final report."

                            else -> "(the subagent produced no text output)"
                        }
                    }
                )
                failureMessage?.let { put("error", it) }
                put("duration_ms", System.currentTimeMillis() - startedAt)
                if (usedTokens > 0) put("tokens", usedTokens)
                finalMessage?.stepCount?.let { put("steps", it) }
                if (!toolTrace.isNullOrEmpty()) {
                    put("tools", buildJsonArray { toolTrace.forEach { add(it) } })
                }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
}

private const val SUBAGENT_SYSTEM_PROMPT =
    "You are a subagent launched by the main assistant to handle one self-contained task. " +
        "You start with no conversation history, so rely on the instructions you were given and inspect the environment with your tools. " +
        "You cannot ask the user questions, and tools that require user approval are unavailable. " +
        "If a tool returns APPROVAL_REQUIRED, do not call it again; choose another approach. " +
        "Work autonomously, verify what you do, and finish with a concise report of findings and results " +
        "with concrete file paths, values or facts the main assistant can use."
