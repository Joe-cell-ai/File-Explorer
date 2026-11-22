package com.joecell.fileexplorer

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

private const val MAX_OFFICE_XML_ENTRY = 2 * 1024 * 1024
private const val MAX_OFFICE_TOTAL_XML = 8 * 1024 * 1024
private const val MAX_OFFICE_PREVIEW_CHARS = 24000

internal fun officeXmlText(xml: String): String {
    val textPattern = Regex("<(?:[A-Za-z0-9_]+:)?t\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?t>", setOf(RegexOption.DOT_MATCHES_ALL))
    return textPattern.findAll(xml).joinToString(" ") { decodeXml(it.groupValues[1]) }
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun buildOpenXmlPreview(fileName: String, entries: Map<String, String>): String {
    val name = fileName.lowercase()
    val output = StringBuilder()
    when {
        name.endsWith(".docx") -> {
            val xml = entries["word/document.xml"] ?: return "This Word document has no readable document body."
            Regex("<(?:[A-Za-z0-9_]+:)?p\\b[^>]*>(.*?)</(?:[A-Za-z0-9_]+:)?p>", setOf(RegexOption.DOT_MATCHES_ALL))
                .findAll(xml).forEach { paragraph ->
                    val line = officeXmlText(paragraph.value)
                    if (line.isNotBlank()) output.append(line).append('\n')
                }
            if (output.isEmpty()) output.append(officeXmlText(xml))
        }
        name.endsWith(".pptx") -> {
            val slides = entries.keys.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { Regex("slide(\\d+)\\.xml$").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            slides.forEachIndexed { index, path ->
                output.append("Slide ").append(index + 1).append('\n')
                output.append(officeXmlText(entries[path].orEmpty())).append("\n\n")
            }
        }
        name.endsWith(".xlsx") -> {
            val shared = entries["xl/sharedStrings.xml"]?.let { xml ->
                Regex("<si\\b[^>]*>(.*?)</si>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(xml).map { officeXmlText(it.value) }.toList()
            }.orEmpty()
            val sheets = entries.keys.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sortedBy { Regex("sheet(\\d+)\\.xml$").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }
            sheets.forEach { path ->
                val sheetNumber = Regex("sheet(\\d+)\\.xml$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                output.append("Sheet ").append(sheetNumber).append('\n')
                val xml = entries[path].orEmpty()
                Regex("<row\\b[^>]*>(.*?)</row>", setOf(RegexOption.DOT_MATCHES_ALL))
                    .findAll(xml).forEach { row ->
                        val cells = Regex("<c\\b([^>]*)>(.*?)</c>", setOf(RegexOption.DOT_MATCHES_ALL))
                            .findAll(row.groupValues[1]).mapNotNull { cell ->
                                val attrs = cell.groupValues[1]
                                val ref = Regex("\\br=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                                val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                                val body = cell.groupValues[2]
                                val raw = Regex("<v\\b[^>]*>(.*?)</v>", setOf(RegexOption.DOT_MATCHES_ALL))
                                    .find(body)?.groupValues?.get(1)?.let(::decodeXml)
                                    ?: officeXmlText(body)
                                val value = if (type == "s") shared.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
                                if (value.isBlank()) null else if (ref == null) value else "$ref: $value"
                            }.toList()
                        if (cells.isNotEmpty()) output.append(cells.joinToString(" | ")).append('\n')
                    }
                output.append('\n')
            }
        }
        else -> return "Preview is not available for this Office format."
    }
    val result = output.toString().trim()
    return when {
        result.isBlank() -> "No readable text was found in this document."
        result.length > MAX_OFFICE_PREVIEW_CHARS -> result.take(MAX_OFFICE_PREVIEW_CHARS) + "\n\nPreview truncated."
        else -> result
    }
}

fun readOfficePreview(context: Context, uri: Uri, fileName: String): String {
    val input = context.contentResolver.openInputStream(uri) ?: return "Cannot open this Office document."
    val wanted = when {
        fileName.endsWith(".docx", true) -> setOf("word/document.xml")
        fileName.endsWith(".pptx", true) -> emptySet()
        fileName.endsWith(".xlsx", true) -> setOf("xl/sharedStrings.xml")
        else -> return "Preview is supported for DOCX, XLSX and PPTX files."
    }
    val entries = linkedMapOf<String, String>()
    var total = 0
    ZipInputStream(input.buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val relevant = entry.name in wanted ||
                (fileName.endsWith(".pptx", true) && entry.name.matches(Regex("ppt/slides/slide\\d+\\.xml"))) ||
                (fileName.endsWith(".xlsx", true) && entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")))
            if (relevant && !entry.isDirectory) {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var entryBytes = 0
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count
                    total += count
                    if (entryBytes > MAX_OFFICE_XML_ENTRY || total > MAX_OFFICE_TOTAL_XML) {
                        return "Office document preview stopped because the document XML is too large."
                    }
                    bytes.write(buffer, 0, count)
                }
                entries[entry.name] = bytes.toString(Charsets.UTF_8.name())
            }
            zip.closeEntry()
        }
    }
    return buildOpenXmlPreview(fileName, entries)
}

internal fun decodeXml(value: String): String = value
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&amp;", "&")
