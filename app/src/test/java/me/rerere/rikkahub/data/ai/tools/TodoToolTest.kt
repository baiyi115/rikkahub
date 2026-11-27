package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolTest {
    @Test
    fun `parses items and normalizes status spelling`() {
        val items = parseTodoItems(
            args(
                "read the file" to "pending",
                "apply the change" to "in-progress",
                "run tests" to "done",
            )
        )

        assertEquals(
            listOf(
                TodoItem("read the file", TodoStatus.PENDING),
                TodoItem("apply the change", TodoStatus.IN_PROGRESS),
                TodoItem("run tests", TodoStatus.COMPLETED),
            ),
            items,
        )
    }

    @Test
    fun `retains the active form of the current step`() = runBlocking {
        val arguments = buildJsonObject {
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("content", "run tests")
                    put("status", "in_progress")
                    put("activeForm", "  Running tests  ")
                })
            })
        }
        val result = buildTodoTool().execute(arguments).single() as UIMessagePart.Text
        val payload = JsonInstant.parseToJsonElement(result.text).jsonObject

        assertEquals("Running tests", payload["current"]?.jsonPrimitive?.content)
        assertEquals("Running tests", resolveTodoItems(args(), payload, result.metadata)?.single()?.activeForm)
    }

    @Test
    fun `rejects unknown status and blank content`() {
        assertNull(parseTodoItems(args("read the file" to "started")))
        assertNull(parseTodoItems(args("  " to "pending")))
        assertNull(parseTodoItems(buildJsonObject { put("items", "not an array") }))
    }

    @Test
    fun `error names the offending item`() = runBlocking {
        val error = runCatching {
            buildTodoTool().execute(args("read the file" to "pending", "run tests" to "started"))
        }.exceptionOrNull()

        assertEquals(
            "item 2 has unknown status \"started\"; use pending, in_progress or completed",
            error?.message,
        )
    }

    @Test
    fun `an empty list clears the plan`() = runBlocking {
        val output = buildTodoTool().execute(args())

        val result = output.single() as UIMessagePart.Text
        val payload = JsonInstant.parseToJsonElement(result.text).jsonObject
        assertEquals("0", payload["total"]?.jsonPrimitive?.content)
        assertEquals(emptyList<TodoItem>(), resolveTodoItems(args("old step" to "pending"), payload, result.metadata))
    }

    @Test
    fun `only the first in progress item stays in progress in both result summaries and lists`() = runBlocking {
        val arguments = args("first" to "in_progress", "second" to "active", "third" to "pending")
        val result = buildTodoTool().execute(arguments).single() as UIMessagePart.Text
        val payload = JsonInstant.parseToJsonElement(result.text).jsonObject

        assertEquals("1", payload["in_progress"]?.jsonPrimitive?.content)
        assertEquals("2", payload["pending"]?.jsonPrimitive?.content)
        assertEquals("first", payload["current"]?.jsonPrimitive?.content)
        assertTrue(payload["note"] != null)

        val expected = listOf(
            TodoItem("first", TodoStatus.IN_PROGRESS),
            TodoItem("second", TodoStatus.PENDING),
            TodoItem("third", TodoStatus.PENDING),
        )
        assertEquals(expected, resolveTodoItems(args(), payload, result.metadata))
        assertEquals(expected, resolveTodoItems(arguments, payload, resultMetadata = null))
    }

    @Test
    fun `a failed call does not display requested steps as accepted`() {
        val arguments = args("done" to "completed")
        val payload = buildJsonObject { put("error", "Tool execution failed") }

        assertNull(resolveTodoItems(arguments, payload, resultMetadata = null))
    }

    @Test
    fun `rejects more items than the limit`() = runBlocking {
        val items = Array(21) { "step $it" to "pending" }
        val error = runCatching { buildTodoTool().execute(args(*items)) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun args(vararg items: Pair<String, String>): JsonElement = buildJsonObject {
        put("items", buildJsonArray {
            items.forEach { (content, status) ->
                add(
                    buildJsonObject {
                        put("content", content)
                        put("status", status)
                    }
                )
            }
        })
    }
}
