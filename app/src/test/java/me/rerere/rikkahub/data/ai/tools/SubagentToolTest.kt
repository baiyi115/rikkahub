package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentToolTest {
    @Test
    fun `subagent tool list drops recursion interactive and shell tools`() {
        val names = subagentTools(
            listOf(
                tool("subagent"),
                tool("ask_user"),
                tool("phone_use"),
                tool("workspace_shell"),
                tool("workspace_shell_output"),
                tool("workspace_shell_kill"),
                tool("local_shell"),
                tool("local_shell_job"),
                tool("browser_open"),
                tool("browser_read"),
                tool("workspace_read_file"),
            )
        ).map { it.name }

        assertEquals(listOf("workspace_read_file"), names)
    }

    @Test
    fun `side effects are opt in`() {
        val tools = listOf(
            tool("workspace_read_file"),
            tool("workspace_write_file"),
            tool("memory_write"),
            tool("mcp__server__tool"),
        )

        assertEquals(
            listOf("workspace_read_file"),
            subagentTools(tools).map { it.name },
        )
        assertEquals(
            listOf("workspace_read_file", "workspace_write_file", "memory_write", "mcp__server__tool"),
            subagentTools(tools, allowSideEffects = true).map { it.name },
        )
    }

    @Test
    fun `side effects require approval`() {
        assertFalse(subagentNeedsApproval(buildJsonObject { }))
        assertFalse(subagentNeedsApproval(buildJsonObject { put("allow_side_effects", false) }))
        assertTrue(subagentNeedsApproval(buildJsonObject { put("allow_side_effects", true) }))
    }

    @Test
    fun `only read-only subagents are concurrency safe`() {
        assertTrue(subagentIsConcurrencySafe(buildJsonObject { }))
        assertTrue(subagentIsConcurrencySafe(buildJsonObject { put("allow_side_effects", false) }))
        assertFalse(subagentIsConcurrencySafe(buildJsonObject { put("allow_side_effects", true) }))
    }

    @Test
    fun `tools that need approval return an error instead of running`() = runBlocking {
        var executed = false
        val approvalTool = Tool(
            name = "custom_tool",
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

    @Test
    fun `report prefers the final assistant text`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("final report"),
                executedTool("tool output"),
            ),
        )

        assertEquals("final report", message.subagentReport())
    }

    @Test
    fun `report prefers text after the last tool call`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me check the file."),
                executedTool("tool output"),
                UIMessagePart.Text("final report"),
            ),
        )

        assertEquals("final report", message.subagentReport())
    }

    @Test
    fun `report falls back to the last executed tool output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(executedTool("tool output")),
        )

        assertEquals("tool output", message.subagentReport())
    }

    private fun executedTool(output: String) = UIMessagePart.Tool(
        toolCallId = "call",
        toolName = "workspace_read_file",
        input = "{}",
        output = listOf(UIMessagePart.Text(output)),
    )

    private fun tool(name: String) = Tool(
        name = name,
        description = "",
        execute = { emptyList() },
    )
}
