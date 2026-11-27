package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PdfParser
import me.rerere.document.PptxParser
import me.rerere.document.XlsxParser
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceLayout
import java.io.File

private const val MAX_DOCUMENT_BYTES = 64L * 1024 * 1024
private const val DEFAULT_DOCUMENT_CHARS = 12_000
private const val MAX_DOCUMENT_CHARS = 60_000

// Binary document parsers can load the entire file and decoded streams at once.
private val documentParserDispatcher = Dispatchers.IO.limitedParallelism(2)

internal enum class DocumentFormat { PDF, DOCX, PPTX, XLSX, EPUB, TEXT, LEGACY_OFFICE, UNSUPPORTED }

private val TEXT_DOCUMENT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "html", "htm", "log", "ini", "toml",
)
private val LEGACY_OFFICE_EXTENSIONS = setOf("doc", "xls", "ppt")

internal fun documentFormatOf(path: String): DocumentFormat =
    when (path.substringAfterLast('.', "").lowercase()) {
        "pdf" -> DocumentFormat.PDF
        "docx" -> DocumentFormat.DOCX
        "pptx" -> DocumentFormat.PPTX
        "xlsx", "xlsm" -> DocumentFormat.XLSX
        "epub" -> DocumentFormat.EPUB
        in TEXT_DOCUMENT_EXTENSIONS -> DocumentFormat.TEXT
        in LEGACY_OFFICE_EXTENSIONS -> DocumentFormat.LEGACY_OFFICE
        else -> DocumentFormat.UNSUPPORTED
    }

internal data class WorkspaceTextPage(val json: String, val hasMore: Boolean)

/** Return the largest requested prefix that fits after JSON escaping and pagination metadata. */
internal fun workspaceTextPage(
    path: String,
    text: String,
    offset: Int,
    maxChars: Int,
    maxOutputChars: Int,
    format: DocumentFormat? = null,
): WorkspaceTextPage {
    fun beforePair(index: Int): Int =
        if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) {
            index - 1
        } else {
            index
        }

    val start = beforePair(offset.coerceIn(0, text.length))
    fun encode(end: Int, content: String): String = buildJsonObject {
        put("path", path)
        format?.let { put("format", it.name.lowercase()) }
        put("offset", start)
        put("total_chars", text.length)
        put("returned_chars", end - start)
        put("truncated", end < text.length)
        put("at_end", start == text.length)
        if (end < text.length) put("next_offset", end)
        if (start == text.length) {
            put("note", "offset is at or past the end; there is nothing left to read")
        }
        put("text", content)
    }.toString()

    if (start == text.length) {
        val json = encode(start, "")
        require(json.length <= maxOutputChars) { PAGE_BUDGET_ERROR }
        return WorkspaceTextPage(json, hasMore = false)
    }
    val header = encode(start, "").length

    // A one-unit request starting at a surrogate pair must still make progress.
    val firstEnd = start + Character.charCount(text.codePointAt(start))
    val requestedEnd = beforePair(start + minOf(maxChars, text.length - start)).coerceAtLeast(firstEnd)
    // Derive fixed overhead from the serializer. Only the two decimal fields grow within a page.
    val continuationBudget = maxOutputChars - (header - 1 - start.decimalLength())
    val finalBudget = if (requestedEnd == text.length) maxOutputChars - encode(requestedEnd, "").length else -1
    var escapedLength = 0
    var end = start
    var bestEnd = start
    while (end < requestedEnd) {
        val width = Character.charCount(text.codePointAt(end))
        escapedLength += if (width == 2) 2 else text[end].jsonEscapedLength()
        end += width
        val budget = if (end == text.length) finalBudget
        else continuationBudget - (end - start).decimalLength() - end.decimalLength()
        if (escapedLength <= budget) bestEnd = end
        // The final page can fit even after a continuation stops fitting, as it has no next_offset.
        if (escapedLength > budget && escapedLength > finalBudget) break
    }
    require(bestEnd >= firstEnd) { PAGE_BUDGET_ERROR }
    return WorkspaceTextPage(encode(bestEnd, text.substring(start, bestEnd)), hasMore = bestEnd < text.length)
}

private const val PAGE_BUDGET_ERROR = "Workspace read path leaves too little room for a page of text"

// Length in the compact kotlinx.serialization JSON output, excluding the surrounding quotes.
private fun Char.jsonEscapedLength(): Int = when (this) {
    '\\', '"', '\b', '\t', '\n', '\u000C', '\r' -> 2
    in '\u0000'..'\u001F' -> 6
    else -> 1
}

