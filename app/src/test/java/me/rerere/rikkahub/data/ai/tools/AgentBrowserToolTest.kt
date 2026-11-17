package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentBrowserToolTest {
    @Test
    fun `normalizes urls and rejects non web schemes`() {
        assertEquals("https://example.com/a", normalizeBrowserUrl("example.com/a"))
        assertEquals("http://example.com", normalizeBrowserUrl(" http://example.com "))

        assertThrows(IllegalArgumentException::class.java) { normalizeBrowserUrl("file:///etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { normalizeBrowserUrl("javascript:alert(1)") }
        assertThrows(IllegalArgumentException::class.java) { normalizeBrowserUrl("   ") }
    }

    @Test
    fun `parses both encoded and plain evaluation results`() {
        val encoded =
            "\"{\\\"url\\\":\\\"https://example.com\\\",\\\"title\\\":\\\"T\\\",\\\"text\\\":\\\"body\\\"}\""
        val page = parseBrowserPage(encoded)
        assertEquals("https://example.com", page.url)
        assertEquals("T", page.title)
        assertEquals("body", page.text)

        val plain = "{\"url\":\"https://example.com\",\"title\":\"T\",\"text\":\"body\"}"
        assertEquals("body", parseBrowserPage(plain).text)
    }

    @Test
    fun `reports a page script error`() {
        assertThrows(IllegalStateException::class.java) {
            parseBrowserPage("\"{\\\"error\\\":\\\"boom\\\"}\"")
        }
    }
}
