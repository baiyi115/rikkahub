package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.readResult
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private const val TAG = "LocalShellRunner"

/** 同时保留的持久 shell 会话上限 */
private const val MAX_SESSIONS = 4

/** 会话 shell 打开后等待环境初始化的时间; 首次 root 授权可能较慢 */
private const val SESSION_SETUP_TIMEOUT_MS = 30_000L

/** 会话输出在两次读取之间的缓冲上限, 防止后台命令刷屏占满内存 */
private const val MAX_BUFFERED_LINES = 4_096

/** 后台任务在工作区里的归属标记, 复用了工作区后台任务的基础设施 */
const val LOCAL_SHELL_WORKSPACE_ID = "local_shell"

/**
 * 在本机执行 shell: 一次性命令、带 cwd/环境持久化的会话, 以及写日志的后台任务。
 * 身份为 App 自身 UID 或经 su 获得的 root, 不使用 PRoot Rootfs。
 */
class LocalShellRunner(context: Context) {
    private val appContext = context.applicationContext

    val defaultCwd: File = File(appContext.filesDir, LOCAL_SHELL_DIR).apply { mkdirs() }

    private val tempDir: File = File(appContext.cacheDir, LOCAL_SHELL_TMP_DIR).apply { mkdirs() }

    private val jobsDir: File = File(defaultCwd, LOCAL_SHELL_JOBS_DIR).apply { mkdirs() }

    private val sessions = LinkedHashMap<String, LocalShellSession>()
    private val sessionsLock = Any()

    suspend fun execute(
        command: String,
        cwd: String?,
        timeoutMillis: Long,
        root: Boolean,
    ): LocalShellResult = runInterruptible(Dispatchers.IO) {
        executeOneShot(command, cwd, timeoutMillis, root)
    }