private fun Int.decimalLength(): Int = when {
    this < 10 -> 1
    this < 100 -> 2
    this < 1_000 -> 3
    this < 10_000 -> 4
    this < 100_000 -> 5
    this < 1_000_000 -> 6
    this < 10_000_000 -> 7
    this < 100_000_000 -> 8
    this < 1_000_000_000 -> 9
    else -> 10
}

/**
 * 按路径读取工作区里的常见文档。
 *
 * PDF/DOCX/PPTX/XLSX/EPUB 复用 document 模块的解析器; 纯文本文件直接按 UTF-8 读取。
 * 本地工作区直接用宿主机文件解析, 远程工作区先导出到缓存目录的临时文件, 解析后删除。
 */
internal fun createReadDocumentTool(
    workspaceId: String,
    layout: WorkspaceLayout,
    workspaceRepository: WorkspaceRepository,
    tempDir: File,
): Tool = Tool(
    name = "workspace_read_document",
    description = """
        Read a document inside the assistant's bound workspace and return its extracted text.
        Supports PDF, DOCX (Word), PPTX (PowerPoint), XLSX/XLSM (Excel), EPUB, and plain-text formats
        such as CSV, Markdown or JSON. Paths must be absolute; use ${layout.workspaceDir} for the workspace files area.
        Long documents are returned in pages. Pass next_offset as offset while truncated is true.
        The JSON size limit can shorten a page. at_end is true if offset is at or past the end.
        Legacy binary Office files (.doc, .xls, .ppt) are not supported; ask the user to save them in the newer format.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Start offset in UTF-16 code units (default 0). Offsets inside a pair round down.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Requested page length in UTF-16 code units. Default $DEFAULT_DOCUMENT_CHARS, max $MAX_DOCUMENT_CHARS."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val offset = params.workspaceReadOffset()
        val maxChars = (params.string("max_chars")?.toIntOrNull() ?: DEFAULT_DOCUMENT_CHARS)
            .coerceIn(1, MAX_DOCUMENT_CHARS)
        val format = documentFormatOf(path)
        val text = when (format) {
            DocumentFormat.PDF -> readParsedDocument(workspaceRepository, workspaceId, path, tempDir, "pdf") {
                PdfParser.parserPdf(it)
            }

            DocumentFormat.DOCX -> readParsedDocument(workspaceRepository, workspaceId, path, tempDir, "docx") {
                DocxParser.parse(it)
            }

            DocumentFormat.PPTX -> readParsedDocument(workspaceRepository, workspaceId, path, tempDir, "pptx") {
                PptxParser.parse(it)
            }

            DocumentFormat.XLSX -> readParsedDocument(workspaceRepository, workspaceId, path, tempDir, "xlsx") {
                XlsxParser.parse(it)
            }

            DocumentFormat.EPUB -> readParsedDocument(workspaceRepository, workspaceId, path, tempDir, "epub") {
                EpubParser.parse(it)
            }

            DocumentFormat.TEXT -> workspaceRepository.readTextInRootfs(workspaceId, path)
            DocumentFormat.LEGACY_OFFICE -> error(
                "Legacy Office format is not supported: $path. Ask the user to save it as .docx, .xlsx or .pptx."
            )

            DocumentFormat.UNSUPPORTED -> error(
                "Unsupported document format: $path. Supported: pdf, docx, pptx, xlsx, epub and plain text."
            )
        }
        val page = workspaceTextPage(path, text, offset, maxChars, workspaceReadOutputLimit(), format)
        listOf(UIMessagePart.Text(page.json))
    },
)

private suspend fun readParsedDocument(
    repository: WorkspaceRepository,
    workspaceId: String,
    path: String,
    tempDir: File,
    extension: String,
    parse: (File) -> String,
): String = withContext(Dispatchers.IO) {
    val limitMessage = "Document is too large to read: $path (max ${MAX_DOCUMENT_BYTES / 1024 / 1024}MB)"
    repository.resolveAbsoluteFile(workspaceId, path)?.let { file ->
        require(file.length() <= MAX_DOCUMENT_BYTES) { limitMessage }
        return@withContext withContext(documentParserDispatcher) { parse(file) }
    }

    tempDir.mkdirs()
    val temp = File.createTempFile("rikkahub-doc-", ".$extension", tempDir)
    try {
        temp.outputStream().use { repository.exportBoundedRead(workspaceId, path, it, MAX_DOCUMENT_BYTES, limitMessage) }
        withContext(documentParserDispatcher) { parse(temp) }
    } finally {
        temp.delete()
    }
}
