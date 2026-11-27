package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentControlToolsTest {
    @Test
    fun `running agent receives scoped steering as a user message and resets stats on resume`() = runBlocking {
        val manager = SubagentManager()
        val owner = Uuid.random()
        val other = Uuid.random()
        val reading = CompletableDeferred<Unit>()
        val continueRead = CompletableDeferred<Unit>()
        val provider = object : Provider<ProviderSetting> {
            var requests = 0

            override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

            override suspend fun streamText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): Nothing = error("Unexpected streaming request")

            override suspend fun generateText(
                providerSetting: ProviderSetting,
                messages: List<UIMessage>,
                params: TextGenerationParams,
            ): TextGenerationResult {
                requests++
                val response = when (requests) {
                    1 -> UIMessage.assistant("").copy(parts = listOf(UIMessagePart.Tool("call", "read", "{}")))
                    else -> {
                        val task = messages.last()
                        check(task.role == MessageRole.USER)
                        UIMessage.assistant(task.toText())
                    }
                }
                return TextGenerationResult(
                    id = "response-$requests",
                    model = params.model.modelId,
                    message = response,
                    usage = TokenUsage(totalTokens = if (requests <= 2) 50 else 10),
                )
            }
        }
        val session = SubagentSession(
            id = "agent",
            conversationId = owner,
            description = "Inspect",
            systemPrompt = "Report findings",
            tools = listOf(
                Tool(name = "read", description = "", execute = {
                    reading.complete(Unit)
                    continueRead.await()
                    listOf(UIMessagePart.Text("read result"))
                }),
            ),
            params = TextGenerationParams(Model(modelId = "test")),
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            retry = RetryPolicy.NONE,
            maxSteps = 5,
            timeoutSeconds = 10,
            maxTotalTokens = 200,
        )
        try {
            manager.launch(session, "original task")
            withTimeout(5_000) { reading.await() }
            assertEquals(SendOutcome.NotFound, manager.send(other, session.id, "wrong direction"))
            assertEquals(null, manager.waitFor(other, session.id, 1))
            assertEquals(null, manager.close(other, session.id))
            assertEquals(SendOutcome.Queued, manager.send(owner, session.id, "focus on the tests"))
            continueRead.complete(Unit)
            val first = checkNotNull(manager.waitFor(owner, session.id, 5_000)).agent
            assertEquals("focus on the tests", first.report)
            assertEquals(100, first.tokens)
            assertEquals(SendOutcome.Started, manager.send(owner, session.id, "check the docs"))
            val resumed = checkNotNull(manager.waitFor(owner, session.id, 5_000)).agent
            assertEquals("check the docs", resumed.report)
            assertEquals(10, resumed.tokens)
        } finally {
            manager.closeConversation(owner)
        }
    }

    @Test
    fun `control tools reject an unknown agent`() = runBlocking {
        val tools = createSubagentControlTools(SubagentManager(), Uuid.random()).associateBy { it.name }

        val payload = tools.getValue(SUBAGENT_SEND_TOOL_NAME)
            .execute(
                buildJsonObject {
                    put("agent_id", "missing")
                    put("message", "focus on the tests")
                }
            )
            .single()
            .let { part -> JsonInstant.parseToJsonElement((part as UIMessagePart.Text).text).jsonObject }

        assertEquals("false", payload["ok"]?.jsonPrimitive?.content)
        assertTrue(
            runCatching {
                tools.getValue(SUBAGENT_WAIT_TOOL_NAME)
                    .execute(buildJsonObject { put("agent_id", "missing") })
            }.exceptionOrNull() is IllegalStateException
        )
        assertTrue(
            runCatching {
                tools.getValue(SUBAGENT_CLOSE_TOOL_NAME)
                    .execute(buildJsonObject { put("agent_id", "missing") })
            }.exceptionOrNull() is IllegalStateException
        )
    }
}
