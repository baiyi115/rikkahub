package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceManagerListRootfsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lists workspace files with absolute paths`() {
        val manager = WorkspaceManager(temp.newFolder("ws"))
        manager.ensureWorkspace("demo")
        File(manager.filesDir("demo"), "notes.md").writeText("hello")
        File(manager.filesDir("demo"), "sub").mkdirs()

        val entries = manager.listRootfs("demo", "/workspace")

        assertEquals(listOf("sub", "notes.md"), entries.map { it.name })
        assertEquals("/workspace/sub", entries.first().path)
        assertTrue(entries.first().isDirectory)
        assertEquals("/workspace/notes.md", entries.last().path)
        assertEquals(5L, entries.last().sizeBytes)
    }

    @Test
    fun `lists rootfs directories with their own prefix`() {
        val manager = WorkspaceManager(temp.newFolder("ws2"))
        manager.ensureWorkspace("demo")
        File(manager.linuxDir("demo"), "etc").mkdirs()
        File(manager.linuxDir("demo"), "etc/hosts").writeText("127.0.0.1")

        val entries = manager.listRootfs("demo", "/etc")

        assertEquals(listOf("/etc/hosts"), entries.map { it.path })
    }

    @Test
    fun `refuses paths that escape the workspace area`() {
        val manager = WorkspaceManager(temp.newFolder("ws3"))
        manager.ensureWorkspace("demo")

        val error = runCatching { manager.listRootfs("demo", "/workspace/../etc") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
