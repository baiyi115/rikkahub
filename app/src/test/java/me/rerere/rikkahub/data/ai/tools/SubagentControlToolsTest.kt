package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentControlToolsTest {
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
