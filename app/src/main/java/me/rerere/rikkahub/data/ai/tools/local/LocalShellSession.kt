package me.rerere.rikkahub.data.ai.tools.local

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.uuid.Uuid

private const val OUTPUT_CHUNK_CHARS = 4_096
private const val MAX_BUFFERED_CHUNKS = 16
private const val MAX_CONTROL_CHARS = 16 * 1024

class LocalShellSession internal constructor(
    val id: String,
    val conversationId: Uuid,
    val identity: String,
    private val process: Process,
    private val pidFile: File,
    private val runner: LocalShellRunner,
    initialCwd: String,
) : AutoCloseable {
    private val commandLock = ReentrantLock()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val chunks = ShellOutputQueue(MAX_BUFFERED_CHUNKS)
    private val closing = AtomicBoolean()
    private var pendingOutput = ""
    private val reader = thread(name = "local-shell-session-$id", isDaemon = true) {
        try {
            process.inputStream.reader(StandardCharsets.UTF_8).use { input ->
                val buffer = CharArray(OUTPUT_CHUNK_CHARS)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    chunks.offer(String(buffer, 0, count))
                }
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            chunks.close()
        }
    }

    @Volatile
    var cwd: String = initialCwd
        private set

    val isAlive: Boolean
        get() = !closing.get() && process.isAlive

    internal fun writeRaw(script: String) {
        writer.write(script)
        writer.flush()
    }

    // Descriptor 3 retains the protocol channel even when a command redirects stdout/stderr with exec.
    fun exec(command: String, workingDir: String? = null, timeoutMillis: Long = 30_000L): LocalShellResult {
        commandLock.lockInterruptibly()
        try {
            if (!isAlive) return LocalShellResult(-1, "", "Shell session is closed", false, false, true)
            val marker = "__RIKKA_SHELL_${UUID.randomUUID().toString().replace("-", "")}__"
            val output = ShellOutputCollector(marker)
            val script = buildString {
                if (!workingDir.isNullOrBlank()) {
                    append("if cd ").append(workingDir.shellQuote()).append("; then\n")
                }
                append("{\n").append(command).append("\n} < /dev/null\n")
                if (!workingDir.isNullOrBlank()) append("else\nfalse\nfi\n")
                append("command printf '\\000%s %s %s\\000' '").append(marker)
                    .append("' \"\$?\" \"\$PWD\" >&3\n")
            }
            try {
                writeRaw(script)
                val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) {
                        close()
                        output.finish()
                        return LocalShellResult(
                            -1,
                            output.text,
                            "Command timed out; the shell session was closed",
                            true,
                            output.truncated,
                            true,
                        )
                    }
                    val chunk = if (pendingOutput.isNotEmpty()) {
                        pendingOutput.also { pendingOutput = "" }
                    } else {
                        chunks.poll((remaining / 1_000_000L).coerceAtLeast(1))
                    }
                    if (chunk == null) {
                        if (!chunks.isClosed) continue
                        output.finish()
                        val exitCode = if (process.waitFor(100, TimeUnit.MILLISECONDS)) process.exitValue() else -1
                        close()
                        return LocalShellResult(
                            exitCode,
                            output.text,
                            "Shell session exited while running the command",
                            false,
                            output.truncated,
                            true,
                        )
                    }
                    val completion = output.append(chunk) ?: continue
                    pendingOutput = output.remainder
                    cwd = completion.cwd
                    return LocalShellResult(completion.exitCode, output.text, "", false, output.truncated)
                }
            } catch (e: InterruptedException) {
                close()
                throw e
            } catch (e: IOException) {
                close()
                output.finish()
                return LocalShellResult(-1, output.text, e.message.orEmpty(), false, output.truncated, true)
            }
        } finally {
            commandLock.unlock()
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        chunks.close()
        // Kill before closing the writer: another thread may be blocked writing to the process pipe.
        runner.terminateProcess(process, pidFile, identity == "root")
        runCatching { writer.close() }
        runCatching { process.inputStream.close() }
        reader.interrupt()
        runCatching { reader.join(500) }
    }
}

internal data class ShellCompletion(val exitCode: Int, val cwd: String)

/** Incremental framing; neither an unterminated output line nor a control record can grow without bounds. */
internal class ShellOutputCollector(marker: String, private val maxChars: Int = MAX_OUTPUT_CHARS) {
    private val prefix = "\u0000$marker "
    private val pending = StringBuilder()
    private val output = StringBuilder()
    private var readingCompletion = false
    var remainder: String = ""
        private set
    var truncated = false
        private set
    val text: String get() = output.toString()

    fun append(chunk: String): ShellCompletion? {
        pending.append(chunk)
        if (!readingCompletion) {
            val start = pending.indexOf(prefix)
            if (start < 0) {
                emit((pending.length - prefix.length + 1).coerceAtLeast(0))
                return null
            }
            emit(start)
            pending.delete(0, prefix.length)
            readingCompletion = true
        }
        val end = pending.indexOf("\u0000")
        if (end < 0) {
            if (pending.length > MAX_CONTROL_CHARS) throw IOException("Shell control record is too large")
            return null
        }
        if (end > MAX_CONTROL_CHARS) throw IOException("Shell control record is too large")
        val record = pending.substring(0, end)
        val split = record.indexOf(' ')
        val exitCode = record.substringBefore(' ').toIntOrNull()
            ?: throw IOException("Invalid shell completion status")
        if (split < 0) throw IOException("Missing shell working directory")
        remainder = pending.substring(end + 1)
        return ShellCompletion(exitCode, record.substring(split + 1))
    }

    fun finish() {
        if (!readingCompletion) emit(pending.length)
    }

    private fun emit(count: Int) {
        val remaining = (maxChars - output.length).coerceAtLeast(0)
        output.append(pending, 0, minOf(count, remaining))
        if (count > remaining) truncated = true
        pending.delete(0, count)
    }
}

private class ShellOutputQueue(private val capacity: Int) {
    private val queue = ArrayDeque<String>()
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var isClosed = false
        private set

    fun offer(chunk: String) = lock.withLock {
        while (!isClosed && queue.size >= capacity) changed.await()
        if (!isClosed) {
            queue.addLast(chunk)
            changed.signalAll()
        }
    }

    fun close() = lock.withLock {
        isClosed = true
        changed.signalAll()
    }

    fun poll(timeoutMillis: Long): String? = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (queue.isEmpty() && !isClosed && remaining > 0) remaining = changed.awaitNanos(remaining)
        queue.removeFirstOrNull().also { changed.signalAll() }
    }
}
