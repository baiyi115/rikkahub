package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceCwdTest {
    private val layout = WorkspaceLayout(workspaceDir = "/workspace", writableRoots = emptyList())

    @Test
    fun `accepts workspace relative and absolute paths`() {
        assertEquals("src", workspaceCwd(layout, "src"))
        assertEquals("src", workspaceCwd(layout, "/workspace/src"))
        assertEquals("", workspaceCwd(layout, "/workspace"))
    }

    @Test
    fun `rejects paths outside the workspace`() {
        assertThrows(IllegalArgumentException::class.java) {
            workspaceCwd(layout, "/etc")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspaceCwd(layout, "/workspacefoo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            workspaceCwd(layout, "../outside")
        }
    }
}
