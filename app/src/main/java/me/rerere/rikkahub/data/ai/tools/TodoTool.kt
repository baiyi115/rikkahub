package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonArrayOrNull
import me.rerere.common.http.jsonObjectOrNull

internal const val TODO_TOOL_NAME = "todo_write"

private const val MAX_TODO_ITEMS = 20
private const val MAX_TODO_ITEM_CHARS = 200

internal enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

internal data class TodoItem(
    val content: String,
    val status: TodoStatus,
    /** 进行中时展示的说法，例如 "Running tests"；模型不填时退回 [content] */
    val activeForm: String? = null,
)

private fun todoStatusOf(raw: String?): TodoStatus? =
    when (raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')) {
        "pending", "todo" -> TodoStatus.PENDING
        "in_progress", "doing", "active" -> TodoStatus.IN_PROGRESS
        "completed", "complete", "done" -> TodoStatus.COMPLETED
        else -> null
    }

/**
 * 解析模型传入的清单，非法内容返回 null。
 *
 * 工具执行和界面渲染共用这一份解析，避免两边对格式的判断不一致。
 */
internal fun parseTodoItems(args: JsonElement): List<TodoItem>? {
    val array = args.jsonObjectOrNull?.get("items")?.jsonArrayOrNull ?: return null
    val items = ArrayList<TodoItem>(array.size)
    for (element in array) {
        val item = element as? JsonObject ?: return null
        val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (content.isEmpty()) return null
        val status = todoStatusOf((item["status"] as? JsonPrimitive)?.contentOrNull) ?: return null
        // 兼容 Claude Code 的 activeForm 写法和我们自己文档里的 active_form
        val activeForm = ((item["active_form"] ?: item["activeForm"]) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        items += TodoItem(content, status, activeForm)
    }
    return items
}

/** 解析失败时指出第一个有问题的条目，模型照着报错就能改对，不用猜格式 */
private fun describeTodoArgs(args: JsonElement): String {
    val array = args.jsonObjectOrNull?.get("items")?.jsonArrayOrNull
        ?: return "items must be an array of {content, status}"
    array.forEachIndexed { index, element ->
        val item = element as? JsonObject
            ?: return "item ${index + 1} must be an object with content and status"
        if (((item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()).isEmpty()) {
            return "item ${index + 1} needs a non-empty content"
        }
        val raw = (item["status"] as? JsonPrimitive)?.contentOrNull
        if (todoStatusOf(raw) == null) {
            return "item ${index + 1} has unknown status \"${raw.orEmpty()}\"; " +
                "use pending, in_progress or completed"
        }
    }
    return "items must be an array of {content, status}"
}

/** 计划里最多允许一个进行中的条目；多出来的降级为待办，并在结果里说明 */
private fun normalizeTodoItems(items: List<TodoItem>): Pair<List<TodoItem>, Boolean> {
    var seenInProgress = false
    var adjusted = false
    val normalized = items.map { item ->
        if (item.status != TodoStatus.IN_PROGRESS) return@map item
        if (seenInProgress) {
            adjusted = true
            item.copy(status = TodoStatus.PENDING)
        } else {
            seenInProgress = true
            item
        }
    }
    return normalized to adjusted
}

/**
 * 任务清单工具：模型用它写下多步计划，并在执行过程中更新。
 *
 * 每次调用替换整份清单，状态保存在工具调用里，因此会随对话一起持久化；
 * 界面按最后一次调用渲染，不需要额外的存储。
 */
internal fun buildTodoTool(): Tool = Tool(
    name = TODO_TOOL_NAME,
    description = """
        Maintain a short checklist for the task you are working on.
        Call it when a request needs several steps: write the plan before starting, then send the whole list again
        whenever a step starts or finishes. Each call replaces the previous list.
        Keep exactly one item in_progress, and mark items completed as soon as they are done.
        Give the in-progress item an active_form with present-continuous wording ("Running tests").
        The list is shown to the user, so keep items short and concrete. Skip it for single-step requests.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("items", buildJsonObject {
                    put("type", "array")
                    put("description", "The complete plan in order; replaces the previous list. Empty clears the plan.")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "One short, concrete step.")
                            })
                            put("status", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add("pending")
                                    add("in_progress")
                                    add("completed")
                                })
                            })
                            put("active_form", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Present-continuous wording shown while the item is in progress, " +
                                        "for example \"Running tests\". Defaults to content."
                                )
                            })
                        })
                        put("required", buildJsonArray {
                            add("content")
                            add("status")
                        })
                    })
                })
            },
            required = listOf("items"),
        )
    },
    systemPrompt = { _, _ ->
        "**Plan**\n" +
            "For work that needs several steps, write a plan with `todo_write` before starting, keep exactly one " +
            "item in_progress, and mark each item completed as soon as it is done. The user sees this list."
    },
    execute = { args ->
        val items = parseTodoItems(args) ?: error(describeTodoArgs(args))
        require(items.size <= MAX_TODO_ITEMS) {
            "too many items (${items.size}, max $MAX_TODO_ITEMS)"
        }
        val tooLong = items.firstOrNull { it.content.length > MAX_TODO_ITEM_CHARS }
        if (tooLong != null) {
            error(
                "item is too long (${tooLong.content.length} characters, max $MAX_TODO_ITEM_CHARS): " +
                    "${tooLong.content.take(40)}..."
            )
        }
        val (normalized, adjusted) = normalizeTodoItems(items)
        val payload = buildJsonObject {
            put("ok", true)
            put("total", normalized.size)
            put("completed", normalized.count { it.status == TodoStatus.COMPLETED })
            put("pending", normalized.count { it.status == TodoStatus.PENDING })
            put("in_progress", normalized.count { it.status == TodoStatus.IN_PROGRESS })
            normalized.firstOrNull { it.status == TodoStatus.IN_PROGRESS }?.let {
                put("current", it.activeForm ?: it.content)
            }
            if (adjusted) {
                put("note", "Only one item can be in_progress; the others were changed to pending.")
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
