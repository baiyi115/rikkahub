package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.BackgroundShellTask

internal fun JsonObjectBuilder.putBackgroundShellTask(task: BackgroundShellTask) {
    put("taskId", task.id)
    put("status", task.status.name.lowercase())
    task.exitCode?.let { put("exitCode", it) }
    put("logPath", task.logPath)
}
