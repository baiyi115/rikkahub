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
    createSubagentSendTool(manager, conversationId),
    createSubagentWaitTool(manager, conversationId),
    createSubagentListTool(manager, conversationId),
    createSubagentCloseTool(manager, conversationId),
)

private fun createSubagentSendTool(manager: SubagentManager, conversationId: Uuid) = Tool(
    name = SUBAGENT_SEND_TOOL_NAME,
    description = """
        Send a message to a subagent you started with background=true.
        While it is running the message is queued for its next model step. If it finishes before that step,
        the queued message starts a follow-up turn. If it has already finished, the message starts
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
        require(message.length <= 16_000) { "message must be at most 16000 characters" }
        val payload = when (manager.send(conversationId, agentId, message)) {
            SendOutcome.NotFound -> buildJsonObject {
                put("ok", false)
                put("error", "No subagent with id \"$agentId\". Use subagent_list to see the live agents.")
            }

            SendOutcome.MailboxFull -> buildJsonObject {
                put("ok", false)
                put("error", "The subagent's message queue is full.")
            }

            SendOutcome.Queued -> buildJsonObject {
                put("ok", true)
                put("status", "queued")
                put("note", "Queued for the next model step or a follow-up turn if it finishes first.")
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

private fun createSubagentWaitTool(manager: SubagentManager, conversationId: Uuid) = Tool(
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
        val outcome = manager.waitFor(conversationId, agentId, timeoutSeconds * 1_000L)
            ?: error("No subagent with id \"$agentId\". Use subagent_list to see the live agents.")
        val agent = outcome.agent
        val payload = buildJsonObject {
            put("agent_id", agentId)
            put("status", agent.status.wire)
            put("outcome", agent.outcome)
            put("timed_out", outcome.timedOut)
            put("result", agent.report.ifBlank {
                if (agent.status == SubagentStatus.RUNNING) "The subagent is still running."
                else "(the subagent produced no text output)"
            })
            if (agent.steps > 0) put("steps", agent.steps)
            if (agent.tokens > 0) put("tokens", agent.tokens)
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
        val agents = manager.list(conversationId)
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

private fun createSubagentCloseTool(manager: SubagentManager, conversationId: Uuid) = Tool(
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
        val previous = manager.close(conversationId, agentId)
            ?: error("No subagent with id \"$agentId\". Use subagent_list to see the live agents.")
        val payload = buildJsonObject {
            put("ok", true)
            put("agent_id", agentId)
            put("previous_status", previous)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
