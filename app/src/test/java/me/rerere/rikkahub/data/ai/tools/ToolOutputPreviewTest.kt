package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolOutputPreviewTest {
    @Test
    fun `short output is unchanged`() {
        assertEquals("hello\nworld", toolOutputPreview(listOf("hello", "world"), 20))
    }

    @Test
    fun `long output keeps head and tail`() {
        val texts = listOf("start", "A".repeat(100), "", "end")
        val joined = texts.joinToString("\n")
        val limit = 21
        val head = limit / 2
        val tail = limit - head

        assertEquals(
            "${joined.take(head)}\n... [${joined.length - limit} characters omitted] ...\n${joined.takeLast(tail)}",
            toolOutputPreview(texts, limit),
        )
    }
}
