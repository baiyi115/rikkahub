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
    fun `reads the active form of the current step`() {
        val items = parseTodoItems(
            buildJsonObject {
                put("items", buildJsonArray {
                    add(
                        buildJsonObject {
                            put("content", "run tests")
                            put("status", "in_progress")
                            put("active_form", "Running tests")
                        }
                    )
                })
            }
        )

        assertEquals("Running tests", items?.single()?.activeForm)
    }

    @Test
    fun `rejects unknown status and blank content`() {
        assertNull(parseTodoItems(args("read the file" to "started")))
        assertNull(parseTodoItems(args("  " to "pending")))
        assertNull(parseTodoItems(buildJsonObject { put("items", "not an array") }))
    }

    @Test
    fun `an empty list clears the plan`() = runBlocking {
        val output = buildTodoTool().execute(args())

        val payload = JsonInstant.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("0", payload["total"]?.jsonPrimitive?.content)
    }

    @Test
    fun `only the first in progress item stays in progress`() = runBlocking {
        val output = buildTodoTool().execute(
            args(
                "first" to "in_progress",
                "second" to "in_progress",
                "third" to "pending",
            )
        )

        val payload = JsonInstant.parseToJsonElement((output.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("1", payload["in_progress"]?.jsonPrimitive?.content)
        assertEquals("2", payload["pending"]?.jsonPrimitive?.content)
        assertEquals("first", payload["current"]?.jsonPrimitive?.content)
        assertTrue(payload["note"] != null)
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
