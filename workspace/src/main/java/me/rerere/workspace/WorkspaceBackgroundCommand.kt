package me.rerere.workspace

import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/**
 * 在后台运行的命令, stdout 与 stderr 合并写入日志文件。
 *
 * 方法都是阻塞 IO, 调用方负责切到 IO 线程。
 */
interface WorkspaceBackgroundCommand {
    /** 日志文件在执行环境内的绝对路径, 模型可以用 shell 直接读取 */
    val logPath: String

    /** 阻塞到命令退出, 返回退出码 */
    fun waitFor(): Int

    /** 日志当前的字节数 */
    fun outputSize(): Long

    /** 从 [offset] 起读取至多 [maxBytes] 字节的日志 */
    fun readOutput(offset: Long, maxBytes: Int): ByteArray

    /** 终止命令及其派生的所有进程, 阻塞到进程退出 */
    fun kill()
}

/** 本机与 PRoot 后台命令共用的日志读取器; 方法均为阻塞 IO。 */
class BackgroundCommandLog(private val file: File) {
    /** 日志当前的字节数; 文件不存在时返回 0。 */
    fun size(): Long = file.length()

    /** 从 [offset] 起读取至多 [maxBytes] 字节; 文件不存在或已到 EOF 时返回空数组。 */
    fun read(offset: Long, maxBytes: Int): ByteArray {
        require(offset >= 0) { "offset must be non-negative" }
        require(maxBytes >= 0) { "maxBytes must be non-negative" }
        if (maxBytes == 0 || !file.isFile) return ByteArray(0)

        val input = try {
            RandomAccessFile(file, "r")
        } catch (error: FileNotFoundException) {
            // 文件可能在 isFile 检查后被删除; 其他打开失败仍交给调用方处理。
            if (!file.exists()) return ByteArray(0)
            throw error
        }
        return input.use { log ->
            val available = (log.length() - offset).coerceAtLeast(0)
            val buffer = ByteArray(minOf(available, maxBytes.toLong()).toInt())
            if (buffer.isEmpty()) return@use buffer
            log.seek(offset)
            var bytesRead = 0
            while (bytesRead < buffer.size) {
                val count = log.read(buffer, bytesRead, buffer.size - bytesRead)
                if (count <= 0) break
                bytesRead += count
            }
            // 日志可能在测量长度后被截断, 只返回实际读到的字节。
            if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
        }
    }
}

/** PRoot 内的后台命令; 终止方式见 [terminateProot] */
internal class ProotBackgroundCommand(
    private val process: Process,
    logFile: File,
    override val logPath: String,
    private val pidFile: File,
) : WorkspaceBackgroundCommand {
    private val log = BackgroundCommandLog(logFile)

    override fun waitFor(): Int = process.waitFor()

    override fun outputSize(): Long = log.size()

    override fun readOutput(offset: Long, maxBytes: Int): ByteArray = log.read(offset, maxBytes)

    override fun kill() = terminateProot(process, pidFile, TERMINATE_GRACE_MS)

    private companion object {
        const val TERMINATE_GRACE_MS = 3_000L
    }
}
