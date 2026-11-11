package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ShellOutputCollectorTest {
    @Test
    fun completionSurvivesEverySingleSplitPosition() {
        val output = "stdout without trailing newline"
        val cwd = "/目录 with spaces/子目录\nnext line/🚀 "
        val input = output + completionRecord(23, cwd)

        for (split in 1 until input.length) {
            val collector = ShellOutputCollector(MARKER)

            assertNull("Premature completion at split $split", collector.append(input.substring(0, split)))
            assertEquals(
                "Completion at split $split",
                ShellCompletion(23, cwd),
                collector.append(input.substring(split)),
            )
            assertEquals("Output at split $split", output, collector.text)
            assertFalse("Truncation at split $split", collector.truncated)
        }
    }

    @Test
    fun completionSurvivesEveryFixedChunkSizeIncludingSingleCharacters() {
        val output = "first line\nlast line without newline"
        val cwd = " /目录 with spaces\nand a newline/🚀 "
        val input = output + completionRecord(0, cwd)

        for (chunkSize in 1..input.length) {
            val collector = ShellOutputCollector(MARKER)
            var completion: ShellCompletion? = null
            var offset = 0
            while (offset < input.length) {
                val end = minOf(offset + chunkSize, input.length)
                completion = collector.append(input.substring(offset, end))
                if (end < input.length) {
                    assertNull("Premature completion with chunk size $chunkSize", completion)
                }
                offset = end
            }

            assertEquals("Chunk size $chunkSize", ShellCompletion(0, cwd), completion)
            assertEquals(output, collector.text)
            assertFalse(collector.truncated)
        }
    }

    @Test
    fun markerLookalikesRemainOrdinaryOutput() {
        val output = "before $MARKER 0 /fake\u0000${MARKER}X 0 /fake\u0000${MARKER.dropLast(1)} after"
        val collector = ShellOutputCollector(MARKER)

        for (character in output) {
            assertNull(collector.append(character.toString()))
        }
        assertEquals(ShellCompletion(0, "/real"), collector.append(completionRecord(0, "/real")))
        assertEquals(output, collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun millionCharactersWithoutNewlinesAreBoundedAndFinalMarkerStillParses() {
        val collector = ShellOutputCollector(MARKER)
        val chunk = "x".repeat(4_096)
        var remaining = 1_000_000

        while (remaining > 0) {
            val size = minOf(chunk.length, remaining)
            assertNull(collector.append(if (size == chunk.length) chunk else chunk.take(size)))
            assertTrue("Output exceeded its cap", collector.text.length <= MAX_OUTPUT_CHARS)
            remaining -= size
        }

        assertEquals("x".repeat(MAX_OUTPUT_CHARS), collector.text)
        assertTrue(collector.truncated)
        val record = completionRecord(17, "/结束 with spaces\n下一行")
        for (index in 0 until record.lastIndex) {
            assertNull(collector.append(record[index].toString()))
        }
        assertEquals(
            ShellCompletion(17, "/结束 with spaces\n下一行"),
            collector.append(record.last().toString()),
        )
        assertEquals("x".repeat(MAX_OUTPUT_CHARS), collector.text)
        assertTrue(collector.truncated)
    }

    @Test
    fun exactlyMaxOutputCharactersAreNotTruncated() {
        val collector = ShellOutputCollector(MARKER)
        val chunk = "e".repeat(1_024)
        repeat(MAX_OUTPUT_CHARS / chunk.length) {
            assertNull(collector.append(chunk))
        }
        assertNull(collector.append(chunk.take(MAX_OUTPUT_CHARS % chunk.length)))

        assertEquals(ShellCompletion(0, "/tmp"), collector.append(completionRecord(0, "/tmp")))
        assertEquals("e".repeat(MAX_OUTPUT_CHARS), collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun oneCharacterOverTheOutputLimitIsTruncated() {
        val collector = ShellOutputCollector(MARKER)

        assertNull(collector.append("x".repeat(MAX_OUTPUT_CHARS) + "y"))
        assertEquals(ShellCompletion(0, "/tmp"), collector.append(completionRecord(0, "/tmp")))
        assertEquals("x".repeat(MAX_OUTPUT_CHARS), collector.text)
        assertTrue(collector.truncated)
    }

    @Test
    fun finishFlushesOutputAndIncompleteMarkerPrefixAtEof() {
        val outputs = listOf("", "short", "unterminated output", "tail\u0000${MARKER.dropLast(1)}")
        for (output in outputs) {
            val collector = ShellOutputCollector(MARKER)
            assertNull(collector.append(output))

            collector.finish()
            assertEquals(output, collector.text)
            assertFalse(collector.truncated)
            collector.finish()
            assertEquals("Finishing twice must not duplicate output", output, collector.text)
        }
    }

    @Test
    fun finishAppliesOutputLimitToTheBufferedTail() {
        for (extra in 0..1) {
            val collector = ShellOutputCollector(MARKER)
            assertNull(collector.append("x".repeat(MAX_OUTPUT_CHARS + extra)))
            // The trailing prefix-sized lookbehind has not been emitted yet.
            assertFalse(collector.truncated)

            collector.finish()

            assertEquals("x".repeat(MAX_OUTPUT_CHARS), collector.text)
            assertEquals(extra > 0, collector.truncated)
        }
    }

    @Test
    fun finishDoesNotLeakAnUnterminatedControlRecordIntoOutput() {
        val collector = ShellOutputCollector(MARKER)

        assertNull(collector.append("visible\u0000$MARKER 42 /unfinished directory"))
        collector.finish()

        assertEquals("visible", collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun nonzeroExitCodesArePreserved() {
        for (exitCode in listOf(1, 127, 255, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val collector = ShellOutputCollector(MARKER)

            assertEquals(
                ShellCompletion(exitCode, "/tmp"),
                collector.append("output" + completionRecord(exitCode, "/tmp")),
            )
            assertEquals("output", collector.text)
            assertFalse(collector.truncated)
        }
    }

    @Test
    fun malformedStatusRecordsAreRejectedOnlyWhenTerminated() {
        val invalidRecords = listOf("", "no-status /tmp", "2147483648 /tmp", "-2147483649 /tmp", " /tmp")
        for (record in invalidRecords) {
            val collector = ShellOutputCollector(MARKER)
            assertNull(collector.append("visible\u0000$MARKER $record"))

            expectIOException("Invalid shell completion status") {
                collector.append("\u0000")
            }
            assertEquals("visible", collector.text)
            assertFalse(collector.truncated)
        }
    }

    @Test
    fun missingWorkingDirectorySeparatorIsRejected() {
        val collector = ShellOutputCollector(MARKER)

        expectIOException("Missing shell working directory") {
            collector.append("visible\u0000$MARKER 0\u0000")
        }
        assertEquals("visible", collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun controlRecordExactlyAtTheLimitIsAccepted() {
        val collector = ShellOutputCollector(MARKER)
        val cwd = "d".repeat(MAX_CONTROL_RECORD_CHARS - "0 ".length)

        assertNull(collector.append("visible\u0000$MARKER 0 $cwd"))
        assertEquals(ShellCompletion(0, cwd), collector.append("\u0000"))
        assertEquals("visible", collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun unterminatedControlRecordIsRejectedAsSoonAsItExceedsTheLimit() {
        val collector = ShellOutputCollector(MARKER)
        val chunk = "d".repeat(1_024)
        assertNull(collector.append("visible\u0000$MARKER "))

        // Stream up to the bound, rather than constructing an unbounded record.
        repeat(MAX_CONTROL_RECORD_CHARS / chunk.length) {
            assertNull(collector.append(chunk))
            assertEquals("visible", collector.text)
        }
        expectIOException("Shell control record is too large") {
            collector.append("d")
        }
        assertEquals("visible", collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun oversizedTerminatedControlRecordIsAlsoRejected() {
        val collector = ShellOutputCollector(MARKER)
        val cwd = "d".repeat(MAX_CONTROL_RECORD_CHARS - "0 ".length + 1)

        expectIOException("Shell control record is too large") {
            collector.append("visible" + completionRecord(0, cwd))
        }
        assertEquals("visible", collector.text)
        assertFalse(collector.truncated)
    }

    @Test
    fun outputFollowingCompletionInTheSameChunkIsRetained() {
        val collector = ShellOutputCollector(MARKER)
        assertEquals(ShellCompletion(0, "/tmp"), collector.append("before" + completionRecord(0, "/tmp") + "after"))
        assertEquals("before", collector.text)
        assertEquals("after", collector.remainder)
    }

    private fun completionRecord(exitCode: Int, cwd: String): String =
        "\u0000$MARKER $exitCode $cwd\u0000"

    private fun expectIOException(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected IOException: $message")
        } catch (exception: IOException) {
            assertEquals(message, exception.message)
        }
    }

    private companion object {
        const val MARKER = "__RIKKA_SHELL_test_0123456789__"

        // Protocol bound is private in LocalShellSession.kt.
        const val MAX_CONTROL_RECORD_CHARS = 16 * 1_024
    }
}
