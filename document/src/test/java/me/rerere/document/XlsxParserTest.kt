package me.rerere.document

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxParserTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun xlsx(entries: Map<String, String>): File {
        val file = temp.newFile("book.xlsx")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun workbook(extra: Map<String, String>): File = xlsx(
        mapOf(
            "xl/workbook.xml" to
                """<?xml version='1.0'?><workbook xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships'>""" +
                """<sheets><sheet name='Data' sheetId='1' r:id='rId1'/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to
                """<?xml version='1.0'?><Relationships>""" +
                """<Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/sheet1.xml'/>""" +
                """</Relationships>""",
        ) + extra
    )

    @Test
    fun `reads shared strings numbers booleans and inline strings`() {
        val file = workbook(
            mapOf(
                "xl/sharedStrings.xml" to
                    """<?xml version='1.0'?><sst><si><t>Name</t></si><si><t>Alice</t></si></sst>""",
                "xl/worksheets/sheet1.xml" to
                    """<?xml version='1.0'?><worksheet><sheetData>""" +
                    """<row r='1'><c r='A1' t='s'><v>0</v></c><c r='B1' t='s'><v>1</v></c></row>""" +
                    """<row r='2'><c r='A2'><v>42</v></c><c r='B2' t='b'><v>1</v></c>""" +
                    """<c r='C2' t='inlineStr'><is><t>Bob</t></is></c></row>""" +
                    """</sheetData></worksheet>""",
            )
        )

        val text = XlsxParser.parse(file)

        assertTrue(text, text.contains("## Sheet: Data"))
        assertTrue(text, text.contains("Name\tAlice"))
        assertTrue(text, text.contains("42\tTRUE\tBob"))
    }

    @Test
    fun `formats numeric cells with a date style`() {
        val file = workbook(
            mapOf(
                "xl/styles.xml" to
                    """<?xml version='1.0'?><styleSheet><cellXfs count='2'>""" +
                    """<xf numFmtId='0'/><xf numFmtId='14'/></cellXfs></styleSheet>""",
                "xl/worksheets/sheet1.xml" to
                    """<?xml version='1.0'?><worksheet><sheetData>""" +
                    """<row r='1'><c r='A1' s='1'><v>45306</v></c></row>""" +
                    """</sheetData></worksheet>""",
            )
        )

        val text = XlsxParser.parse(file)

        assertTrue(text, text.contains("2024-01-15"))
    }

    @Test
    fun `reports malformed archives instead of crashing`() {
        val file = temp.newFile("broken.xlsx").apply { writeText("not a zip") }

        assertTrue(XlsxParser.parse(file).startsWith("Error parsing XLSX file"))
    }
}
