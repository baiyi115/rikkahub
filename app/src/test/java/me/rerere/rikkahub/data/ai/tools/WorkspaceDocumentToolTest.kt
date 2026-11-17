package me.rerere.rikkahub.data.ai.tools

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
        val text = "0123456789"

        assertEquals("2345", documentSlice(text, offset = 2, maxChars = 4))
        assertEquals("", documentSlice(text, offset = 99, maxChars = 4))
        assertEquals("789", documentSlice(text, offset = 7, maxChars = 100))
    }

    @Test
    fun `slice never exceeds the remaining text`() {
        val slice = documentSlice("abc", offset = 0, maxChars = 10_000)

        assertEquals("abc", slice)
        assertFalse(slice.length > 3)
        assertTrue(documentSlice("abc", 3, 10).isEmpty())
    }
}
