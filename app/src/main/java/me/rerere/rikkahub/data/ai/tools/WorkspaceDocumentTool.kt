package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** 长文档按字符切片返回, 让模型用 offset 继续读, 也不会一次塞满上下文 */
internal fun documentSlice(text: String, offset: Int, maxChars: Int): String {
    val start = offset.coerceIn(0, text.length)
    return text.substring(start, (start + maxChars).coerceAtMost(text.length))
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
        Long documents are returned in slices: continue with offset while returned_chars is smaller than total_chars.
        at_end is true once the offset reaches the end of the document; there is nothing more to read then.
        Legacy binary Office files (.doc, .xls, .ppt) are not supported; ask the user to save them in the newer format.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty(layout, required = true)
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Start character offset in the extracted text. Defaults to 0.")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put(
                        "description",
                        "Characters to return. Default $DEFAULT_DOCUMENT_CHARS, max $MAX_DOCUMENT_CHARS."
                    )
                })
            },
            required = listOf("path"),
        )
    },
    execute = {
        val params = it.jsonObject
        val path = params.absolutePath("path")
        val offset = (params.string("offset")?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val maxChars = (params.string("max_chars")?.toIntOrNull() ?: DEFAULT_DOCUMENT_CHARS)
            .coerceIn(256, MAX_DOCUMENT_CHARS)
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
        val start = offset.coerceIn(0, text.length)
        val slice = documentSlice(text, start, maxChars)
        val payload = buildJsonObject {
            put("path", path)
            put("format", format.name.lowercase())
            put("offset", start)
            put("total_chars", text.length)
            put("returned_chars", slice.length)
            put("truncated", start + slice.length < text.length)
            // 和 workspace_read_file 一致: 越过末尾时明确说读完了
            put("at_end", start >= text.length)
            if (start >= text.length) {
                put("note", "offset is at or past the end of the document; there is nothing left to read")
            }
            put("text", slice)
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)

private suspend fun readParsedDocument(
    repository: WorkspaceRepository,
    workspaceId: String,
    path: String,
    tempDir: File,
    extension: String,
    parse: (File) -> String,
): String {
    val size = repository.absoluteFileSize(workspaceId, path)
    require(size <= MAX_DOCUMENT_BYTES) {
        "Document is too large to read: $path (${size / 1024 / 1024}MB, max ${MAX_DOCUMENT_BYTES / 1024 / 1024}MB)"
    }
    repository.resolveAbsoluteFile(workspaceId, path)?.let { return parse(it) }

    tempDir.mkdirs()
    val temp = File.createTempFile("rikkahub-doc-", ".$extension", tempDir)
    return try {
        temp.outputStream().use { repository.exportAbsoluteFile(workspaceId, path, it) }
        parse(temp)
    } finally {
        temp.delete()
    }
}
