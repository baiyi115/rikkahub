package me.rerere.rikkahub.data.ai.tools

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.RetryPolicy
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "SubagentManager"

/** 每个会话同时存活的后台子代理上限, 避免模型一次开出太多条独立请求 */
internal const val SUBAGENT_MAX_LIVE = 4

/** 完成通知里带多少字报告; 完整报告用 subagent_wait 取 */
private const val NOTICE_PREVIEW_CHARS = 400

internal enum class SubagentStatus(val wire: String) {
    RUNNING("running"),
    IDLE("idle"),
    CLOSED("closed"),
}

/** 后台子代理结束一轮时的通知, 由 ChatService 投进会话收件箱 */
sealed interface SubagentNoticeEvent {
    val conversationId: Uuid
    val key: String

    data class Posted(
        override val conversationId: Uuid,
        override val key: String,
        val notice: UIMessagePart.Notice,
    ) : SubagentNoticeEvent
}

internal sealed interface SendOutcome {
    data object NotFound : SendOutcome
    data object Closed : SendOutcome

    /** 正在跑, 消息已放进邮箱, 下一步请求模型前送达 */
    data object Delivered : SendOutcome

    /** 已经空闲, 带着原上下文开始新的一轮 */
    data object Started : SendOutcome
}

internal sealed interface WaitOutcome {
    data object NotFound : WaitOutcome

    data class Done(
        val status: String,
        val outcome: String,
        val report: String,
        val steps: Int,
        val tokens: Int,
        val timedOut: Boolean,
    ) : WaitOutcome
}

/**
 * 一个后台子代理的会话。
 *
 * 跑完一轮不销毁: 主代理可以继续用 [SubagentManager.send] 带着原上下文改方向,
 * 不必重新把已经读过的文件再读一遍。
 */
internal class SubagentSession(
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
) {
    val mutex = Mutex()

    /** 主代理在运行中发来的消息, 下一步请求模型前取走并追加到子代理的消息末尾 */
    val mailbox = Channel<UIMessagePart.Notice>(Channel.UNLIMITED)

    var messages: List<UIMessage> = emptyList()

    @Volatile
    var status: SubagentStatus = SubagentStatus.RUNNING

    var job: Job? = null

    /** 当前这一轮的完成信号, 空闲时为 null */
    var completion: CompletableDeferred<Unit>? = null

    var report: String = ""
    var outcome: String = "running"

    /** 运行中由事件流更新, 等待方随时读取 */
    @Volatile
    var tokens: Int = 0
    var steps: Int = 0
    var startedAt: Long = System.currentTimeMillis()
}

/**
 * 后台子代理的注册表。
 *
 * 对应 Claude Code 的 agent 会话和 Codex 的 spawn_agent/send_input/wait_agent:
 * 子代理有自己的上下文和邮箱, 主代理可以继续干活、中途发消息改方向, 再等它给出报告。
 * 会话按 conversationId 归属, 进程重启后丢失; 完成时发通知, 主代理这一轮已经结束时
 * 由 ChatService 投进收件箱并开新一轮。
 */
class SubagentManager {
    private val sessions = ConcurrentHashMap<String, SubagentSession>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _noticeEvents = MutableSharedFlow<SubagentNoticeEvent>(extraBufferCapacity = 64)
    val noticeEvents: SharedFlow<SubagentNoticeEvent> = _noticeEvents.asSharedFlow()

    fun liveCount(conversationId: Uuid): Int = sessions.values.count {
        it.conversationId == conversationId && it.status != SubagentStatus.CLOSED
    }

    internal fun list(conversationId: Uuid): List<SubagentSession> =
        sessions.values
            .filter { it.conversationId == conversationId }
            .sortedBy { it.startedAt }

    internal fun launch(session: SubagentSession, task: String) {
        sessions[session.id] = session
        session.messages = listOf(UIMessage.system(session.systemPrompt), UIMessage.user(task))
        startRun(session)
    }

    internal suspend fun send(id: String, text: String): SendOutcome {
        val session = sessions[id] ?: return SendOutcome.NotFound
        return session.mutex.withLock {
            when (session.status) {
                SubagentStatus.CLOSED -> SendOutcome.Closed
                SubagentStatus.RUNNING -> {
                    val sent = session.mailbox.trySend(
                        UIMessagePart.Notice(title = "Message from the main agent", text = text)
                    ).isSuccess
                    if (sent) SendOutcome.Delivered else SendOutcome.NotFound
                }

                SubagentStatus.IDLE -> {
                    // 带着之前的上下文继续: 已经读过的文件和搜索结果的工具结果都还在
                    session.messages = session.messages + UIMessage.user(text)
                    startRun(session)
                    SendOutcome.Started
                }
            }
        }
    }

    /** 等当前这一轮结束; 已经空闲时立即返回 */
    internal suspend fun waitFor(id: String, timeoutMillis: Long): WaitOutcome {
        val session = sessions[id] ?: return WaitOutcome.NotFound
        val completion = session.mutex.withLock { session.completion }
        val timedOut = completion != null && withTimeoutOrNull(timeoutMillis) { completion.await() } == null
        return session.mutex.withLock {
            WaitOutcome.Done(
                status = session.status.wire,
                outcome = session.outcome,
                report = session.report,
                steps = session.steps,
                tokens = session.tokens,
                timedOut = timedOut,
            )
        }
    }

