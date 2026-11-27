package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

internal const val SUBAGENT_SEND_TOOL_NAME = "subagent_send"
internal const val SUBAGENT_WAIT_TOOL_NAME = "subagent_wait"
internal const val SUBAGENT_LIST_TOOL_NAME = "subagent_list"
internal const val SUBAGENT_CLOSE_TOOL_NAME = "subagent_close"

private const val DEFAULT_WAIT_SECONDS = 600L
private const val MAX_WAIT_SECONDS = 1_800L

/**
 * 后台子代理的控制工具。
 *
 * 与 `subagent` 的 background 模式配套: spawn 之后主代理可以继续工作, 用 send 改方向,
 * 用 wait 收报告, 用 list 看还有谁在跑, 用 close 及时释放名额。
 */
internal fun createSubagentControlTools(
    manager: SubagentManager,
    conversationId: Uuid,
): List<Tool> = listOf(
    createSubagentSendTool(manager),
    createSubagentWaitTool(manager),
    createSubagentListTool(manager, conversationId),
    createSubagentCloseTool(manager),
)

private fun createSubagentSendTool(manager: SubagentManager) = Tool(
    name = SUBAGENT_SEND_TOOL_NAME,
    description = """
        Send a message to a subagent you started with background=true.
        While it is running the message is delivered before its next model step, so use it to correct course,
        narrow the scope, or add information you learned meanwhile. If it has already finished, the message starts
        a new turn on the same context, so it keeps everything it read before instead of starting over.
        Finish by collecting the result with subagent_wait.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("agent_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The agent_id returned when the subagent was started.")
                })
                put("message", buildJsonObject {
                    put("type", "string")
                    put("description", "The instruction or information for the subagent.")
                })
            },
            required = listOf("agent_id", "message"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val agentId = params.string("agent_id")?.trim().orEmpty()
        val message = params.string("message")?.trim().orEmpty()
        require(agentId.isNotEmpty()) { "agent_id is required" }
        require(message.isNotEmpty()) { "message is required" }
        val payload = when (manager.send(agentId, message)) {
            SendOutcome.NotFound -> buildJsonObject {
                put("ok", false)
                put("error", "No subagent with id \"$agentId\". Use subagent_list to see the live agents.")
            }

            SendOutcome.Closed -> buildJsonObject {
                put("ok", false)
                put("error", "Subagent \"$agentId\" is closed and cannot receive messages.")
            }

            SendOutcome.Delivered -> buildJsonObject {
                put("ok", true)
                put("status", "delivered")
                put("note", "The subagent sees this before its next step.")
            }

            SendOutcome.Started -> buildJsonObject {
                put("ok", true)
                put("status", "started")
                put("note", "The subagent had finished; it continues with this message on its existing context.")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createSubagentWaitTool(manager: SubagentManager) = Tool(
    name = SUBAGENT_WAIT_TOOL_NAME,
    description = """
        Wait for a background subagent to finish its current turn, then return its status and report.
        Prefer one long wait over repeated polling. If the wait times out the subagent keeps running and you can
        wait again or send it a message.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("agent_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The agent_id returned when the subagent was started.")
                })
                put("timeout_seconds", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "How long to wait. Defaults to $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS."
                    )
                })
            },
            required = listOf("agent_id"),
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val agentId = params.string("agent_id")?.trim().orEmpty()
        require(agentId.isNotEmpty()) { "agent_id is required" }
        val timeoutSeconds = (params.string("timeout_seconds")?.toLongOrNull() ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(1L, MAX_WAIT_SECONDS)
        val payload = when (val outcome = manager.waitFor(agentId, timeoutSeconds * 1_000L)) {
            WaitOutcome.NotFound -> error(
                "No subagent with id \"$agentId\". Use subagent_list to see the live agents."
            )

            is WaitOutcome.Done -> buildJsonObject {
                put("agent_id", agentId)
                put("status", outcome.status)
                put("outcome", outcome.outcome)
                put("timed_out", outcome.timedOut)
                put(
                    "result",
                    outcome.report.ifBlank {
                        if (outcome.status == SubagentStatus.RUNNING.wire) {
                            "The subagent is still running and has not produced a report yet."
                        } else {
                            "(the subagent produced no text output)"
                        }
                    }
                )
                if (outcome.steps > 0) put("steps", outcome.steps)
                if (outcome.tokens > 0) put("tokens", outcome.tokens)
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createSubagentListTool(manager: SubagentManager, conversationId: Uuid) = Tool(
    name = SUBAGENT_LIST_TOOL_NAME,
    description = """
        List the subagents started in this conversation with their status and last outcome.
        Closed subagents are not listed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(properties = buildJsonObject {})
    },
    execute = {
        val agents = manager.list(conversationId).filter { it.status != SubagentStatus.CLOSED }
        val payload = buildJsonObject {
            put("count", agents.size)
            put(
                "agents",
                buildJsonArray {
                    agents.forEach { session ->
                        add(
                            buildJsonObject {
                                put("agent_id", session.id)
                                put("description", session.description)
                                put("status", session.status.wire)
                                put("outcome", session.outcome)
                                if (session.steps > 0) put("steps", session.steps)
                            }
                        )
                    }
                }
            )
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private fun createSubagentCloseTool(manager: SubagentManager) = Tool(
    name = SUBAGENT_CLOSE_TOOL_NAME,
    description = """
        Stop a subagent when its result is no longer needed and free its slot. A closed subagent cannot be
        messaged or waited on again.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("agent_id", buildJsonObject {
                    put("type", "string")
                    put("description", "The agent_id returned when the subagent was started.")
                })
            },
            required = listOf("agent_id"),
        )
    },
    execute = { args ->
        val agentId = args.jsonObject.string("agent_id")?.trim().orEmpty()
        require(agentId.isNotEmpty()) { "agent_id is required" }
        val previous = manager.close(agentId)
            ?: error("No subagent with id \"$agentId\". Use subagent_list to see the live agents.")
        val payload = buildJsonObject {
            put("ok", true)
            put("agent_id", agentId)
            put("previous_status", previous)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