    /** 打开持久 shell; [cwd] 省略时使用 [defaultCwd] */
    fun openSession(root: Boolean, cwd: String? = null): LocalShellSession {
        if (root &&
            !LocalShellRuntime.isRootAvailable) {
            error("Root is not available on this device: su was not found")
        }
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        val id = "shell_" + UUID.randomUUID().toString().replace("-", "").take(8)
        val pidFile = File(tempDir, "session-$id.pid")
        val process = startSessionProcess(root)
        val session = LocalShellSession(
            id = id,
            identity = if (root) "root" else "user",
            process = process,
            pidFile = pidFile,
            runner = this,
            initialCwd = workingDir.absolutePath,
        )
        try {
            session.writeRaw(
                buildString {
                    append("echo \$\$ > ").append(pidFile.absolutePath.shellQuote()).append(" 2>/dev/null\n")
                    append(environmentExports())
                    append("cd ").append(workingDir.absolutePath.shellQuote())
                    append(" 2>/dev/null || echo __RIKKA_CWD_FAILED__\n")
                }
            )
            // 用一个无副作用的命令确认 shell 已经能处理输入; 首次 root 授权也在这段时间完成
            val probe = session.exec(":", workingDir = null, timeoutMillis = SESSION_SETUP_TIMEOUT_MS)
            if (probe.stdout.contains("__RIKKA_CWD_FAILED__")) {
                error("Working directory does not exist or is not accessible: ${workingDir.absolutePath}")
            }
            if (probe.timedOut || probe.exitCode != 0) {
                error(probe.stderr.ifBlank { "Shell session did not start (exit=${probe.exitCode})" })
            }
        } catch (e: Throwable) {
            session.close()
            throw e
        }
        synchronized(sessionsLock) {
            pruneClosedSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                session.close()
                error("Too many shell sessions (max $MAX_SESSIONS). Close one with action=close first.")
            }
            sessions[id] = session
        }
        return session
    }

    fun findSession(id: String): LocalShellSession? = synchronized(sessionsLock) {
        sessions[id]?.takeIf { it.isAlive }
    }

    fun closeSession(id: String): Boolean {
        val session = synchronized(sessionsLock) { sessions.remove(id) } ?: return false
        session.close()
        return true
    }

    fun listSessions(): List<LocalShellSessionInfo> = synchronized(sessionsLock) {
        pruneClosedSessionsLocked()
        sessions.values.map { session ->
            LocalShellSessionInfo(
                id = session.id,
                identity = session.identity,
                cwd = session.cwd,
                alive = session.isAlive,
            )
        }
    }

    fun closeAll() {
        val current = synchronized(sessionsLock) {
            sessions.values.toList().also { sessions.clear() }
        }
        current.forEach { runCatching { it.close() } }
    }

    /** 启动后台命令, stdout/stderr 合并写入日志; 调用方负责等待与终止 */
    fun startBackgroundCommand(
        command: String,
        cwd: String?,
        root: Boolean,
        id: String,
    ): WorkspaceBackgroundCommand {
        if (root && !LocalShellRuntime.isRootAvailable) {
            error("Root is not available on this device: su was not found")
        }
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifBlank {
            UUID.randomUUID().toString()
        }
        val logFile = File(jobsDir, "$safeId.log")
        val pidFile = File(tempDir, "job-$safeId.pid")
        val wrapped = buildWrappedCommand(command, workingDir, pidFile)
        val process = backgroundBuilder(wrapped, root)
            .redirectInput(File("/dev/null"))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        return LocalBackgroundCommand(
            process = process,
            logFile = logFile,
            logPath = logFile.absolutePath,
            pidFile = pidFile,
            root = root,
        )
    }

    suspend fun checkRoot(timeoutMillis: Long = 15_000L): LocalShellResult =
        execute("id -u", cwd = null, timeoutMillis = timeoutMillis, root = true)

    private fun executeOneShot(
        command: String,
        cwd: String?,
        timeoutMillis: Long,
        root: Boolean,
    ): LocalShellResult {
        if (root && !LocalShellRuntime.isRootAvailable) {
            error("Root is not available on this device: su was not found")
        }
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        val pidFile = File(tempDir, "exec-${UUID.randomUUID()}.pid")
        val wrapped = buildWrappedCommand(command, workingDir, pidFile)
        val process = startOneShotProcess(wrapped, root)
        try {
            val result = process.readResult(
                timeoutMillis = timeoutMillis,
                terminate = { terminateProcess(this, pidFile, root) },
                maxChars = MAX_OUTPUT_CHARS,
            )
            // 命令结束后回收同组后台进程, 与 Agent 的一次性命令语义一致
            if (!result.timedOut) killProcessGroup(pidFile, root)
            return LocalShellResult(
                exitCode = result.exitCode,
                stdout = result.stdout,
                stderr = result.stderr,
                timedOut = result.timedOut,
                truncated = result.truncated,
            )
        } finally {
            pidFile.delete()
        }
    }

    private fun startOneShotProcess(wrapped: String, root: Boolean): Process =
        processBuilderFor(wrapped, root)
            .redirectErrorStream(false)
            .start()

    private fun backgroundBuilder(wrapped: String, root: Boolean): ProcessBuilder =
        processBuilderFor(wrapped, root)

    private fun processBuilderFor(wrapped: String, root: Boolean): ProcessBuilder {
        val setsidAvailable = File(SETSID).let { it.isFile && it.canExecute() }
        return when {
            // root: 在 su 里再 exec setsid, 让内层 shell 成为会话组长, pid 文件记录的就是组长 pid
            root -> {
                val inner = if (setsidAvailable) {
                    "exec $SETSID $SYSTEM_SHELL -c ${wrapped.shellQuote()}"
                } else {
                    wrapped
                }
                ProcessBuilder(LocalShellRuntime.suPath() ?: error("su is not available"), "-c", inner)
            }

            setsidAvailable -> ProcessBuilder(SETSID, SYSTEM_SHELL, "-c", wrapped)
            else -> ProcessBuilder(SYSTEM_SHELL, "-c", wrapped)
        }.apply {
            environment().putAll(baseEnvironment())
        }
    }

    /** 统一工作目录、HOME、PATH 等环境; 命令里还会再 export 一次, 防止 su 重置环境 */
    internal fun buildWrappedCommand(command: String, workingDir: File, pidFile: File): String = buildString {
        append("echo \$\$ > ").append(pidFile.absolutePath.shellQuote()).append(" 2>/dev/null; ")
        append(environmentExports())
        append("cd ").append(workingDir.absolutePath.shellQuote()).append(" || exit 1; ")
        append(command)
    }

    internal fun environmentExports(): String = buildString {
        append("export HOME=").append(defaultCwd.absolutePath.shellQuote())
        append(" TMPDIR=").append(tempDir.absolutePath.shellQuote())
        append(" PATH=").append(DEFAULT_PATH.shellQuote())
        append(" SHELL=").append(SYSTEM_SHELL)
        append(" TERM=dumb NO_COLOR=1 PAGER=cat CI=true; ")
    }

    private fun baseEnvironment(): Map<String, String> = mapOf(
        "HOME" to defaultCwd.absolutePath,
        "TMPDIR" to tempDir.absolutePath,
        "PATH" to DEFAULT_PATH,
        "SHELL" to SYSTEM_SHELL,
        "TERM" to "dumb",
        "NO_COLOR" to "1",
        "PAGER" to "cat",
        "CI" to "true",
    )

    private fun startSessionProcess(root: Boolean): Process {
        val setsidAvailable = File(SETSID).let { it.isFile && it.canExecute() }
        val builder = when {
            // su -c <shell> 让 shell 直接接管 stdin/stdout, 比依赖 su 默认 shell 的行为更确定
            root -> ProcessBuilder(
                LocalShellRuntime.suPath() ?: error("su is not available"),
                "-c",
                SYSTEM_SHELL,
            )

            setsidAvailable -> ProcessBuilder(SETSID, SYSTEM_SHELL)
            else -> ProcessBuilder(SYSTEM_SHELL)
        }
        // 不能把 cwd 交给 ProcessBuilder: root 目录在提权前不可进入, 由 setup 脚本 cd
        return builder
            .redirectErrorStream(true)
            .apply { environment().putAll(baseEnvironment()) }
            .start()
    }

    private fun resolveWorkingDir(cwd: String?, requireExists: Boolean): File {
        val raw = cwd?.trim().orEmpty()
        val dir = when {
            raw.isEmpty() -> defaultCwd
            File(raw).isAbsolute -> File(raw)
            else -> File(defaultCwd, raw)
        }
        if (requireExists) {
            require(dir.exists()) { "Working directory does not exist: ${dir.absolutePath}" }
            require(dir.isDirectory) { "Working path is not a directory: ${dir.absolutePath}" }
            return dir.canonicalFile
        }
        // root 能进入 App 身份看不到的目录, 是否存在交给 shell 里的 cd 判断
        return dir
    }

    private fun killProcessGroup(pidFile: File, root: Boolean) {
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull() ?: return
        if (pid > 1) runQuietly("kill -9 -$pid 2>/dev/null", root)
    }

    /** 结束进程并尽可能回收它派生的进程; 会话、一次性命令与后台任务共用 */
    internal fun terminateProcess(process: Process, pidFile: File, root: Boolean) {
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
        // 先记录子孙进程: 根进程一死, 它们会被 reparent 到 init, 再也找不到
        val descendants = if (pid != null && pid > 1) collectProcessTree(pid) else emptyList()
        runCatching { process.destroy() }
        val exited = runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!exited) {
            runCatching { process.destroyForcibly() }
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
        if (pid != null && pid > 1) {
            runQuietly("kill -9 -$pid 2>/dev/null", root)
            runQuietly("kill -9 $pid 2>/dev/null", root)
        }
        if (descendants.isNotEmpty()) {
            runQuietly("kill -9 ${descendants.joinToString(" ")} 2>/dev/null", root)
        }
        pidFile.delete()
    }

    private fun collectProcessTree(rootPid: Long): List<Long> {
        val childrenByParent = HashMap<Long, MutableList<Long>>()
        File("/proc").listFiles()?.forEach { dir ->
            val pid = dir.name.toLongOrNull() ?: return@forEach
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: return@forEach
            val close = stat.lastIndexOf(')')
            if (close < 0) return@forEach
            // "pid (comm) state ppid ..."
            val ppid = stat.substring(close + 2).split(' ').getOrNull(1)?.toLongOrNull() ?: return@forEach
            childrenByParent.getOrPut(ppid) { mutableListOf() }.add(pid)
        }
        val result = mutableListOf<Long>()
        val queue = ArrayDeque<Long>().apply { add(rootPid) }
        while (queue.isNotEmpty()) {
            childrenByParent[queue.removeFirst()]?.forEach { child ->
                if (child != rootPid && child !in result) {
                    result.add(child)
                    queue.add(child)
                }
            }
        }
        return result
    }

    internal fun runQuietly(command: String, root: Boolean) {
        runCatching {
            val builder = if (root) {
                ProcessBuilder(LocalShellRuntime.suPath() ?: return, "-c", command)
            } else {
                ProcessBuilder(SYSTEM_SHELL, "-c", command)
            }
            builder
                .redirectOutput(File("/dev/null"))
                .redirectError(File("/dev/null"))
                .start()
                .waitFor(500, TimeUnit.MILLISECONDS)
        }
    }

    private fun pruneClosedSessionsLocked() {
        val closed = sessions.values.filterNot { it.isAlive }
        closed.forEach { session ->
            sessions.remove(session.id)
            runCatching { session.close() }
        }
    }

    /** 后台命令; 终止时会一并结束命令派生的子进程 */
    private inner class LocalBackgroundCommand(
        private val process: Process,
        private val logFile: File,
        override val logPath: String,
        private val pidFile: File,
        private val root: Boolean,
    ) : WorkspaceBackgroundCommand {
        override fun waitFor(): Int = process.waitFor()

        override fun outputSize(): Long = logFile.length()

        override fun readOutput(offset: Long, maxBytes: Int): ByteArray {
            if (!logFile.isFile) return ByteArray(0)
            return RandomAccessFile(logFile, "r").use { file ->
                val available = (file.length() - offset).coerceAtLeast(0)
                val buffer = ByteArray(minOf(available, maxBytes.toLong()).toInt())
                file.seek(offset)
                file.readFully(buffer)
                buffer
            }
        }

        override fun kill() {
            Log.i(TAG, "kill background command, root=$root")
            terminateProcess(process, pidFile, root)
        }
    }
}

