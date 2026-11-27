package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CheckList
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Circle
import me.rerere.hugeicons.stroke.Loading03
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.TODO_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.TodoItem
import me.rerere.rikkahub.data.ai.tools.TodoStatus

/**
 * 任务清单: 标题显示完成进度，摘要显示当前步骤，详情列出完整计划。
 */
object TodoToolUI : ToolUIRenderer {
    override val toolName: String = TODO_TOOL_NAME

    override fun icon(context: ToolUIContext): ImageVector = HugeIcons.CheckList

    @Composable
    override fun title(context: ToolUIContext): String {
        val items = context.todoItems
        if (items.isNullOrEmpty()) return stringResource(R.string.tool_ui_todo)
        return stringResource(
            R.string.tool_ui_todo_title,
            items.count { it.status == TodoStatus.COMPLETED },
            items.size,
        )
    }

    override fun hasSummary(context: ToolUIContext): Boolean = !context.todoItems.isNullOrEmpty()

    @Composable
    override fun Summary(context: ToolUIContext) {
        val items = context.todoItems
        if (items.isNullOrEmpty()) return
        val current = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
        Text(
            text = when {
                current != null -> stringResource(R.string.tool_ui_todo_current, current.activeForm ?: current.content)
                items.all { it.status == TodoStatus.COMPLETED } -> stringResource(R.string.tool_ui_todo_all_done)
                else -> stringResource(R.string.tool_ui_todo_status_pending)
            },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val items = context.todoItems
        if (items == null) {
            DefaultToolPreview(context = context)
            return
        }
        Column(
            modifier = Modifier
                .fillMaxHeight(0.8f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.tool_ui_todo),
                style = MaterialTheme.typography.headlineSmall,
            )
            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_ui_todo_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item -> TodoRow(item) }
                }
            }
        }
    }

    @Composable
    private fun TodoRow(item: TodoItem) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val (icon, color) = when (item.status) {
                TodoStatus.COMPLETED -> HugeIcons.CheckmarkCircle02 to MaterialTheme.colorScheme.primary
                TodoStatus.IN_PROGRESS -> HugeIcons.Loading03 to MaterialTheme.colorScheme.primary
                TodoStatus.PENDING -> HugeIcons.Circle to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(
                imageVector = icon,
                contentDescription = stringResource(
                    when (item.status) {
                        TodoStatus.COMPLETED -> R.string.tool_ui_todo_status_completed
                        TodoStatus.IN_PROGRESS -> R.string.tool_ui_todo_status_in_progress
                        TodoStatus.PENDING -> R.string.tool_ui_todo_status_pending
                    }
                ),
                tint = color,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = item.content,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.status == TodoStatus.COMPLETED) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                textDecoration = if (item.status == TodoStatus.COMPLETED) TextDecoration.LineThrough else null,
            )
        }
    }
}
