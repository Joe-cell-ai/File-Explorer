package com.joecell.fileexplorer

import android.content.Context
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.StandardCharsets

internal data class TrashMetadata(
    val originalName: String,
    val parentSegments: List<String>
)

internal fun findParentSegments(root: DocumentFile, targetUri: android.net.Uri): List<String>? {
    val stack = ArrayDeque<Pair<DocumentFile, List<String>>>()
    stack.add(root to emptyList())
    var visited = 0
    while (stack.isNotEmpty() && visited < 10000) {
        val (folder, path) = stack.removeLast()
        if (!folder.isDirectory) continue
        for (child in folder.listFiles()) {
            visited++
            if (child.uri == targetUri) return path
            if (child.isDirectory && child.name != ".FileExplorerTrash") {
                val childName = child.name ?: continue
                stack.add(child to (path + childName))
            }
            if (visited >= 10000) break
        }
    }
    return null
}

internal fun listTrashRecords(root: DocumentFile?): List<DocumentFile> {
    val trash = root?.findFile(".FileExplorerTrash") ?: return emptyList()
    if (!trash.isDirectory) return emptyList()
    return trash.listFiles().filter { it.isDirectory && it.findFile(".trashinfo") != null }
        .sortedByDescending { it.name.orEmpty() }
}

internal fun readTrashMetadata(context: Context, record: DocumentFile): TrashMetadata? {
    val metadata = record.findFile(".trashinfo") ?: return null
    return runCatching {
        val lines = context.contentResolver.openInputStream(metadata.uri)?.bufferedReader()?.use { it.readLines() }
            ?: return null
        if (lines.size < 2) return null
        val name = String(Base64.decode(lines[0], Base64.NO_WRAP), StandardCharsets.UTF_8)
        val pathText = String(Base64.decode(lines[1], Base64.NO_WRAP), StandardCharsets.UTF_8)
        TrashMetadata(name, pathText.split('\n').filter { it.isNotBlank() })
    }.getOrNull()
}

internal suspend fun moveDocumentToTrash(
    context: Context,
    root: DocumentFile,
    source: DocumentFile,
    parentSegments: List<String>
): Boolean {
    val trash = root.findFile(".FileExplorerTrash") ?: root.createDirectory(".FileExplorerTrash") ?: return false
    if (!trash.isDirectory || source.uri == root.uri || source.uri == trash.uri) return false
    val recordName = "item_" + System.currentTimeMillis() + "_" + (0..9999).random()
    val record = trash.createDirectory(recordName) ?: return false
    val copied = copyDocument(context, source, record)
    if (!copied) {
        record.delete()
        return false
    }
    val metadata = record.createFile("text/plain", ".trashinfo")
    if (metadata == null) {
        record.delete()
        return false
    }
    val nameEncoded = Base64.encodeToString((source.name ?: "Unnamed").toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    val pathEncoded = Base64.encodeToString(parentSegments.joinToString("\n").toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    val metadataWritten = runCatching {
        context.contentResolver.openOutputStream(metadata.uri)?.bufferedWriter()?.use { writer ->
            writer.write(nameEncoded)
            writer.newLine()
            writer.write(pathEncoded)
        } ?: error("Cannot write recycle-bin metadata")
    }.isSuccess
    if (!metadataWritten || !source.delete()) {
        record.delete()
        return false
    }
    return true
}

internal suspend fun restoreTrashRecord(
    context: Context,
    root: DocumentFile,
    record: DocumentFile
): Boolean {
    val metadata = readTrashMetadata(context, record) ?: return false
    var destination = root
    for (segment in metadata.parentSegments) {
        val child = destination.findFile(segment)
        if (child == null || !child.isDirectory) {
            destination = root
            break
        }
        destination = child
    }
    val payload = record.listFiles().firstOrNull { it.name != ".trashinfo" } ?: return false
    if (!copyDocument(context, payload, destination)) return false
    return record.delete()
}

internal fun permanentlyDeleteTrashRecord(record: DocumentFile): Boolean = record.delete()
