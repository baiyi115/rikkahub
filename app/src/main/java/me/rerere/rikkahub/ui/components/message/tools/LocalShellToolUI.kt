package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LOCAL_SHELL_JOB_TOOL_NAME
import me.rerere.rikkahub.data.ai.tools.local.LOCAL_SHELL_TOOL_NAME

/** 本机 Shell 的输出结构与工作区 Shell 相同, 直接复用卡片和详情 */
object LocalShellToolUI : ToolUIRenderer by ShellToolUI {
    override val toolName: String = LOCAL_SHELL_TOOL_NAME

    @Composable
    override fun title(context: ToolUIContext): String {
        val isExec = (context.arguments.getStringContent("action") ?: "exec") == "exec"
        return if (isExec) {
            ShellToolUI.title(context)
        } else {
            stringResource(R.string.assistant_page_local_tools_local_shell_title)
        }
    }

    /** 会话管理类操作没有命令输出, 不显示退出状态 */
    override fun hasSummary(context: ToolUIContext): Boolean {
        val isExec = (context.arguments.getStringContent("action") ?: "exec") == "exec"
        return isExec && context.content != null
    }

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        if ((context.arguments.getStringContent("action") ?: "exec") == "exec") {
            ShellToolUI.Preview(context, onDismissRequest)
        } else {
            DefaultToolPreview(context = context)
        }
    }
}

object LocalShellJobToolUI : ToolUIRenderer by ShellToolUI {
    override val toolName: String = LOCAL_SHELL_JOB_TOOL_NAME

    /** list 的结果是任务列表, 没有单个任务的退出状态 */
    override fun hasSummary(context: ToolUIContext): Boolean =
        (context.arguments.getStringContent("action") ?: "start") != "list" && context.content != null

    @Composable
    override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        if ((context.arguments.getStringContent("action") ?: "start") == "list") {
            DefaultToolPreview(context = context)
        } else {
            ShellToolUI.Preview(context, onDismissRequest)
        }
    }
}
