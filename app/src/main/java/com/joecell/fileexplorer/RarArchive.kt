package com.joecell.fileexplorer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val MAX_RAR_ENTRIES = 5000
private const val MAX_RAR_ENTRY_BYTES = 134217728L
private const val MAX_RAR_TOTAL_BYTES = 536870912L

fun readRarListing(context: Context, uri: Uri, password: String = ""): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: return "Cannot open RAR archive."
    return input.use { stream ->
        (if (password.isEmpty()) Archive(stream) else Archive(stream, password)).use { archive ->
            val lines = mutableListOf<String>()
            var count = 0
            while (count < 100) {
                val header = archive.nextFileHeader() ?: break
                val name = header.fileName ?: "Unnamed entry"
                lines += (if (header.isDirectory) "[Folder] " else "[File] ") + name.take(220)
                count++
            }
            if (lines.isEmpty()) "This RAR archive is empty."
            else lines.joinToString("\n") + if (count == 100) "\nShowing first 100 entries" else ""
        }
    }
}

suspend fun extractRarSafely(context: Context, archiveUri: Uri, destination: DocumentFile, password: String = ""): String {
    val input = context.contentResolver.openInputStream(archiveUri)
        ?: return "Cannot open RAR archive."
    val created = RollbackJournal<DocumentFile> { it.delete() }
    var entries = 0
    var totalBytes = 0L
    var extracted = 0
    return try {
        input.use { stream ->
            (if (password.isEmpty()) Archive(stream) else Archive(stream, password)).use { archive ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val header: FileHeader = archive.nextFileHeader() ?: break
                    entries++
                    if (entries > MAX_RAR_ENTRIES) {
                        throw IllegalArgumentException("RAR archive contains too many entries")
                    }
                    val segments = validatedZipEntryPath(header.fileName ?: "")
                    var parent = destination
                    for (segment in segments.dropLast(1)) {
                        currentCoroutineContext().ensureActive()
                        val existing = parent.findFile(segment)
                        if (existing != null && !existing.isDirectory) {
                            throw IllegalArgumentException("Cannot create RAR archive folder")
                        }
                        if (existing == null) {
                            parent = parent.createDirectory(segment)
                                ?: throw IllegalArgumentException("Cannot create RAR archive folder")
                            created.record(parent)
                        } else {
                            parent = existing
                        }
                    }
                    val leaf = segments.last()
                    if (header.isDirectory) {
                        val existing = parent.findFile(leaf)
                        if (existing == null) {
                            val folder = parent.createDirectory(leaf)
                                ?: throw IllegalArgumentException("Cannot create RAR archive folder")
                            created.record(folder)
                        } else if (!existing.isDirectory) {
                            throw IllegalArgumentException("RAR folder conflicts with an existing file: $leaf")
                        }
                    } else {
                        if (parent.findFile(leaf) != null) {
                            throw IllegalArgumentException("File already exists: $leaf")
                        }
                        val target = parent.createFile("application/octet-stream", leaf)
                            ?: throw IllegalArgumentException("Cannot create extracted file: $leaf")
                        created.record(target)
                        val output = context.contentResolver.openOutputStream(target.uri)
                            ?: throw IllegalArgumentException("Cannot write extracted file: $leaf")
                        archive.getInputStream(header).use { source ->
                            output.use { sink ->
                                val buffer = ByteArray(32768)
                                var entryBytes = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    totalBytes += count
                                    if (entryBytes > MAX_RAR_ENTRY_BYTES || totalBytes > MAX_RAR_TOTAL_BYTES) {
                                        throw IllegalArgumentException("RAR archive exceeds extraction size limits")
                                    }
                                    sink.write(buffer, 0, count)
                                }
                            }
                        }
                        extracted++
                    }
                }
            }
        }
        created.commit()
        "Extracted $extracted file(s) from RAR archive."
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        created.rollback()
        throw cancelled
    } catch (error: Exception) {
        created.rollback()
        throw error
    }
}
