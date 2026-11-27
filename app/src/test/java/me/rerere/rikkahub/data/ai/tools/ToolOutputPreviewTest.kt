package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputPreviewTest {
    @Test
    fun `short output is unchanged`() {
        assertEquals("hello", toolOutputPreview("hello", 10))
    }

    @Test
    fun `long output keeps head and tail`() {
        val text = "A".repeat(100) + "B".repeat(100)

        val preview = toolOutputPreview(text, 40)

        assertTrue(preview.startsWith("A".repeat(20)))
        assertTrue(preview.endsWith("B".repeat(20)))
        assertTrue(preview.contains("characters omitted"))
    }
}
