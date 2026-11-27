package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import me.rerere.rikkahub.service.SessionActivity
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val MAX_LIVE_AGENTS = 4
private const val MAX_PENDING_MESSAGES = 16
private const val NOTICE_PREVIEW_CHARS = 400
private const val SUBAGENT_REPORT_KEY = "subagent_report"

internal fun UIMessagePart.Notice.displayText(): String =
    (metadata?.get(SUBAGENT_REPORT_KEY) as? JsonPrimitive)?.contentOrNull ?: text

internal enum class SubagentStatus(val wire: String) {
    RUNNING("running"),
    IDLE("idle"),
    CLOSED("closed"),
}

internal data class SubagentSnapshot(
    val id: String,
    val description: String,
    val status: SubagentStatus = SubagentStatus.RUNNING,
    val outcome: String = "running",
    val report: String = "",
    val steps: Int = 0,
    val tokens: Int = 0,
)

internal data class SubagentNoticeEvent(
    val conversationId: Uuid,
    val key: String,
    val notice: UIMessagePart.Notice,
)

internal enum class SendOutcome { NotFound, Queued, Started, MailboxFull }

internal data class WaitOutcome(val agent: SubagentSnapshot, val timedOut: Boolean = false)

internal data class SubagentSession(
    val id: String,
    val conversationId: Uuid,
    val description: String,
    val systemPrompt: String,
    val tools: List<Tool>,
    val params: TextGenerationParams,
    val provider: Provider<ProviderSetting>,
    val providerSetting: ProviderSetting,
    val retry: RetryPolicy,
    val maxSteps: Int,
    val timeoutSeconds: Long,
    val maxTotalTokens: Int,
)

class SubagentManager(private val context: Context? = null) {
    private class Agent(val session: SubagentSession, task: String) {
        var snapshot = SubagentSnapshot(session.id, session.description)
        var history = listOf(UIMessage.system(session.systemPrompt), UIMessage.user(task))
        val mailbox = ArrayDeque<String>()
        var run: Run? = null
    }

    private class Run {
        val completion = CompletableDeferred<SubagentSnapshot>()
        lateinit var job: Job
        var steps = 0
        var tokens = 0
        var result: SubagentSnapshot? = null
        val startedAt = TimeSource.Monotonic.markNow()

        fun remainingMillis(session: SubagentSession) =
            session.timeoutSeconds * 1_000L - startedAt.elapsedNow().inWholeMilliseconds
    }

    // One lock protects ownership, admission, snapshots and turn transitions. No I/O under the lock.
    private val lock = Any()
    private val agents = mutableMapOf<Uuid, MutableMap<String, Agent>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshots = MutableStateFlow<Map<Uuid, List<SubagentSnapshot>>>(emptyMap())
    internal val tasks = snapshots.asStateFlow()

    // A single ChatService consumer drains the queue, including completions before it starts collecting.
    private val notices = Channel<SubagentNoticeEvent>(Channel.UNLIMITED)
    internal val noticeEvents = notices.receiveAsFlow()

    internal fun list(conversationId: Uuid): List<SubagentSnapshot> = tasks.value[conversationId].orEmpty()

    internal fun launch(session: SubagentSession, task: String) = synchronized(lock) {
        val group = agents.getOrPut(session.conversationId) { linkedMapOf() }
        check(group.size < MAX_LIVE_AGENTS) {
            "Too many live subagents (${group.size}); close one with subagent_close first."
        }
        val agent = Agent(session, task)
        group[session.id] = agent
        startRun(agent)
        publish(session.conversationId)
    }

    internal fun send(conversationId: Uuid, id: String, text: String): SendOutcome = synchronized(lock) {
        val agent = agents[conversationId]?.get(id) ?: return@synchronized SendOutcome.NotFound
        if (agent.run != null) {
            if (agent.mailbox.size >= MAX_PENDING_MESSAGES) return@synchronized SendOutcome.MailboxFull
            agent.mailbox.addLast(text)
            return@synchronized SendOutcome.Queued
        }
        agent.history = agent.history + UIMessage.user(text)
        startRun(agent)
        publish(conversationId)
        SendOutcome.Started
    }

    internal suspend fun waitFor(conversationId: Uuid, id: String, timeoutMillis: Long): WaitOutcome? {
        val run = synchronized(lock) {
            val agent = agents[conversationId]?.get(id) ?: return null
            agent.run ?: return WaitOutcome(agent.snapshot)
        }
        val result = withTimeoutOrNull(timeoutMillis) { run.completion.await() }
        if (result != null) return WaitOutcome(result)
        return synchronized(lock) {
            run.result?.let { return@synchronized WaitOutcome(it) }
            val latest = agents[conversationId]?.get(id)?.snapshot ?: return@synchronized null
            WaitOutcome(latest, timedOut = true)
        }
    }

    internal fun close(conversationId: Uuid, id: String): String? = synchronized(lock) {
        val agent = agents[conversationId]?.remove(id) ?: return@synchronized null
        val status = agent.snapshot.status.wire
        cancel(agent)
        publish(conversationId)
        status
    }

    internal fun stopRunning(conversationId: Uuid) = synchronized(lock) {
        val group = agents[conversationId] ?: return@synchronized
        val running = group.values.filter { it.run != null }
        if (running.isEmpty()) return@synchronized
        running.forEach {
            group.remove(it.session.id)
            cancel(it)
        }
        publish(conversationId)
    }

