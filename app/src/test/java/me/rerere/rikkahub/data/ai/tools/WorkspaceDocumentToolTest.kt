package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceDocumentToolTest {
    @Test
    fun `maps document extensions to formats`() {
        assertEquals(DocumentFormat.PDF, documentFormatOf("/workspace/a.PDF"))
        assertEquals(DocumentFormat.DOCX, documentFormatOf("/workspace/report.docx"))
        assertEquals(DocumentFormat.PPTX, documentFormatOf("/workspace/slides.pptx"))
        assertEquals(DocumentFormat.XLSX, documentFormatOf("/workspace/book.xlsx"))
        assertEquals(DocumentFormat.XLSX, documentFormatOf("/workspace/book.xlsm"))
        assertEquals(DocumentFormat.EPUB, documentFormatOf("/workspace/book.epub"))
        assertEquals(DocumentFormat.TEXT, documentFormatOf("/workspace/data.csv"))
        assertEquals(DocumentFormat.LEGACY_OFFICE, documentFormatOf("/workspace/old.doc"))
        assertEquals(DocumentFormat.UNSUPPORTED, documentFormatOf("/workspace/archive.zip"))
        assertEquals(DocumentFormat.UNSUPPORTED, documentFormatOf("/workspace/no-extension"))
    }

    @Test
    fun `slices document text and clamps the offset`() {
        fun page(offset: Int, maxChars: Int) = Json.parseToJsonElement(
            workspaceTextPage("/workspace/a.txt", "0123456789", offset, maxChars, 32 * 1024),
        ).jsonObject

        val partial = page(offset = 2, maxChars = 4)
        assertEquals("2345", partial.text)
        assertEquals(6, partial.getValue("next_offset").jsonPrimitive.int)
        val pastEnd = page(offset = 99, maxChars = 4)
        assertEquals("", pastEnd.text)
        assertEquals(10, pastEnd.getValue("offset").jsonPrimitive.int)
        assertTrue(pastEnd.getValue("at_end").jsonPrimitive.boolean)
        assertEquals("789", page(offset = 7, maxChars = 100).text)
    }

    @Test
    fun `preserves the requested text when it fits and avoids splitting a surrogate pair`() {
        val plain = "a".repeat(30_000)
        val full = workspaceTextPage("/workspace/a.txt", plain, 0, plain.length, 32 * 1024)
        assertEquals(plain, Json.parseToJsonElement(full).jsonObject.text)

        val oneUnit = workspaceTextPage("/workspace/a.txt", "a😀z", 2, 1, 16 * 1024)
        val page = Json.parseToJsonElement(oneUnit).jsonObject
        assertEquals("😀", page.text)
        assertEquals(1, page.getValue("offset").jsonPrimitive.int)
        assertEquals(3, page.getValue("next_offset").jsonPrimitive.int)
    }

    @Test
    fun `escaped pages fit both tool output limits and reconstruct the original text`() {
        val text = "\"\u0001\\\n😀".repeat(9_000)
        val path = "/workspace/quoted\"name.txt"
        for ((limit, format) in listOf(32 * 1024 to null, 16 * 1024 to DocumentFormat.TEXT)) {
            val recovered = StringBuilder()
            var offset = 0
            while (offset < text.length) {
                val encoded = workspaceTextPage(path, text, offset, 30_000, limit, format)
                assertTrue(encoded.length <= limit)
                val page = Json.parseToJsonElement(encoded).jsonObject
                assertEquals(path, page.getValue("path").jsonPrimitive.content)
                assertEquals(text.length, page.getValue("total_chars").jsonPrimitive.int)
                assertEquals(offset, page.getValue("offset").jsonPrimitive.int)
                val slice = page.text
                assertTrue(slice.isNotEmpty())
                assertFalse(slice.first().isLowSurrogate())
                assertFalse(slice.last().isHighSurrogate())
                assertEquals(slice.length, page.getValue("returned_chars").jsonPrimitive.int)
                recovered.append(slice)
                offset += slice.length
                assertEquals(offset < text.length, page.getValue("truncated").jsonPrimitive.boolean)
                assertEquals(offset.takeIf { it < text.length }, page["next_offset"]?.jsonPrimitive?.int)
            }
            assertEquals(text, recovered.toString())
        }
    }

    private val JsonObject.text: String get() = getValue("text").jsonPrimitive.content
}