    internal suspend fun close(id: String): String? {
        val session = sessions[id] ?: return null
        val previous = session.mutex.withLock { session.status.wire }
        session.job?.cancel()
        session.mutex.withLock {
            session.status = SubagentStatus.CLOSED
            session.outcome = "cancelled"
        }
        return previous
    }

    /** 会话被删除时终止它名下的全部子代理 */
    fun closeConversation(conversationId: Uuid) {
        sessions.values
            .filter { it.conversationId == conversationId }
            .forEach { session ->
                session.job?.cancel()
                session.status = SubagentStatus.CLOSED
                sessions.remove(session.id)
            }
    }

    private fun startRun(session: SubagentSession) {
        session.status = SubagentStatus.RUNNING
        session.outcome = "running"
        session.startedAt = System.currentTimeMillis()
        session.completion = CompletableDeferred()
        session.job = scope.launch { runSession(session) }
    }

    private suspend fun runSession(session: SubagentSession) {
        var finalMessage: UIMessage? = null
        var finishReason: AgentFinishReason? = null
        var timedOut = false
        var budgetExceeded = false
        var failure: Exception? = null
        var cancelled = false
        val request = AgentRequest(
            provider = session.provider,
            providerSetting = session.providerSetting,
            messages = session.messages,
            tools = session.tools,
            stream = false,
            maxSteps = session.maxSteps,
            retry = session.retry,
        )
        val hooks = object : AgentHooks {
            override suspend fun prepareStep(messages: List<UIMessage>) =
                StepRequest(messages, session.params)

            // 主代理发来的消息在下一步请求模型前追加到消息末尾, 模型看到后就能改方向
            override suspend fun pendingParts(): List<UIMessagePart> {
                val parts = mutableListOf<UIMessagePart>()
                while (true) {
                    parts += session.mailbox.tryReceive().getOrNull() ?: break
                }
                return parts
            }
        }
        try {
            withTimeout(session.timeoutSeconds * 1_000L) {
                runAgent(request, hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> {
                            finalMessage = event.message
                            session.tokens = maxOf(session.tokens, event.message.tokenCount())
                            if (session.tokens > session.maxTotalTokens) {
                                throw SubagentTokenBudgetExceededException()
                            }
                        }

                        is AgentEvent.Finished -> {
                            // 已经结束的运行不再检查预算: 最后一步的回复本身就是结果
                            finalMessage = event.message
                            finishReason = event.reason
                            session.tokens = maxOf(session.tokens, event.message.tokenCount())
                        }

                        is AgentEvent.Retrying -> Unit
                    }
                }
            }
        } catch (e: SubagentTokenBudgetExceededException) {
            budgetExceeded = true
        } catch (e: TimeoutCancellationException) {
            timedOut = true
        } catch (e: CancellationException) {
            // 被 close 或会话删除取消: 先把状态收好, 再把取消向上传
            cancelled = true
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            val outcome = when {
                cancelled -> "cancelled"
                timedOut -> "timeout"
                budgetExceeded -> "token_limit_reached"
                failure != null -> "failed"
                finishReason == AgentFinishReason.StepLimitReached -> "step_limit_reached"
                else -> "completed"
            }
            // 取消后普通挂起函数会立刻抛出, 收尾必须放在 NonCancellable 里
            withContext(NonCancellable) {
                session.mutex.withLock {
                    finalMessage?.takeIf { it.role == MessageRole.ASSISTANT }?.let { message ->
                        session.messages = session.messages + message
                    }
                    session.report = finalMessage?.subagentReport().orEmpty()
                    finalMessage?.stepCount?.let { session.steps = it }
                    if (session.status != SubagentStatus.CLOSED) {
                        session.status = if (cancelled) SubagentStatus.CLOSED else SubagentStatus.IDLE
                    }
                    session.outcome = outcome
                    session.completion?.complete(Unit)
                    session.completion = null
                }
                if (!cancelled) notifyFinished(session)
            }
        }
    }

    private fun notifyFinished(session: SubagentSession) {
        val report = session.report
        val preview = report.take(NOTICE_PREVIEW_CHARS).ifBlank { "(no report)" }
        val suffix = if (report.length > NOTICE_PREVIEW_CHARS) {
            "\n\nCall subagent_wait with agent_id \"${session.id}\" for the full report."
        } else {
            ""
        }
        val notice = UIMessagePart.Notice(
            title = "Subagent \"${session.description}\" ${session.outcome}",
            text = preview + suffix,
        )
        val emitted = _noticeEvents.tryEmit(
            SubagentNoticeEvent.Posted(
                conversationId = session.conversationId,
                key = "subagent-${session.id}",
                notice = notice,
            )
        )
        if (!emitted) Log.w(TAG, "notifyFinished: dropped notice for ${session.id}")
    }
}
