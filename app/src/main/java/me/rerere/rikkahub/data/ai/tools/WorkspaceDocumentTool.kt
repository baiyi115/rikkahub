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

/** Return the largest requested prefix that fits after JSON escaping and pagination metadata. */
internal fun workspaceTextPage(
    path: String,
    text: String,
    offset: Int,
    maxChars: Int,
    maxOutputChars: Int,
    format: DocumentFormat? = null,
): String {
    fun beforePair(index: Int): Int =
        if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) {
            index - 1
        } else {
            index
        }

    val start = beforePair(offset.coerceIn(0, text.length))
    fun encode(end: Int): String = buildJsonObject {
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
        put("text", text.substring(start, end))
    }.toString()

    // A one-unit request starting at a surrogate pair must still make progress.
    val firstEnd = if (start == text.length) start else start + Character.charCount(text.codePointAt(start))
    val requestedEnd = beforePair(start + minOf(maxChars, text.length - start)).coerceAtLeast(firstEnd)
    val full = encode(requestedEnd)
    if (full.length <= maxOutputChars) return full

    var best = encode(firstEnd)
    require(best.length <= maxOutputChars) { "Workspace read path leaves too little room for a page of text" }
    var lower = firstEnd + 1
    var upper = requestedEnd - 1
    // The full candidate was already tried. All remaining candidates have continuation metadata.
    while (lower <= upper) {
        val mid = lower + (upper - lower) / 2
        val candidate = encode(beforePair(mid))
        if (candidate.length <= maxOutputChars) {
            best = candidate
            lower = mid + 1
        } else {
            upper = mid - 1
        }
    }
    return best
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
        listOf(UIMessagePart.Text(page))
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
        return@withContext parse(file)
    }

    tempDir.mkdirs()
    val temp = File.createTempFile("rikkahub-doc-", ".$extension", tempDir)
    try {
        temp.outputStream().use { repository.exportBoundedRead(workspaceId, path, it, MAX_DOCUMENT_BYTES, limitMessage) }
        parse(temp)
    } finally {
        temp.delete()
    }
}