data class LocalShellSessionInfo(
    val id: String,
    val identity: String,
    val cwd: String,
    val alive: Boolean,
)

/**
 * 一个常驻 shell 进程。命令按顺序写入 stdin, 输出经同一管道回流;
 * 每段命令后打印带随机标记的一行, 用来确定边界、退出码与新的工作目录。
 */
class LocalShellSession internal constructor(
    val id: String,
    val identity: String,
    private val process: Process,
    private val pidFile: File,
    private val runner: LocalShellRunner,
    initialCwd: String,
) : AutoCloseable {
    private val commandLock = Any()
    private val writeLock = Any()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val lines = LineQueue(MAX_BUFFERED_LINES)
    private val reader = thread(name = "local-shell-session-$id", isDaemon = true) {
        try {
            process.inputStream.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    lines.offer(line)
                }
            }
        } catch (_: IOException) {
        } finally {
            lines.close()
        }
    }

    @Volatile
    var cwd: String = initialCwd
        private set

    @Volatile
    var closed: Boolean = false
        private set

    val isAlive: Boolean
        get() = !closed && process.isAlive

    internal fun writeRaw(script: String) = synchronized(writeLock) {
        writer.write(script)
        writer.flush()
    }

    /** stderr 合并进 stdout; 会话超时后无法可靠地中断管道里的子进程, 因此关闭整个 shell */
    fun exec(command: String, workingDir: String? = null, timeoutMillis: Long = 30_000L): LocalShellResult =
        synchronized(commandLock) {
            if (!isAlive) return LocalShellResult(-1, "", "Shell session is closed", false, false, sessionClosed = true)

            val marker = "__RIKKA_SHELL_${UUID.randomUUID().toString().replace("-", "")}__"
            val script = buildString {
                // stdin 是命令通道, 命令必须从 /dev/null 读; 需要输入时用管道或 heredoc
                if (!workingDir.isNullOrBlank()) {
                    append("if cd ").append(workingDir.shellQuote()).append(" 2>/dev/null; then\n")
                    append("{\n").append(command).append("\n} < /dev/null\n")
                    append("else\nfalse\nfi\n")
                } else {
                    append("{\n").append(command).append("\n} < /dev/null\n")
                }
                append("printf '\\n%s %s %s\\n' '").append(marker).append("' \"\$?\" \"\$PWD\"\n")
            }
            try {
                writeRaw(script)
            } catch (e: IOException) {
                closeInternal()
                return LocalShellResult(-1, "", "Failed to write to shell: ${e.message}", false, false, true)
            }

            val output = StringBuilder()
            var truncated = false
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
            while (true) {
                val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) {
                    closeInternal()
                    return LocalShellResult(
                        exitCode = -1,
                        stdout = output.toString(),
                        stderr = "Command timed out after ${timeoutMillis / 1_000}s; the shell session was closed",
                        timedOut = true,
                        truncated = truncated,
                        sessionClosed = true,
                    )
                }
                val line = lines.poll(remainingMs)
                if (line == null) {
                    if (System.nanoTime() >= deadline) {
                        closeInternal()
                        return LocalShellResult(
                            exitCode = -1,
                            stdout = output.toString(),
                            stderr = "Command timed out after ${timeoutMillis / 1_000}s; the shell session was closed",
                            timedOut = true,
                            truncated = truncated,
                            sessionClosed = true,
                        )
                    }
                    closeInternal()
                    return LocalShellResult(
                        exitCode = -1,
                        stdout = output.toString(),
                        stderr = "Shell session exited while running the command",
                        timedOut = false,
                        truncated = truncated,
                        sessionClosed = true,
                    )
                }
                if (line.startsWith("$marker ")) {
                    val rest = line.removePrefix("$marker ").trim()
                    val exitCode = rest.substringBefore(' ').toIntOrNull() ?: -1
                    val newCwd = rest.substringAfter(' ', "").trim()
                    if (newCwd.isNotEmpty()) cwd = newCwd
                    return LocalShellResult(
                        exitCode = exitCode,
                        stdout = output.toString(),
                        stderr = "",
                        timedOut = false,
                        truncated = truncated,
                    )
                }
                val remaining = MAX_OUTPUT_CHARS - output.length
                if (remaining > line.length + 1) {
                    output.append(line).append('\n')
                } else {
                    if (remaining > 0) output.append(line, 0, remaining)
                    truncated = true
                }
            }
            error("unreachable")
        }

    override fun close() = synchronized(commandLock) { closeInternal() }

    private fun closeInternal() {
        if (closed) return
        closed = true
        runCatching { writer.close() }
        runner.terminateProcess(process, pidFile, identity == "root")
        lines.close()
        runCatching { reader.join(500) }
    }
}

/** 有界行队列: 队列满时让读取线程等待, 由管道反压输出方, 不丢行 */
private class LineQueue(private val maxLines: Int) {
    private val queue = ArrayDeque<String>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()

    @Volatile
    private var closed = false

    fun offer(line: String) = lock.withLock {
        while (!closed && queue.size >= maxLines) notFull.await()
        if (closed) return@withLock
        queue.addLast(line)
        notEmpty.signal()
    }

    fun close() = lock.withLock {
        closed = true
        notEmpty.signalAll()
        notFull.signalAll()
    }

    /** 超时或队列关闭且已排空时返回 null */
    fun poll(timeoutMillis: Long): String? {
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000L
        lock.withLock {
            while (true) {
                queue.removeFirstOrNull()?.let {
                    notFull.signal()
                    return it
                }
                if (closed) return null
                val remaining = (deadline - System.nanoTime()) / 1_000_000L
                if (remaining <= 0) return null
                notEmpty.await(remaining, TimeUnit.MILLISECONDS)
            }
        }
    }
}