    fun closeConversation(conversationId: Uuid) = synchronized(lock) {
        agents.remove(conversationId)?.values?.forEach(::cancel)
        publish(conversationId)
    }

    private fun cancel(agent: Agent) {
        agent.run?.let { run ->
            agent.run = null
            val stopped = agent.snapshot.copy(status = SubagentStatus.CLOSED, outcome = "cancelled")
            run.result = stopped
            run.completion.complete(stopped)
            run.job.cancel()
        }
    }

    private fun publish(conversationId: Uuid) {
        val group = agents[conversationId]
        snapshots.value = if (group.isNullOrEmpty()) {
            agents.remove(conversationId)
            snapshots.value - conversationId
        } else {
            snapshots.value + (conversationId to group.values.map { it.snapshot })
        }
    }

    private fun startRun(agent: Agent) {
        val session = agent.session
        val run = Run()
        val initialHistory = agent.history
        agent.snapshot = SubagentSnapshot(session.id, session.description)
        agent.run = run
        run.job = scope.launch(start = CoroutineStart.LAZY) {
            val leaseId = Uuid.random()
            val acquired = context?.let {
                ChatGenerationForegroundService.acquire(it, leaseId, session.conversationId, SessionActivity.Generating)
            } == true
            try {
                var history = initialHistory
                while (true) {
                    val result = runSubagentTurn(
                        request = AgentRequest(
                            provider = session.provider,
                            providerSetting = session.providerSetting,
                            messages = history,
                            tools = session.tools,
                            stream = false,
                            maxSteps = session.maxSteps - run.steps,
                            retry = session.retry,
                        ),
                        params = session.params,
                        timeoutMillis = run.remainingMillis(session),
                        maxTotalTokens = session.maxTotalTokens - run.tokens,
                        takePendingParts = { takePendingParts(agent, run) },
                    )
                    history = finishTurn(agent, run, history, result) ?: break
                }
            } finally {
                if (acquired) context?.let { ChatGenerationForegroundService.release(it, leaseId) }
            }
        }
        run.job.invokeOnCompletion { cause ->
            if (cause == null) return@invokeOnCompletion
            synchronized(lock) {
                if (agent.run !== run) return@synchronized
                finishTurn(agent, run, agent.history, SubagentRunResult(null, "failed", 0, cause.message))
            }
        }
        run.job.start()
    }

    private fun takePendingParts(agent: Agent, run: Run): List<UIMessagePart> = synchronized(lock) {
        if (agent.run !== run || agent.mailbox.isEmpty()) return@synchronized emptyList()
        listOf(UIMessagePart.Notice("Message from the main agent", takeInstructions(agent)))
    }

    private fun takeInstructions(agent: Agent): String =
        agent.mailbox.joinToString("\n\n").also { agent.mailbox.clear() }

    private fun finishTurn(
        agent: Agent,
        run: Run,
        history: List<UIMessage>,
        result: SubagentRunResult,
    ): List<UIMessage>? {
        val session = agent.session
        val message = result.message?.let { message ->
            // A cancelled or budget-limited batch can contain tool calls without results.
            val reusable = message.parts.filterNot { it is UIMessagePart.Tool && !it.isExecuted }
            reusable.takeIf { it.isNotEmpty() }?.let { message.copy(parts = it) }
        }
        val updatedHistory = if (message == null) history else history + message
        val report = result.report
        return synchronized(lock) {
            if (agent.run !== run) return@synchronized null
            agent.history = updatedHistory
            run.steps += result.steps
            run.tokens += result.tokens
            val pendingCount = agent.mailbox.size
            // Messages arriving during the final request can use what is left of this run's budget.
            val outcome = if (result.outcome == "completed" && pendingCount > 0) {
                when {
                    run.remainingMillis(session) <= 0 -> "timeout"
                    run.steps >= session.maxSteps -> "step_limit_reached"
                    run.tokens >= session.maxTotalTokens -> "token_limit_reached"
                    else -> {
                        val continued = updatedHistory + UIMessage.user(takeInstructions(agent))
                        agent.history = continued
                        return@synchronized continued
                    }
                }
            } else {
                result.outcome
            }
            agent.mailbox.clear()
            val done = SubagentSnapshot(
                id = session.id,
                description = session.description,
                status = SubagentStatus.IDLE,
                outcome = outcome,
                report = report,
                steps = run.steps,
                tokens = run.tokens,
            )
            agent.snapshot = done
            agent.run = null
            publish(session.conversationId)
            run.result = done
            run.completion.complete(done)
            notices.trySend(
                SubagentNoticeEvent(
                    conversationId = session.conversationId,
                    key = "subagent-${session.id}",
                    notice = UIMessagePart.Notice(
                        title = "Subagent \"${session.description}\" ${done.outcome}",
                        text = buildString {
                            appendLine("Subagent \"${session.description}\" (${session.id}): ${done.outcome}.")
                            appendLine(report.take(NOTICE_PREVIEW_CHARS).ifBlank { "(no report)" })
                            if (report.length > NOTICE_PREVIEW_CHARS) appendLine("Use subagent_wait for the full report.")
                            if (pendingCount > 0) appendLine("$pendingCount queued message(s) were not delivered.")
                        },
                        metadata = if (report.length > NOTICE_PREVIEW_CHARS) {
                            buildJsonObject { put(SUBAGENT_REPORT_KEY, report) }
                        } else {
                            null
                        },
                    ),
                ),
            )
            null
        }
    }
}
