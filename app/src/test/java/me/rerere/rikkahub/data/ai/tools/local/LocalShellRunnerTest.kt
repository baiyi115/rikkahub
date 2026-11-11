package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

/** Process tests require Linux host tools; Windows still runs the platform-independent framing tests. */
class LocalShellRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = Uuid.random()
    private val other = Uuid.random()
    private lateinit var runner: LocalShellRunner

    @Before
    fun setUp() {
        assumeTrue(File("/bin/sh").canExecute() && File("/usr/bin/setsid").canExecute())
        // Exercise the su argument/setsid path without requesting host root privileges.
        val fakeSu = temporary.newFile("su").apply {
            writeText("#!/bin/sh\nexec /bin/sh \"\$@\"\n")
            check(setExecutable(true))
        }
        runner = LocalShellRunner(
            temporary.newFolder("files"),
            temporary.newFolder("cache"),
            shell = "/bin/sh",
            setsid = "/usr/bin/setsid",
            searchPath = "/usr/bin:/bin",
            suPath = { fakeSu.absolutePath },
        )
    }

    @After
    fun tearDown() {
        if (::runner.isInitialized) {
            runner.closeConversation(owner)
            runner.closeConversation(other)
        }
    }

    @Test(timeout = 15_000)
    fun sessionsCannotBeListedExecutedOrClosedByAnotherConversation() = runBlocking {
        val session = runner.openSession(owner, root = true)
        assertTrue(runner.listSessions(other).isEmpty())
        assertNull(runner.findSession(other, session.id))
        assertFalse(runner.closeSession(other, session.id))
        try {
            runner.executeSession(other, session.id, "printf wrong", null, 1_000)
            fail("Cross-conversation execution must be rejected")
        } catch (_: IllegalStateException) {
        }
        assertEquals("root", runner.findSession(owner, session.id)?.identity)
        assertEquals("ok", exec(session, "printf ok").stdout)
    }

    @Test(timeout = 15_000)
    fun deletingAConversationClosesItsSessionsAndReleasesCapacity() = runBlocking {
        repeat(4) { runner.openSession(owner, root = false) }
        val otherSession = runner.openSession(other, root = false)
        try {
            runner.openSession(owner, root = false)
            fail("Per-conversation capacity must be enforced")
        } catch (_: IllegalStateException) {
        }
        runner.closeConversation(owner)
        assertTrue(runner.listSessions(owner).isEmpty())
        assertTrue(otherSession.isAlive)
        assertTrue(runner.openSession(owner, root = false).isAlive)
    }

    @Test(timeout = 15_000)
    fun variablesWorkingDirectoryAndExactUnterminatedOutputPersist() = runBlocking {
        val directory = File(runner.defaultCwd, "空 格\n下一行").apply { mkdirs() }
        val session = runner.openSession(owner, root = false)
        assertEquals(0, exec(session, "VALUE='hello world'; export VALUE", directory.absolutePath).exitCode)
        assertEquals(directory.absolutePath, session.cwd)
        assertEquals("hello world", exec(session, "printf '%s' \"\$VALUE\"").stdout)
        assertEquals("error", exec(session, "printf error >&2").stdout)
        assertEquals(7, exec(session, "false; (exit 7)").exitCode)
    }

    @Test(timeout = 15_000)
    fun execRedirectingStdoutAndStderrDoesNotHideCompletion() = runBlocking {
        val session = runner.openSession(owner, root = false)
        val log = File(runner.defaultCwd, "redirected.log")
        val result = exec(session, "exec >${log.absolutePath.shellQuote()} 2>&1; printf redirected")
        assertEquals(0, result.exitCode)
        assertFalse(result.timedOut)
        assertFalse(result.sessionClosed)
        assertEquals("", result.stdout)
        assertEquals("redirected", log.readText())
        assertEquals(0, exec(session, "exec >/dev/null; printf silent").exitCode)
        assertEquals(0, exec(session, ":").exitCode)
        assertTrue(session.isAlive)
    }

    @Test(timeout = 15_000)
    fun longOutputWithoutNewlinesIsCappedAndFollowingCommandStillWorks() = runBlocking {
        val session = runner.openSession(owner, root = false)
        val result = exec(session, "head -c 1000000 /dev/zero | tr '\\000' x")
        assertEquals("x".repeat(MAX_OUTPUT_CHARS), result.stdout)
        assertTrue(result.truncated)
        assertEquals(0, result.exitCode)
        assertEquals("next", exec(session, "printf next").stdout)
    }

    @Test(timeout = 15_000)
    fun stdinDoesNotConsumeProtocolButPipesAndHeredocsStillWork() = runBlocking {
        val session = runner.openSession(owner, root = false)
        assertEquals("done", exec(session, "cat; printf done").stdout)
        assertEquals("ABC\n", exec(session, "cat <<'EOF' | tr a-z A-Z\nabc\nEOF").stdout)
    }

    @Test(timeout = 15_000)
    fun exitAndErrexitReportClosedSessionWithoutWaitingForTimeout() = runBlocking {
        for (command in listOf("printf before; exit 7", "printf before; set -e; false")) {
            val session = runner.openSession(owner, root = false)
            val result = exec(session, command)
            assertEquals("before", result.stdout)
            assertFalse(result.timedOut)
            assertTrue(result.sessionClosed)
            assertTrue(result.exitCode != 0)
            assertNull(runner.findSession(owner, session.id))
        }
    }

    @Test(timeout = 15_000)
    fun timeoutKillsSessionBeforeDelayedSideEffects() = runBlocking {
        val effect = File(runner.defaultCwd, "must-not-exist")
        val session = runner.openSession(owner, root = false)
        val result = runner.executeSession(owner, session.id, delayedWrite(effect), null, 100)
        assertTrue(result.timedOut)
        assertTrue(result.sessionClosed)
        delay(1_000)
        assertFalse(effect.exists())
        assertTrue(runner.listSessions(owner).isEmpty())
    }

    @Test(timeout = 15_000)
    fun cancellingExecutionKillsSessionAndPreventsDelayedSideEffects() = runBlocking {
        val started = File(runner.defaultCwd, "started")
        val effect = File(runner.defaultCwd, "must-not-exist")
        val session = runner.openSession(owner, root = true)
        val job = launch(Dispatchers.Default) {
            runner.executeSession(
                owner,
                session.id,
                "printf ready >${started.absolutePath.shellQuote()}; ${delayedWrite(effect)}",
                null,
                10_000
            )
        }
        awaitFile(started)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertFalse(session.isAlive)
        assertTrue(runner.listSessions(owner).isEmpty())
        delay(1_000)
        assertFalse(effect.exists())
    }

    @Test(timeout = 15_000)
    fun closingSessionDoesNotWaitForItsCommandLock() = runBlocking {
        val started = File(runner.defaultCwd, "started")
        val session = runner.openSession(owner, root = false)
        val result = async(Dispatchers.Default) {
            runner.executeSession(
                owner,
                session.id,
                "printf ready >${started.absolutePath.shellQuote()}; sleep 10",
                null,
                10_000
            )
        }
        awaitFile(started)
        withTimeout(5_000) {
            withContext(Dispatchers.IO) { assertTrue(runner.closeSession(owner, session.id)) }
            assertTrue(result.await().sessionClosed)
        }
    }

    @Test(timeout = 15_000)
    fun cancellingSessionOpenDoesNotLeaveAnUnreturnedSession() = runBlocking {
        val started = temporary.newFile("opening").apply { delete() }
        val su = temporary.newFile("slow-su").apply {
            writeText("#!/bin/sh\nprintf ready >${started.absolutePath.shellQuote()}\nsleep 10\nexec /bin/sh \"\$@\"\n")
            check(setExecutable(true))
        }
        val slow = LocalShellRunner(
            temporary.newFolder("slow-files"),
            temporary.newFolder("slow-cache"),
            shell = "/bin/sh",
            setsid = "/usr/bin/setsid",
            searchPath = "/usr/bin:/bin",
            suPath = { su.absolutePath }
        )
        try {
            val job = launch(Dispatchers.Default) { slow.openSession(owner, root = true) }
            awaitFile(started)
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(slow.listSessions(owner).isEmpty())
        } finally {
            slow.closeConversation(owner)
        }
    }

    @Test(timeout = 15_000)
    fun backgroundParentExitCleansChildrenBeforeReportingCompletion() = runBlocking {
        for (root in listOf(false, true)) {
            val effect = File(runner.defaultCwd, "background-effect-$root")
            val command = runner.startBackgroundCommand(
                "(${delayedWrite(effect)}) >/dev/null 2>&1 &",
                null,
                root,
                "job-$root"
            )
            try {
                assertEquals(0, withContext(Dispatchers.IO) { command.waitFor() })
                delay(1_000)
                assertFalse(effect.exists())
            } finally {
                command.kill()
            }
        }
    }

    @Test(timeout = 15_000)
    fun stoppingBackgroundCommandCleansChildrenAndKeepsItsLogReadable() = runBlocking {
        val effect = File(runner.defaultCwd, "background-effect")
        val command = runner.startBackgroundCommand("printf before; ${delayedWrite(effect)}", null, false, "stopped")
        try {
            withTimeout(5_000) { while (command.outputSize() == 0L) delay(10) }
            withContext(Dispatchers.IO) {
                command.kill()
                command.waitFor()
            }
            assertTrue(command.readOutput(0, 1024).decodeToString().startsWith("before"))
            delay(1_000)
            assertFalse(effect.exists())
        } finally {
            command.kill()
        }
    }

    @Test(timeout = 15_000)
    fun oneShotExitCleansChildrenEvenWhenTheyInheritOutputPipes() = runBlocking {
        val effect = File(runner.defaultCwd, "oneshot-effect")
        val result = runner.execute("(${delayedWrite(effect)}) &", null, 5_000, false)
        assertEquals(0, result.exitCode)
        delay(1_000)
        assertFalse(effect.exists())
    }

    @Test(timeout = 15_000)
    fun oneShotCancellationPreventsDelayedSideEffects() = runBlocking {
        val started = File(runner.defaultCwd, "started")
        val effect = File(runner.defaultCwd, "oneshot-effect")
        val job = launch(Dispatchers.Default) {
            runner.execute(
                "printf ready >${started.absolutePath.shellQuote()}; ${delayedWrite(effect)}",
                null,
                10_000,
                false
            )
        }
        awaitFile(started)
        withTimeout(5_000) { job.cancelAndJoin() }
        delay(1_000)
        assertFalse(effect.exists())
    }

    @Test(timeout = 15_000)
    fun oneShotCleanupPreservesStdoutAndStderr() = runBlocking {
        val result = runner.execute("head -c 32768 /dev/zero | tr '\\000' x; printf error >&2", null, 5_000, false)
        assertEquals("x".repeat(MAX_OUTPUT_CHARS), result.stdout)
        assertEquals("error", result.stderr)
        assertEquals(0, result.exitCode)
        assertFalse(result.truncated)
    }

    @Test(timeout = 15_000)
    fun missingIsolationFailsBeforeExecutingTheCommand() = runBlocking {
        val unavailable = LocalShellRunner(
            temporary.newFolder(),
            temporary.newFolder(),
            shell = "/bin/sh",
            setsid = "/missing/setsid",
            searchPath = "/usr/bin:/bin"
        )
        val effect = File(runner.defaultCwd, "isolation-effect")
        try {
            unavailable.execute("touch ${effect.absolutePath.shellQuote()}", null, 1_000, false)
            fail("Missing setsid must fail closed")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("setsid"))
        }
        assertFalse(effect.exists())
    }

    private suspend fun exec(session: LocalShellSession, command: String, cwd: String? = null) =
        runner.executeSession(owner, session.id, command, cwd, 5_000)

    private fun delayedWrite(file: File) = "sleep 0.8; printf wrong >${file.absolutePath.shellQuote()}"

    private suspend fun awaitFile(file: File) = withTimeout(5_000) {
        while (!file.exists() || file.length() == 0L) delay(10)
    }
}
