package me.rerere.document

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * 轻量的 XLSX 读取器: 只提取单元格值, 不计算公式, 不保留样式。
 *
 * 输出按工作表分段, 每行一条, 单元格用制表符分隔, 便于模型阅读和检索。
 * 日期按单元格样式识别后转成 ISO 时间, 其余数值保持文件里的原始写法。
 */
object XlsxParser {
    private const val MAX_ROWS_PER_SHEET = 5_000
    private const val MAX_COLUMNS = 64
    private const val MAX_TOTAL_CHARS = 1_000_000

    /** Excel 内置的日期/时间格式编号 */
    private val BUILT_IN_DATE_FORMATS = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 45, 46, 47)

    fun parse(file: File): String = try {
        ZipFile(file).use { zip ->
            val sharedStrings = parseSharedStrings(zip)
            val dateStyles = parseDateStyles(zip)
            val relTargets = parseWorkbookRels(zip)
            val sheets = parseWorkbook(zip, relTargets)
            if (sheets.isEmpty()) {
                "No sheets found in XLSX file"
            } else {
                val result = StringBuilder()
                sheets.forEach { sheet ->
                    if (result.length >= MAX_TOTAL_CHARS) {
                        result.append("\n[Truncated: reached the output limit]\n")
                        return@forEach
                    }
                    val entry = zip.getEntry(sheet.entryPath) ?: return@forEach
                    result.append("## Sheet: ").append(sheet.name).append('\n')
                    val rows = zip.getInputStream(entry).use { parseSheet(it, sharedStrings, dateStyles) }
                    result.append(rows).append('\n')
                }
                result.toString().trim()
            }
        }
    } catch (e: Exception) {
        "Error parsing XLSX file: ${e.message}"
    }

    private data class SheetRef(val name: String, val entryPath: String)

    private fun parseXml(stream: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        runCatching {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        factory.newSAXParser().parse(stream, handler)
    }

    private fun parseSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val strings = mutableListOf<String>()
        zip.getInputStream(entry).use { stream ->
            parseXml(
                stream,
                object : DefaultHandler() {
                    private val current = StringBuilder()
                    private var inItem = false
                    private var inText = false

                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: Attributes?,
                    ) {
                        when (localName ?: qName) {
                            "si" -> {
                                inItem = true
                                current.setLength(0)
                            }

                            "t" -> if (inItem) inText = true
                        }
                    }

                    override fun characters(ch: CharArray, start: Int, length: Int) {
                        if (inItem && inText) current.append(ch, start, length)
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        when (localName ?: qName) {
                            "t" -> inText = false
                            "si" -> {
                                inItem = false
                                strings += current.toString()
                            }
                        }
                    }
                }
            )
        }
        return strings
    }

    /** 返回按日期格式显示的单元格样式下标 */
    private fun parseDateStyles(zip: ZipFile): Set<Int> {
        val entry = zip.getEntry("xl/styles.xml") ?: return emptySet()
        val customDateFormats = mutableSetOf<Int>()
        val styleFormats = mutableListOf<Int>()
        var inCellXfs = false
        zip.getInputStream(entry).use { stream ->
            parseXml(
                stream,
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: Attributes?,
                    ) {
                        when (localName ?: qName) {
                            "numFmt" -> {
                                val id = attributes?.getValue("numFmtId")?.toIntOrNull()
                                val code = attributes?.getValue("formatCode")
                                if (id != null && code != null && isDateFormatCode(code)) {
                                    customDateFormats += id
                                }
                            }

                            "cellXfs" -> inCellXfs = true
                            "xf" -> if (inCellXfs) {
                                styleFormats += attributes?.getValue("numFmtId")?.toIntOrNull() ?: 0
                            }
                        }
                    }

                    override fun endElement(uri: String?, localName: String?, qName: String?) {
                        if ((localName ?: qName) == "cellXfs") inCellXfs = false
                    }
                }
            )
        }
        val dateFormatIds = BUILT_IN_DATE_FORMATS + customDateFormats
        return styleFormats.withIndex()
            .filter { it.value in dateFormatIds }
            .map { it.index }
            .toSet()
    }

    private fun isDateFormatCode(code: String): Boolean {
        // 去掉引号里的字面量和转义字符, 剩下的占位符里出现日期/时间单位才按日期处理
        val stripped = code
            .replace(Regex("\"[^\"]*\""), "")
            .replace(Regex("\\\\."), "")
        return stripped.any { it in "ymdhs" } && !stripped.contains("General", ignoreCase = true)
    }

    private fun parseWorkbookRels(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("xl/_rels/workbook.xml.rels") ?: return emptyMap()
        val rels = mutableMapOf<String, String>()
        zip.getInputStream(entry).use { stream ->
            parseXml(
                stream,
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: Attributes?,
                    ) {
                        if ((localName ?: qName) != "Relationship") return
                        val id = attributes?.getValue("Id") ?: return
                        val target = attributes.getValue("Target") ?: return
                        rels[id] = normalizeZipPath("xl", target)
                    }
                }
            )
        }
        return rels
    }

    private fun parseWorkbook(zip: ZipFile, relTargets: Map<String, String>): List<SheetRef> {
        val entry = zip.getEntry("xl/workbook.xml") ?: return emptyList()
        val sheets = mutableListOf<SheetRef>()
        zip.getInputStream(entry).use { stream ->
            parseXml(
                stream,
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String?,
                        qName: String?,
                        attributes: Attributes?,
                    ) {
                        if ((localName ?: qName) != "sheet") return
                        val name = attributes?.getValue("name") ?: "Sheet ${sheets.size + 1}"
                        val relId = attributes?.let { attrs ->
                            (0 until attrs.length).firstNotNullOfOrNull { index ->
                                attrs.getLocalName(index)?.takeIf { it == "id" }?.let { attrs.getValue(index) }
                            }
                        }
                        val target = relId?.let(relTargets::get)
                            ?: "xl/worksheets/sheet${sheets.size + 1}.xml"
                        sheets += SheetRef(name, target)
                    }
                }
            )
        }
        return sheets
    }

    private fun normalizeZipPath(baseDir: String, target: String): String {
        val path = if (target.startsWith("/")) target.trimStart('/') else "$baseDir/$target"
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull()
                else -> segments.addLast(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun parseSheet(
        stream: InputStream,
        sharedStrings: List<String>,
        dateStyles: Set<Int>,
    ): String {
        val result = StringBuilder()
        parseXml(
            stream,
            object : DefaultHandler() {
                private val rowCells = sortedMapOf<Int, String>()
                private val value = StringBuilder()
                private val inlineText = StringBuilder()
                private var cellColumn = -1
                private var nextColumn = 0
                private var cellType: String? = null
                private var cellStyle = -1
                private var inValue = false
                private var inInlineText = false
                private var rowsWritten = 0
                private var truncated = false

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    when (localName ?: qName) {
                        "row" -> {
                            rowCells.clear()
                            nextColumn = 0
                        }

                        "c" -> {
                            cellColumn = columnOf(attributes?.getValue("r")).takeIf { it >= 0 } ?: nextColumn
                            nextColumn = cellColumn + 1
                            cellType = attributes?.getValue("t")
                            cellStyle = attributes?.getValue("s")?.toIntOrNull() ?: -1
                            value.setLength(0)
                            inlineText.setLength(0)
                        }

                        "v" -> inValue = true
                        "t" -> if (cellType == "inlineStr") inInlineText = true
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (inValue) {
                        value.append(ch, start, length)
                    } else if (inInlineText) {
                        inlineText.append(ch, start, length)
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    when (localName ?: qName) {
                        "v" -> inValue = false
                        "t" -> inInlineText = false
                        "c" -> {
                            val text = when (cellType) {
                                "s" -> sharedStrings.getOrNull(value.toString().trim().toIntOrNull() ?: -1).orEmpty()
                                "inlineStr" -> inlineText.toString()
                                "b" -> if (value.toString().trim() == "1") "TRUE" else "FALSE"
                                "str" -> value.toString()
                                "e" -> "#ERROR"
                                else -> formatNumberOrDate(value.toString(), cellStyle in dateStyles)
                            }
                            if (text.isNotEmpty() && cellColumn in 0 until MAX_COLUMNS) {
                                rowCells[cellColumn] = text
                            }
                        }

                        "row" -> {
                            if (rowCells.isNotEmpty()) {
                                if (rowsWritten >= MAX_ROWS_PER_SHEET) {
                                    truncated = true
                                } else {
                                    result.append(rowCells.values.joinToString("\t")).append('\n')
                                    rowsWritten++
                                }
                            }
                            rowCells.clear()
                        }
                    }
                }

                override fun endDocument() {
                    if (truncated) {
                        result.append("[Truncated: only the first $MAX_ROWS_PER_SHEET rows are shown]\n")
                    }
                }
            }
        )
        return result.toString()
    }

    private fun columnOf(reference: String?): Int {
        if (reference.isNullOrEmpty()) return -1
        var column = 0
        var index = 0
        while (index < reference.length && reference[index].isLetter()) {
            column = column * 26 + (reference[index].uppercaseChar() - 'A' + 1)
            index++
        }
        return column - 1
    }

    private fun formatNumberOrDate(raw: String, isDate: Boolean): String {
        val text = raw.trim()
        if (text.isEmpty() || !isDate) return text
        val serial = text.toDoubleOrNull() ?: return text
        return formatExcelSerial(serial)
    }

    private fun formatExcelSerial(serial: Double): String {
        if (serial <= 0.0) return serial.toString()
        val wholeDays = serial.toLong()
        val timeSeconds = Math.round((serial - wholeDays) * 86_400.0)
            .coerceIn(0L, 86_399L)
        if (wholeDays == 0L) {
            return LocalTime.ofSecondOfDay(timeSeconds).toString()
        }
        // Excel 的 1900 日期系统从 1899-12-30 起算 (已经包含 1900 闰年误差)
        val date = LocalDate.of(1899, 12, 30).plusDays(wholeDays)
        if (timeSeconds == 0L) return date.toString()
        return LocalDateTime.of(date, LocalTime.ofSecondOfDay(timeSeconds))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}
