package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSearchToolsTest {
    @Test
    fun `find output splits nul records and drops a partial tail`() {
        val stdout = "/workspace/a.txt\u0000/workspace/b c.txt\u0000/workspace/part"
        assertEquals(
            listOf("/workspace/a.txt", "/workspace/b c.txt"),
            parseFindOutput(stdout, truncated = true),
        )
        assertEquals(
            listOf("/workspace/a.txt", "/workspace/b c.txt", "/workspace/part"),
            parseFindOutput(stdout, truncated = false),
        )
    }

    @Test
    fun `grep output keeps path line and text`() {
        val matches = parseGrepOutput(
            "/workspace/a.kt:12:val x = 1\n/workspace/b.kt:7:y = 2\n",
            truncated = false,
        )
        assertEquals(2, matches.size)
        assertEquals("/workspace/a.kt", matches[0].path)
        assertEquals(12, matches[0].line)
        assertEquals("val x = 1", matches[0].text)
    }

    @Test
    fun `glob matches relative paths and bare name patterns`() {
        val paths = listOf(
            "/workspace/src/a.kt",
            "/workspace/src/deep/b.kt",
            "/workspace/readme.md",
        )
        val (byName, _) = matchGlobPaths("/workspace", "*.kt", paths, 10)
        assertEquals(listOf("/workspace/src/a.kt", "/workspace/src/deep/b.kt"), byName)

        val (scoped, _) = matchGlobPaths("/workspace", "src/**", paths, 10)
        assertEquals(listOf("/workspace/src/a.kt", "/workspace/src/deep/b.kt"), scoped)

        val (limited, truncated) = matchGlobPaths("/workspace", "*.kt", paths, 1)
        assertEquals(listOf("/workspace/src/a.kt"), limited)
        assertTrue(truncated)
    }

    @Test
    fun `double star pattern also matches files at the root`() {
        val paths = listOf("/workspace/a.kt", "/workspace/src/b.kt")

        val (matched, _) = matchGlobPaths("/workspace", "**/*.kt", paths, 10)

        assertEquals(paths, matched)
    }

    @Test
    fun `write verification fails when read back content differs`() {
        requireWriteVerified("/workspace/a.txt", "hello", "hello")

        val error = assertThrows(IllegalArgumentException::class.java) {
            requireWriteVerified("/workspace/a.txt", "hello", "hell")
        }
        assertTrue(error.message.orEmpty().contains("/workspace/a.txt"))
    }

    @Test
    fun `shell quote survives single quotes`() {
        assertEquals("'it'\\''s'", "it's".shellQuote())
    }
}
