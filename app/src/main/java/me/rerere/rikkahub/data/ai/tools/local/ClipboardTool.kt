package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.readClipboardText
import me.rerere.rikkahub.utils.writeClipboardText

internal fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = """
        Read or write plain text from the device clipboard.
        Use action: read or write. For write, provide text.
        Do NOT write to the clipboard unless the user has explicitly requested it.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("read")
                            add("write")
                        }
                    )
                    put("description", "Operation to perform: read or write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to write to the clipboard (required for write)")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        when (action) {
            "read" -> {
                val payload = buildJsonObject {
                    put("text", context.readClipboardText())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "write" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                context.writeClipboardText(text)
                // 写操作回读校验: 系统可能限制后台读取剪贴板, 读不到时只报告未验证
                val readBack = runCatching { context.readClipboardText() }.getOrNull()
                val verified = readBack == text
                val payload = buildJsonObject {
                    put("success", true)
                    put("verified", verified)
                    put("text", text)
                    if (!verified) {
                        put(
                            "warning",
                            if (readBack == null) {
                                "The clipboard could not be read back to verify the write."
                            } else {
                                "The clipboard content read back does not match the text that was written."
                            }
                        )
                    }
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> error("unknown action: $action, must be one of [read, write]")
        }
    }
)
