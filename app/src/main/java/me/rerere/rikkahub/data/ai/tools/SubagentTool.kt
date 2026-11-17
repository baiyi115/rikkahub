package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
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
private const val SUBAGENT_MAX_TASK_CHARS = 16_000

/** 需要用户介入或会接管界面的工具不能交给子代理: 嵌套运行没有审批卡片, 也不该弹出问答 */
private val SUBAGENT_EXCLUDED_TOOLS = setOf(SUBAGENT_TOOL_NAME, "ask_user", "phone_use")

/**
 * 子代理可用的工具: 去掉递归调用和交互式工具, 其余工具在子代理内一律不触发审批。
 * 需要审批的调用改为返回错误结果, 模型能据此换一种做法。
 */
internal fun subagentTools(tools: List<Tool>): List<Tool> =
    tools
        .filterNot { it.name in SUBAGENT_EXCLUDED_TOOLS }
        .map { it.withoutUserApproval() }

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
 * 把一段独立任务交给隔离上下文的子代理执行, 只把最终报告带回主对话。
 *
 * 子代理复用当前会话的模型与参数, 工具集是主对话工具去掉子代理和交互式工具后的一份;
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
    val nestedTools = subagentTools(tools)
    return Tool(
        name = SUBAGENT_TOOL_NAME,
        description = """
            Run a focused subagent with a fresh, isolated context to handle one subtask and return its report.
            Use it for self-contained work such as researching several sources, summarizing a long document,
            or exploring the workspace while you continue on something else. Put every known path, constraint
            and the expected output format in the task; the subagent starts with no conversation history.
            The subagent cannot ask the user questions, and tools that need user approval are unavailable to it.
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
                },
                required = listOf("task"),
            )
        },
        needsApproval = { false },
        execute = { args ->
            val task = args.jsonObject["task"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("task is required")
            require(task.length <= SUBAGENT_MAX_TASK_CHARS) {
                "task is too long (${task.length} characters, max $SUBAGENT_MAX_TASK_CHARS)"
            }
            val providerSetting = model.findProvider(settings.providers) ?: error("Provider not found")
            val provider = usageRecorder.wrap(
                providerManager.getProviderByType(providerSetting),
                UsageTag(UsageSource.SUBAGENT, assistant.id, conversationId),
            )
            val systemPrompt = buildString {
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
                maxSteps = SUBAGENT_MAX_STEPS,
                retry = settings.retryPolicy(),
            )
            var finalMessage: UIMessage? = null
            var finishReason: AgentFinishReason? = null
            runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                when (event) {
                    is AgentEvent.MessageUpdated -> finalMessage = event.message
                    is AgentEvent.Finished -> {
                        finalMessage = event.message
                        finishReason = event.reason
                    }

                    is AgentEvent.Retrying -> Unit
                }
            }
            val report = finalMessage?.toText()?.trim().orEmpty()
            val payload = buildJsonObject {
                put(
                    "status",
                    when (finishReason) {
                        is AgentFinishReason.AwaitingApproval -> "approval_required"
                        AgentFinishReason.StepLimitReached -> "step_limit_reached"
                        AgentFinishReason.Paused -> "paused"
                        else -> "completed"
                    }
                )
                put("result", report.ifBlank { "(the subagent produced no text output)" })
                finalMessage?.stepCount?.let { put("steps", it) }
            }
            listOf(UIMessagePart.Text(payload.toString()))
        },
    )
}

private const val SUBAGENT_SYSTEM_PROMPT =
    "You are a subagent launched by the main assistant to handle one self-contained task. " +
        "You start with no conversation history, so rely on the instructions you were given and inspect the environment with your tools. " +
        "You cannot ask the user questions, and tools that require user approval are unavailable. " +
        "Work autonomously, verify what you write, and finish with a concise report of findings and results " +
        "with concrete file paths, values or facts the main assistant can use."
