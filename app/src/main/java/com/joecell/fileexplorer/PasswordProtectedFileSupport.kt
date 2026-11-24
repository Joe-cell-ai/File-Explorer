package com.joecell.fileexplorer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import net.lingala.zip4j.ZipFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.ensureActive

private fun copyUriToPasswordCache(context: Context, uri: Uri, suffix: String): File {
    val directory = File(context.cacheDir, "password-protected").apply { mkdirs() }
    val file = File.createTempFile("source-", suffix, directory)
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot read the selected file")
        input.use { source -> file.outputStream().buffered().use { sink -> source.copyTo(sink) } }
        if (file.length() == 0L) throw IllegalArgumentException("The selected file is empty")
        return file
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}

internal fun isZipPasswordProtected(context: Context, uri: Uri): Boolean {
    val staged = copyUriToPasswordCache(context, uri, ".zip")
    return try {
        ZipFile(staged).isEncrypted
    } finally {
        staged.delete()
    }
}

internal fun isPdfPasswordProtected(context: Context, uri: Uri): Boolean {
    PDFBoxResourceLoader.init(context)
    val staged = copyUriToPasswordCache(context, uri, ".pdf")
    return try {
        try {
            PDDocument.load(staged).use { document -> document.isEncrypted }
        } catch (_: InvalidPasswordException) {
            true
        }
    } finally {
        staged.delete()
    }
}

internal fun decryptPdfForExternalViewer(context: Context, uri: Uri, password: String): Uri {
    PDFBoxResourceLoader.init(context)
    val source = copyUriToPasswordCache(context, uri, ".pdf")
    val directory = File(context.cacheDir, "unlocked-pdf").apply { mkdirs() }
    val output = File.createTempFile("unlocked-", ".pdf", directory)
    try {
        PDDocument.load(source, password).use { document ->
            document.setAllSecurityToBeRemoved(true)
            document.save(output)
        }
        if (!output.isFile || output.length() == 0L) throw IllegalStateException("The PDF could not be unlocked")
        return androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", output
        )
    } catch (error: Exception) {
        output.delete()
        if (error is InvalidPasswordException) throw IllegalArgumentException("Incorrect PDF password")
        throw error
    } finally {
        source.delete()
    }
}

internal suspend fun extractPasswordProtectedZip(
    context: Context,
    archiveUri: Uri,
    destination: DocumentFile,
    password: String
): String {
    val staged = copyUriToPasswordCache(context, archiveUri, ".zip")
    val created = mutableListOf<DocumentFile>()
    var totalBytes = 0L
    var extracted = 0
    try {
        val archive = ZipFile(staged, password.toCharArray())
        if (!archive.isValidZipFile) throw IllegalArgumentException("This ZIP archive is invalid or damaged")
        val headers = archive.fileHeaders
        if (headers.size > 5000) throw IllegalArgumentException("Archive contains too many entries (5,000 limit)")
        for (header in headers) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val rawName = header.fileName.replace('\\', '/')
            val segments = rawName.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty() || segments.any { it == "." || it == ".." || it.contains(':') }) {
                throw IllegalArgumentException("Archive contains an unsafe file path")
            }
            var parent = destination
            for (segment in segments.dropLast(1)) {
                val existing = parent.findFile(segment)
                if (existing != null && !existing.isDirectory) throw IllegalArgumentException("Cannot create archive folder: $segment")
                if (existing == null) {
                    parent = parent.createDirectory(segment) ?: throw IllegalArgumentException("Cannot create archive folder: $segment")
                    created += parent
                } else parent = existing
            }
            val leaf = segments.last()
            if (header.isDirectory) {
                if (parent.findFile(leaf) == null) {
                    val folder = parent.createDirectory(leaf) ?: throw IllegalArgumentException("Cannot create archive folder: $leaf")
                    created += folder
                } else if (parent.findFile(leaf)?.isDirectory != true) {
                    throw IllegalArgumentException("A file already exists with the name $leaf")
                }
            } else {
                if (parent.findFile(leaf) != null) throw IllegalArgumentException("A file already exists with the name $leaf")
                val target = parent.createFile("application/octet-stream", leaf)
                    ?: throw IllegalArgumentException("Cannot create file: $leaf")
                created += target
                archive.getInputStream(header).use { input ->
                    context.contentResolver.openOutputStream(target.uri)?.use { output ->
                        val buffer = ByteArray(32768)
                        var entryBytes = 0L
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            if (entryBytes > 134217728L || totalBytes > 536870912L) {
                                throw IllegalArgumentException("Archive exceeds the extraction safety limits")
                            }
                            output.write(buffer, 0, count)
                        }
                    } ?: throw IllegalArgumentException("Cannot write extracted file: $leaf")
                }
                extracted++
            }
        }
        return "Extracted $extracted file(s) from ZIP archive."
    } catch (error: Exception) {
        created.asReversed().forEach { runCatching { it.delete() } }
        val message = error.message.orEmpty()
        if (message.contains("password", true) || error.javaClass.simpleName.contains("WrongPassword", true)) {
            throw IllegalArgumentException("Incorrect ZIP password")
        }
        throw error
    } finally {
        staged.delete()
    }
}

internal fun decryptZipToTemporaryArchive(context: Context, archiveUri: Uri, password: String): Uri {
    val staged = copyUriToPasswordCache(context, archiveUri, ".zip")
    val directory = File(context.cacheDir, "unlocked-zip").apply { mkdirs() }
    val outputFile = File.createTempFile("unlocked-", ".zip", directory)
    try {
        val archive = ZipFile(staged, password.toCharArray())
        if (!archive.isValidZipFile) throw IllegalArgumentException("This ZIP archive is invalid or damaged")
        var totalBytes = 0L
        val headers = archive.fileHeaders
        require(headers.size <= 5000) { "Archive contains too many entries (5,000 limit)" }
        ZipOutputStream(FileOutputStream(outputFile).buffered()).use { output ->
            for (header in headers) {
                val name = header.fileName.replace('\\', '/')
                val segments = name.split('/').filter { it.isNotEmpty() }
                require(segments.isNotEmpty() && segments.none { it == "." || it == ".." || it.contains(':') }) {
                    "Archive contains an unsafe file path"
                }
                output.putNextEntry(ZipEntry(name))
                if (!header.isDirectory) {
                    archive.getInputStream(header).use { input ->
                        val buffer = ByteArray(32768)
                        var entryBytes = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            totalBytes += count
                            require(entryBytes <= 134217728L && totalBytes <= 536870912L) {
                                "Archive exceeds the 512 MB unlock safety limit"
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                output.closeEntry()
            }
        }
        return androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", outputFile
        )
    } catch (error: Exception) {
        outputFile.delete()
        val message = error.message.orEmpty()
        if (message.contains("password", true) || error.javaClass.simpleName.contains("WrongPassword", true)) {
            throw IllegalArgumentException("Incorrect ZIP password")
        }
        throw error
    } finally {
        staged.delete()
    }
}
