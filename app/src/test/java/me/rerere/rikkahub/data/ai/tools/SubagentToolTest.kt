package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolTest {
    @Test
    fun `subagent tool list drops recursion and interactive tools`() {
        val names = subagentTools(
            listOf(tool("subagent"), tool("ask_user"), tool("phone_use"), tool("workspace_shell"))
        ).map { it.name }

        assertEquals(listOf("workspace_shell"), names)
    }

    @Test
    fun `tools that need approval return an error instead of running`() = runBlocking {
        var executed = false
        val approvalTool = Tool(
            name = "workspace_shell",
            description = "",
            needsApproval = { true },
            execute = {
                executed = true
                listOf(UIMessagePart.Text("ran"))
            },
        )

        val wrapped = subagentTools(listOf(approvalTool)).single()
        assertFalse(wrapped.needsApproval(JsonPrimitive("")))

        val output = wrapped.execute(buildJsonObject { })
        val text = (output.single() as UIMessagePart.Text).text
        assertTrue(text.contains("APPROVAL_REQUIRED"))
        assertFalse(executed)
    }

    @Test
    fun `tools that do not need approval run normally`() = runBlocking {
        var executed = false
        val readOnly = Tool(
            name = "workspace_read_file",
            description = "",
            needsApproval = { false },
            execute = {
                executed = true
                listOf(UIMessagePart.Text("ok"))
            },
        )

        val output = subagentTools(listOf(readOnly)).single().execute(buildJsonObject { })

        assertTrue(executed)
        assertEquals("ok", (output.single() as UIMessagePart.Text).text)
    }

    private fun tool(name: String) = Tool(
        name = name,
        description = "",
        execute = { emptyList() },
    )
}
