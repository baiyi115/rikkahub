package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import me.rerere.workspace.BackgroundCommandLog
import me.rerere.workspace.WorkspaceBackgroundCommand
import me.rerere.workspace.readResult
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private const val MAX_SESSIONS_PER_CONVERSATION = 4
private const val MAX_SESSIONS = 16
private const val SESSION_SETUP_TIMEOUT_MS = 30_000L

const val LOCAL_SHELL_WORKSPACE_ID = "local_shell"

/** Android host execution; the file-based constructor also allows JVM process regression tests. */
class LocalShellRunner internal constructor(
    filesDir: File,
    cacheDir: File,
    private val shell: String = SYSTEM_SHELL,
    private val setsid: String = SETSID,
    private val searchPath: String = DEFAULT_PATH,
    private val suPath: () -> String? = LocalShellRuntime::suPath,
) {
    constructor(context: Context) : this(context.applicationContext.filesDir, context.applicationContext.cacheDir)

    val defaultCwd = File(filesDir, LOCAL_SHELL_DIR).apply { mkdirs() }
    private val tempDir = File(cacheDir, LOCAL_SHELL_TMP_DIR).apply { mkdirs() }
    private val jobsDir = File(defaultCwd, LOCAL_SHELL_JOBS_DIR).apply { mkdirs() }
    private val sessions = LinkedHashMap<String, LocalShellSession>()
    private val sessionsLock = Any()

    suspend fun execute(command: String, cwd: String?, timeoutMillis: Long, root: Boolean): LocalShellResult =
        runInterruptible(Dispatchers.IO) { executeOneShot(command, cwd, timeoutMillis, root) }

    suspend fun openSession(conversationId: Uuid, root: Boolean, cwd: String? = null): LocalShellSession {
        var opened: LocalShellSession? = null
        try {
            return runInterruptible(Dispatchers.IO) {
                createSession(conversationId, root, cwd).also { opened = it }
            }
        } catch (e: CancellationException) {
            opened?.let { closeSession(conversationId, it.id) }
            throw e
        }
    }

    private fun createSession(conversationId: Uuid, root: Boolean, cwd: String?): LocalShellSession {
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        synchronized(sessionsLock) { requireSessionCapacity(conversationId) }
        val id = "shell_${UUID.randomUUID().toString().replace("-", "")}"
        val pidFile = File(tempDir, "session-$id.pid")
        val session = LocalShellSession(
            id = id,
            conversationId = conversationId,
            identity = if (root) "root" else "user",
            process = processBuilderFor(null, root, pidFile).redirectErrorStream(true).start(),
            pidFile = pidFile,
            runner = this,
            initialCwd = workingDir.absolutePath,
        )
        try {
            synchronized(sessionsLock) {
                val closed = sessions.values.filterNot { it.isAlive }
                closed.forEach {
                    sessions.remove(it.id)
                    it.close()
                }
                requireSessionCapacity(conversationId)
                sessions[id] = session
            }
            session.writeRaw(
                "exec 3>&1\n${environmentExports()}\n"
            )
            val probe = session.exec(":", workingDir.absolutePath, SESSION_SETUP_TIMEOUT_MS)
            check(!probe.timedOut && !probe.sessionClosed && probe.exitCode == 0 && session.isAlive) {
                probe.stderr.ifBlank { "Shell session could not enter ${workingDir.absolutePath}" }
            }
            return session
        } catch (e: Throwable) {
            synchronized(sessionsLock) { sessions.remove(id) }
            session.close()
            throw e
        }
    }

    private fun requireSessionCapacity(conversationId: Uuid) {
        check(sessions.values.count { it.isAlive } < MAX_SESSIONS) {
            "Too many shell sessions on this device (max $MAX_SESSIONS)"
        }
        check(sessions.values.count { it.conversationId == conversationId && it.isAlive } < MAX_SESSIONS_PER_CONVERSATION) {
            "Too many shell sessions in this conversation (max $MAX_SESSIONS_PER_CONVERSATION); close one first"
        }
    }

    fun findSession(conversationId: Uuid, id: String): LocalShellSession? = synchronized(sessionsLock) {
        sessions[id]?.takeIf { it.conversationId == conversationId && it.isAlive }
    }

    suspend fun executeSession(
        conversationId: Uuid,
        id: String,
        command: String,
        cwd: String?,
        timeoutMillis: Long,
    ): LocalShellResult {
        val session = findSession(conversationId, id) ?: error("Session not found or already closed: $id")
        try {
            return runInterruptible(Dispatchers.IO) { session.exec(command, cwd, timeoutMillis) }
        } catch (e: CancellationException) {
            closeSession(conversationId, id)
            throw e
        } finally {
            if (!session.isAlive) closeSession(conversationId, id)
        }
    }

    fun closeSession(conversationId: Uuid, id: String): Boolean {
        val session = synchronized(sessionsLock) {
            sessions[id]?.takeIf { it.conversationId == conversationId }?.also { sessions.remove(id) }
        } ?: return false
        session.close()
        return true
    }

    fun closeConversation(conversationId: Uuid) {
        val removed = synchronized(sessionsLock) {
            sessions.values.filter { it.conversationId == conversationId }.also { list ->
                list.forEach { sessions.remove(it.id) }
            }
        }
        removed.forEach { it.close() }
    }

    fun listSessions(conversationId: Uuid): List<LocalShellSessionInfo> = synchronized(sessionsLock) {
        sessions.values.filter { it.conversationId == conversationId && it.isAlive }.map {
            LocalShellSessionInfo(it.id, it.identity, it.cwd, true)
        }
    }

    fun startBackgroundCommand(command: String, cwd: String?, root: Boolean, id: String): WorkspaceBackgroundCommand {
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        val safeId = id.filter {
            it.isLetterOrDigit() || it == '-' || it == '_'
        }.ifBlank { UUID.randomUUID().toString() }
        val logFile = File(jobsDir, "$safeId.log")
        val pidFile = File(tempDir, "job-$safeId.pid")
        val process = processBuilderFor(buildWrappedCommand(command, workingDir), root, pidFile)
            .redirectInput(File("/dev/null"))
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .start()
        return LocalBackgroundCommand(process, logFile, pidFile, root)
    }

    suspend fun checkRoot(timeoutMillis: Long = 15_000L): LocalShellResult =
        execute("id -u", cwd = null, timeoutMillis = timeoutMillis, root = true)

    private fun executeOneShot(command: String, cwd: String?, timeoutMillis: Long, root: Boolean): LocalShellResult {
        val workingDir = resolveWorkingDir(cwd, requireExists = !root)
        val pidFile = File(tempDir, "exec-${UUID.randomUUID()}.pid")
        val process = processBuilderFor(buildWrappedCommand(command, workingDir), root, pidFile).start()
        try {
            val result = process.readResult(
                timeoutMillis = timeoutMillis,
                onFinished = { terminateProcess(process, pidFile, root) },
                terminate = { terminateProcess(this, pidFile, root) },
                maxChars = MAX_OUTPUT_CHARS,
            )
            return LocalShellResult(result.exitCode, result.stdout, result.stderr, result.timedOut, result.truncated)
        } finally {
            terminateProcess(process, pidFile, root)
        }
    }

    private fun processBuilderFor(command: String?, root: Boolean, pidFile: File): ProcessBuilder {
        check(File(setsid).let { it.isFile && it.canExecute() }) {
            "setsid is unavailable; safe shell process isolation is required"
        }
        val body = "echo \$\$ > ${pidFile.absolutePath.shellQuote()}; " + (command ?: "exec ${shell.shellQuote()}")
        val args = listOf(setsid, shell, "-c", body)
        // Launch setsid as a non-job-controlled child so it cannot fork away from the process we wait for.
        val launch = "set +m; exec 4<&0; " + args.joinToString(" ") { it.shellQuote() } +
            " <&4 4<&- & wait \$!"
        return if (root) {
            val su = suPath() ?: error("Root is not available: su was not found")
            val proxy = "echo user:\$\$ > ${pidFile.absolutePath.shellQuote()}; exec ${su.shellQuote()} -c ${launch.shellQuote()}"
            ProcessBuilder(shell, "-c", proxy)
        } else {
            ProcessBuilder(shell, "-c", launch)
        }.apply { environment().putAll(baseEnvironment()) }
    }

    private fun buildWrappedCommand(command: String, workingDir: File): String =
        environmentExports() +
            "cd ${workingDir.absolutePath.shellQuote()} || exit 1;\n$command"

    private fun baseEnvironment() = mapOf(
        "HOME" to defaultCwd.absolutePath,
        "TMPDIR" to tempDir.absolutePath,
        "PATH" to searchPath,
        "SHELL" to shell,
        "TERM" to "dumb",
        "NO_COLOR" to "1",
        "PAGER" to "cat",
        "CI" to "true",
    )

    private fun environmentExports() = baseEnvironment().entries.joinToString(" ", "export ", "; ") {
        "${it.key}=${it.value.shellQuote()}"
    }

    private fun resolveWorkingDir(cwd: String?, requireExists: Boolean): File {
        val raw = cwd?.trim().orEmpty()
        val dir = when {
            raw.isEmpty() -> defaultCwd
            File(raw).isAbsolute -> File(raw)
            else -> File(defaultCwd, raw)
        }
        if (!requireExists) return dir
        require(dir.isDirectory) { "Working directory does not exist or is not a directory: ${dir.absolutePath}" }
        return dir.canonicalFile
    }

    internal fun terminateProcess(process: Process, pidFile: File, root: Boolean) {
        // Cleanup must still run if interruption arrived while writing or starting the shell.
        val interrupted = Thread.interrupted()
        try {
            val recorded = runCatching { pidFile.readText().trim() }.getOrDefault("")
            val pid = recorded.removePrefix("user:").toLongOrNull()?.takeIf { it > 1 }
            if (pid != null) {
                // Before su is ready only its app-owned proxy exists; do not request root again to cancel it.
                runQuietly(buildProcessCleanup(pid), root && !recorded.startsWith("user:"))
            }
            if (process.isAlive) {
                runCatching { process.destroy() }
                if (!runCatching { process.waitFor(500, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    runCatching { process.destroyForcibly() }
                    runCatching { process.waitFor(1, TimeUnit.SECONDS) }
                }
            }
        } finally {
            pidFile.delete()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun runQuietly(command: String, root: Boolean) {
        runCatching {
            val args = if (root) listOf(suPath() ?: return, "-c", command) else listOf(shell, "-c", command)
            val cleanup = ProcessBuilder(args).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { environment()["PATH"] = searchPath }.start()
            try {
                if (!cleanup.waitFor(2, TimeUnit.SECONDS)) cleanup.destroyForcibly()
            } finally {
                if (cleanup.isAlive) cleanup.destroyForcibly()
                cleanup.outputStream.close()
            }
        }
    }

    private inner class LocalBackgroundCommand(
        private val process: Process,
        private val logFile: File,
        private val pidFile: File,
        private val root: Boolean,
    ) : WorkspaceBackgroundCommand {
        private val log = BackgroundCommandLog(logFile)
        private val cleanupLock = Any()
        private var cleaned = false
        override val logPath: String get() = logFile.absolutePath

        override fun waitFor(): Int = try {
            process.waitFor()
        } finally {
            cleanup()
        }

        override fun outputSize() = log.size()
        override fun readOutput(offset: Long, maxBytes: Int) = log.read(offset, maxBytes)
        override fun kill() = cleanup()

        private fun cleanup() = synchronized(cleanupLock) {
            if (!cleaned) {
                terminateProcess(process, pidFile, root)
                cleaned = true
            }
        }
    }
}

// Record descendants before killing the parent, then kill both the process group and escaped descendants.
internal fun buildProcessCleanup(pid: Long): String {
    require(pid > 1)
    return """
        parents=$pid
        targets=$pid
        while [ -n "${'$'}parents" ]; do
          next=
          for stat in /proc/[0-9]*/stat; do
            [ -r "${'$'}stat" ] || continue
            IFS= read -r record < "${'$'}stat" || continue
            child=${'$'}{record%% *}
            fields=${'$'}{record##*) }
            parent=${'$'}{fields#* }
            parent=${'$'}{parent%% *}
            case " ${'$'}parents " in *" ${'$'}parent "*) next="${'$'}next ${'$'}child" ;; esac
          done
          targets="${'$'}targets ${'$'}next"
          parents=${'$'}next
        done
        kill -9 -$pid 2>/dev/null
        kill -9 ${'$'}targets 2>/dev/null
    """.trimIndent()
}

data class LocalShellSessionInfo(val id: String, val identity: String, val cwd: String, val alive: Boolean)
