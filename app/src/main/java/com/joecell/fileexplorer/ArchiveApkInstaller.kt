package com.joecell.fileexplorer

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

private const val MAX_APK_ARCHIVE_ENTRIES = 10000
private const val MAX_STAGED_APK_BYTES = 536870912L

/** Returns APK entry paths only; it never extracts archive contents to shared storage. */
internal fun listZipApkEntries(context: Context, archiveUri: Uri): List<String> {
    val input = context.contentResolver.openInputStream(archiveUri) ?: return emptyList()
    val apks = linkedSetOf<String>()
    ZipInputStream(input.buffered()).use { zip ->
        var count = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            count++
            require(count <= MAX_APK_ARCHIVE_ENTRIES) { "ZIP contains too many entries to inspect safely" }
            if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true) &&
                entry.name.split('/').none { it == ".." } && entry.name.isNotBlank()
            ) {
                apks.add(entry.name)
                if (apks.size >= 50) break
            }
            zip.closeEntry()
        }
    }
    return apks.toList()
}

/** Stages one APK privately in cache for Android's package installer hand-off. */
internal fun stageApkFromZip(context: Context, archiveUri: Uri, entryPath: String): File? {
    require(entryPath.endsWith(".apk", ignoreCase = true) && entryPath.split('/').none { it == ".." }) {
        "Invalid APK entry path"
    }
    val input = context.contentResolver.openInputStream(archiveUri) ?: return null
    val stagingDirectory = File(context.cacheDir, "apk-install").apply { mkdirs() }
    val outputFile = File.createTempFile("archive-install-", ".apk", stagingDirectory)
    try {
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_APK_ARCHIVE_ENTRIES) { "ZIP contains too many entries" }
                if (!entry.isDirectory && entry.name == entryPath) {
                    found = true
                    outputFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_STAGED_APK_BYTES) { "APK is larger than the 512 MB safety limit" }
                            output.write(buffer, 0, count)
                        }
                    }
                    break
                }
                zip.closeEntry()
            }
        }
        if (!found || outputFile.length() == 0L) {
            outputFile.delete()
            return null
        }
        return outputFile
    } catch (error: Exception) {
        outputFile.delete()
        throw error
    }
}


/** Copies a standalone APK URI into private cache for Android's package installer. */
internal fun stageApkFromUri(context: Context, sourceUri: Uri): File? {
    val input = context.contentResolver.openInputStream(sourceUri) ?: return null
    val stagingDirectory = File(context.cacheDir, "apk-install").apply { mkdirs() }
    val outputFile = File.createTempFile("file-install-", ".apk", stagingDirectory)
    try {
        input.use { stream ->
            outputFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_STAGED_APK_BYTES) { "APK is larger than the 512 MB safety limit" }
                    output.write(buffer, 0, count)
                }
            }
        }
        if (outputFile.length() == 0L) {
            outputFile.delete()
            return null
        }
        return outputFile
    } catch (error: Exception) {
        outputFile.delete()
        throw error
    }
}
