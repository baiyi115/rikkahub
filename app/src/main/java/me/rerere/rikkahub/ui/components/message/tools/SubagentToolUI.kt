package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain01
import me.rerere.rikkahub.R

/**
 * 子代理: 摘要直接展示报告，详情展示任务、状态、耗时、工具调用和完整结果。
 */
object SubagentToolUI : ToolUIRenderer {
    override val toolName: String = "subagent"

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.AiBrain01

    @Composable
    override fun title(context: ToolUIContext): String {
        val description = context.arguments.getStringContent("description")?.trim()
        if (!description.isNullOrBlank()) {
            return stringResource(R.string.tool_ui_subagent_task_title, description.take(60))
        }
        val task = context.arguments.getStringContent("task")
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        return if (task.isBlank()) {
            stringResource(R.string.tool_ui_subagent)
        } else {
            stringResource(R.string.tool_ui_subagent_task_title, task.take(60))
        }
    }

    override fun hasSummary(context: ToolUIContext): Boolean =
        context.loading || result(context) != null || errorMessage(context) != null

    @Composable
    override fun Summary(context: ToolUIContext) {
        val report = result(context)
        if (report == null) {
            if (context.loading) {
                Text(
                    text = stringResource(R.string.tool_ui_subagent_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // 调用本身失败时没有报告, 至少把错误显示出来
                ErrorLine(context)
            }
            return
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = report.take(SUMMARY_MAX_CHARS),
                style = MaterialTheme.typography.bodySmall,
                maxLines = SUMMARY_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            StatusLine(context, MaterialTheme.typography.labelSmall)
            MetaLine(context)
        }
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_ui_subagent),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.tool_ui_subagent_task),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = context.arguments.getStringContent("task").orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )
            StatusLine(context, style = MaterialTheme.typography.labelMedium)
            MetaLine(context)
            ErrorLine(context)
            val tools = toolTrace(context)
            if (tools.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_subagent_tools),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val notExecuted = stringResource(R.string.tool_ui_subagent_tool_not_executed)
                Text(
                    text = tools.joinToString("\n") { (name, executed) ->
                        if (executed) name else "$name ($notExecuted)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = stringResource(R.string.tool_ui_subagent_result),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = result(context)?.let { result ->
                        if (result.length > PREVIEW_MAX_CHARS) {
                            result.take(PREVIEW_MAX_CHARS) + "\n…"
                        } else {
                            result
                        }
                    } ?: stringResource(R.string.tool_ui_subagent_empty),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    @Composable
    private fun StatusLine(
        context: ToolUIContext,
        style: TextStyle,
    ) {
        status(context)?.let { status ->
            Text(
                text = stringResource(R.string.tool_ui_subagent_status, statusLabel(status)),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun MetaLine(context: ToolUIContext) {
        val stepCount = steps(context)
        val duration = durationSeconds(context)
        val tokenCount = tokens(context)
        if (stepCount == null && duration == null && tokenCount == null) return
        val stepsText = if (stepCount != null) stringResource(R.string.tool_ui_subagent_steps, stepCount) else null
        val durationText = if (duration != null) {
            stringResource(R.string.tool_ui_subagent_duration, duration)
        } else {
            null
        }
        val tokensText = if (tokenCount != null) {
            stringResource(R.string.tool_ui_subagent_tokens, tokenCount)
        } else {
            null
        }
        Text(
            text = listOfNotNull(stepsText, durationText, tokensText).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    @Composable
    private fun ErrorLine(context: ToolUIContext) {
        errorMessage(context)?.let { error ->
            Text(
                text = stringResource(R.string.tool_ui_subagent_error, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    @Composable
    private fun statusLabel(status: String): String = when (status) {
        "completed" -> stringResource(R.string.tool_ui_subagent_status_completed)
        "timeout" -> stringResource(R.string.tool_ui_subagent_status_timeout)
        "step_limit_reached" -> stringResource(R.string.tool_ui_subagent_status_step_limit)
        "token_limit_reached" -> stringResource(R.string.tool_ui_subagent_status_token_limit)
        "failed" -> stringResource(R.string.tool_ui_subagent_status_failed)
        else -> status
    }

    private fun result(context: ToolUIContext): String? =
        context.content.getStringContent("result")?.takeIf { it.isNotBlank() }

    private fun status(context: ToolUIContext): String? =
        context.content.getStringContent("status")?.takeIf { it.isNotBlank() }

    private fun steps(context: ToolUIContext): Int? =
        context.content.getStringContent("steps")?.toIntOrNull()

    private fun durationSeconds(context: ToolUIContext): Long? =
        context.content.getStringContent("duration_ms")
            ?.toLongOrNull()
            ?.div(1_000L)
            ?.coerceAtLeast(0L)

    private fun tokens(context: ToolUIContext): Int? =
        context.content.getStringContent("tokens")?.toIntOrNull()

    private fun errorMessage(context: ToolUIContext): String? =
        context.content.getStringContent("error")?.takeIf { it.isNotBlank() }

    private fun toolTrace(context: ToolUIContext): List<Pair<String, Boolean>> =
        context.content?.jsonObjectOrNull?.get("tools")?.jsonArrayOrNull?.mapNotNull { item ->
            val name = item.getStringContent("tool") ?: return@mapNotNull null
            name to (item.getStringContent("executed") != "false")
        }.orEmpty()

    private const val SUMMARY_MAX_CHARS = 600
    private const val SUMMARY_MAX_LINES = 8
    private const val PREVIEW_MAX_CHARS = 12_000
}
