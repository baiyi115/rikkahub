package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.putBackgroundShellTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class LocalShellToolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun approvalIsEnabledByDefaultAndOnlyRequiredForExecutingActions() {
        val tool = buildLocalShellTool(runner(), Uuid.random(), defaultRoot = false)
        for (action in listOf("exec", "open", "check_root")) assertTrue(tool.needsApproval(input(action)))
        for (action in listOf("list", "close")) assertFalse(tool.needsApproval(input(action)))
        assertTrue(tool.needsApproval(buildJsonObject {}))
    }

    @Test
    fun disablingApprovalRemovesPromptsForAllActions() {
        val tool = buildLocalShellTool(runner(), Uuid.random(), defaultRoot = false, needsApproval = false)
        for (action in listOf("exec", "open", "check_root", "list", "close")) {
            assertFalse(tool.needsApproval(input(action)))
        }
    }

    @Test
    fun listAndCloseDoNotNeedAWorkingShellOrRootAuthorization() = runBlocking {
        val tool = buildLocalShellTool(runner(), Uuid.random(), defaultRoot = false)
        val list = (tool.execute(input("list")).single() as UIMessagePart.Text).text
        assertTrue(list.contains("\"sessions\":[]"))
        val close = tool.execute(buildJsonObject {
            put("action", "close")
            put("session_id", "missing")
        })
        assertTrue((close.single() as UIMessagePart.Text).text.contains("\"closed\":false"))
    }

    @Test
    fun sharedTaskEncodingPreservesCanonicalFields() {
        val task = me.rerere.rikkahub.service.BackgroundShellTask(
            "id", Uuid.random(), "local_shell", "echo test", "/log", 1,
            me.rerere.rikkahub.service.BackgroundShellStatus.EXITED, 7, 2,
        )
        val result = buildJsonObject { putBackgroundShellTask(task) }
        assertEquals("id", result.getValue("taskId").jsonPrimitive.content)
        assertEquals("exited", result.getValue("status").jsonPrimitive.content)
        assertEquals("/log", result.getValue("logPath").jsonPrimitive.content)
        assertEquals(7, result.getValue("exitCode").jsonPrimitive.int)
    }

    private fun runner() = LocalShellRunner(temporary.newFolder(), temporary.newFolder())
    private fun input(action: String) = buildJsonObject { put("action", action) }
}
