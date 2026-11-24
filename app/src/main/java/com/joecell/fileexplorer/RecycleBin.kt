package com.joecell.fileexplorer

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.io.File

internal data class TrashMetadata(
    val originalName: String,
    val parentSegments: List<String>
)

internal fun findParentSegments(root: DocumentFile, targetUri: android.net.Uri): List<String>? {
    val stack = ArrayDeque<Triple<DocumentFile, List<String>, Int>>()
    stack.add(Triple(root, emptyList(), 0))
    var visited = 0
    while (stack.isNotEmpty() && visited < 20000) {
        val (folder, path, depth) = stack.removeLast()
        if (!folder.isDirectory || depth > 64) continue
        val children = runCatching { folder.listFiles().toList() }.getOrDefault(emptyList())
        for (child in children) {
            visited++
            if (child.uri == targetUri) return path
            if (child.isDirectory && child.name != ".FileExplorerTrash" && depth < 64) {
                val childName = child.name?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: continue
                stack.add(Triple(child, path + childName, depth + 1))
            }
            if (visited >= 20000) break
        }
    }
    return null
}

internal fun listTrashRecords(root: DocumentFile?): List<DocumentFile> {
    val trash = runCatching { root?.findFile(".FileExplorerTrash") }.getOrNull() ?: return emptyList()
    if (!runCatching { trash.isDirectory }.getOrDefault(false)) return emptyList()
    return runCatching { trash.listFiles().filter { it.isDirectory && it.findFile("trash-info.txt") != null } }
        .getOrDefault(emptyList())
        .sortedByDescending { it.name.orEmpty() }
}

internal fun encodeTrashMetadata(metadata: TrashMetadata): String {
    val encoder = Base64.getEncoder()
    return (listOf(metadata.originalName) + metadata.parentSegments).joinToString("\n") {
        encoder.encodeToString(it.toByteArray(StandardCharsets.UTF_8))
    }
}

internal fun decodeTrashMetadata(raw: String): TrashMetadata? {
    if (raw.isBlank()) return null
    return runCatching {
        val lines = raw.lineSequence().toList()
        if (lines.isEmpty()) return null
        val decoder = Base64.getDecoder()
        val decoded = lines.map { String(decoder.decode(it), StandardCharsets.UTF_8) }
        TrashMetadata(decoded.first(), decoded.drop(1))
    }.getOrNull()
}

internal fun readTrashMetadata(context: Context, record: DocumentFile): TrashMetadata? {
    val metadata = record.findFile("trash-info.txt") ?: return null
    return runCatching {
        context.contentResolver.openInputStream(metadata.uri)?.bufferedReader()?.use { reader ->
            decodeTrashMetadata(reader.readText())
        }
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
    val payloadFolder = record.createDirectory("payload")
    if (payloadFolder == null) {
        deleteDocumentTree(record)
        return false
    }
    val metadata = record.createFile("text/plain", "trash-info.txt")
    if (metadata == null) {
        deleteDocumentTree(record)
        return false
    }
    val metadataText = encodeTrashMetadata(TrashMetadata(source.name ?: "Unnamed", parentSegments))
    val metadataWritten = runCatching {
        context.contentResolver.openOutputStream(metadata.uri)?.bufferedWriter()?.use { writer ->
            writer.write(metadataText)
        } ?: error("Cannot write recycle-bin metadata")
    }.isSuccess
    if (!metadataWritten) {
        deleteDocumentTree(record)
        return false
    }

    // Use an atomic filesystem rename for ordinary shared-storage files when both
    // endpoints are inside the same storage volume. SAF providers keep the safe
    // copy-then-delete path below.
    val moved = runCatching {
        val sourceFile = source.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile
        val rootFile = root.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile
        val payloadFile = payloadFolder.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile
        if (sourceFile != null && rootFile != null && payloadFile != null &&
            sourceFile.toPath().startsWith(rootFile.toPath()) &&
            payloadFile.toPath().startsWith(rootFile.toPath()) &&
            !File(payloadFile, sourceFile.name).exists()
        ) sourceFile.renameTo(File(payloadFile, sourceFile.name)) else false
    }.getOrDefault(false)
    if (moved) return true

    if (!copyDocument(context, source, payloadFolder) || !source.delete()) {
        deleteDocumentTree(record)
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
    if (metadata.originalName.isBlank() || metadata.originalName == "." || metadata.originalName == ".." ||
        metadata.originalName.contains('/') || metadata.originalName.contains(92.toChar())) return false
    var destination = root
    for (segment in metadata.parentSegments) {
        if (segment.isBlank() || segment == "." || segment == ".." || segment.contains('/') || segment.contains(92.toChar())) return false
        val child = runCatching { destination.findFile(segment) }.getOrNull() ?: return false
        if (!child.isDirectory) return false
        destination = child
    }
    val payload = runCatching { record.findFile("payload")?.listFiles()?.firstOrNull() }.getOrNull() ?: return false
    // Restore by rename where this is a local filesystem payload; otherwise retain
    // the provider-safe copy-and-delete behavior.
    val moved = runCatching {
        val payloadFile = payload.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile
        val destinationFile = destination.uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.canonicalFile
        if (payloadFile != null && destinationFile != null &&
            destinationFile.exists() && !File(destinationFile, metadata.originalName).exists()
        ) payloadFile.renameTo(File(destinationFile, metadata.originalName)) else false
    }.getOrDefault(false)
    if (moved) return deleteDocumentTree(record)
    if (!copyDocument(context, payload, destination)) return false
    return deleteDocumentTree(record)
}

internal fun permanentlyDeleteTrashRecord(record: DocumentFile): Boolean = deleteDocumentTree(record)

private fun deleteDocumentTree(document: DocumentFile): Boolean {
    if (document.isDirectory) {
        var success = true
        for (child in document.listFiles()) {
            if (!deleteDocumentTree(child)) success = false
        }
        if (!success) return false
    }
    return document.delete()
}
