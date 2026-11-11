package me.rerere.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class BackgroundCommandLogTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun logFile(content: String = "abcdef"): File =
        tempFolder.newFile("command.log").apply { writeText(content) }

    @Test
    fun missingFileHasZeroSizeAndEmptyOutput() {
        val log = BackgroundCommandLog(File(tempFolder.root, "missing.log"))

        assertEquals(0L, log.size())
        assertArrayEquals(byteArrayOf(), log.read(0, 10))
        assertArrayEquals(byteArrayOf(), log.read(100, 10))
    }

    @Test
    fun emptyFileHasZeroSizeAndEmptyOutput() {
        val log = BackgroundCommandLog(logFile(""))

        assertEquals(0L, log.size())
        assertArrayEquals(byteArrayOf(), log.read(0, 10))
    }

    @Test
    fun readsAllBytesFromStart() {
        val log = BackgroundCommandLog(logFile())

        assertEquals(6L, log.size())
        assertArrayEquals("abcdef".toByteArray(), log.read(0, 6))
    }

    @Test
    fun readsAtByteOffset() {
        val file = tempFolder.newFile("binary.log").apply {
            writeBytes(byteArrayOf(0, 127, -128, -1, 42))
        }
        val log = BackgroundCommandLog(file)

        assertArrayEquals(byteArrayOf(-128, -1), log.read(2, 2))
    }

    @Test
    fun limitsReadToRequestedCount() {
        val log = BackgroundCommandLog(logFile())

        assertArrayEquals("abc".toByteArray(), log.read(0, 3))
        assertArrayEquals("cd".toByteArray(), log.read(2, 2))
    }

    @Test
    fun oversizedReadReturnsOnlyRemainingBytes() {
        val log = BackgroundCommandLog(logFile())

        assertArrayEquals("ef".toByteArray(), log.read(4, Int.MAX_VALUE))
        assertArrayEquals("f".toByteArray(), log.read(5, 10))
    }

    @Test
    fun offsetAtOrBeyondEofReturnsEmptyOutput() {
        val log = BackgroundCommandLog(logFile())

        assertArrayEquals(byteArrayOf(), log.read(6, 10))
        assertArrayEquals(byteArrayOf(), log.read(7, 10))
        assertArrayEquals(byteArrayOf(), log.read(Long.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun zeroCountReturnsEmptyOutput() {
        val log = BackgroundCommandLog(logFile())

        assertArrayEquals(byteArrayOf(), log.read(0, 0))
        assertArrayEquals(byteArrayOf(), log.read(2, 0))
        assertArrayEquals(byteArrayOf(), log.read(Long.MAX_VALUE, 0))
    }

    @Test
    fun readsReflectAppendedBytes() {
        val file = logFile()
        val log = BackgroundCommandLog(file)
        assertArrayEquals("abcdef".toByteArray(), log.read(0, 10))

        file.appendText("gh")

        assertEquals(8L, log.size())
        assertArrayEquals("gh".toByteArray(), log.read(6, 10))
    }

    @Test
    fun readsReflectTruncatedFileWithoutPaddingOrEofException() {
        val file = logFile()
        val log = BackgroundCommandLog(file)
        assertEquals(6L, log.size())

        RandomAccessFile(file, "rw").use { it.setLength(2) }

        assertEquals(2L, log.size())
        assertArrayEquals("ab".toByteArray(), log.read(0, 6))
        assertArrayEquals(byteArrayOf(), log.read(4, 6))

        RandomAccessFile(file, "rw").use { it.setLength(0) }

        assertEquals(0L, log.size())
        assertArrayEquals(byteArrayOf(), log.read(0, 6))
    }

    @Test
    fun deletedFileReturnsEmptyOutput() {
        val file = logFile()
        val log = BackgroundCommandLog(file)
        assertEquals(6L, log.size())
        check(file.delete())

        assertEquals(0L, log.size())
        assertArrayEquals(byteArrayOf(), log.read(0, 6))
    }

    @Test
    fun negativeOffsetIsRejectedEvenForZeroCount() {
        val log = BackgroundCommandLog(logFile())

        assertThrows(IllegalArgumentException::class.java) { log.read(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { log.read(Long.MIN_VALUE, 0) }
    }

    @Test
    fun negativeCountIsRejectedEvenAtEof() {
        val log = BackgroundCommandLog(logFile())

        assertThrows(IllegalArgumentException::class.java) { log.read(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { log.read(6, Int.MIN_VALUE) }
    }

    @Test
    fun invalidArgumentsAreRejectedForMissingFile() {
        val log = BackgroundCommandLog(File(tempFolder.root, "missing.log"))

        assertThrows(IllegalArgumentException::class.java) { log.read(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { log.read(0, -1) }
    }
}
