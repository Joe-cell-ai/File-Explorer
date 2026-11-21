package com.joecell.fileexplorer

/**
 * Validates an untrusted ZIP entry name before it is resolved under a SAF destination.
 * Returns normalized path segments without a trailing directory separator.
 */
internal fun validatedZipEntryPath(raw: String): List<String> {
    require(raw.isNotBlank()) { "Archive contains an empty path" }
    val normalized = raw.replace('\\', '/')
    require(!normalized.startsWith("/") && !normalized.contains('\u0000')) { "Unsafe archive path" }
    val segments = normalized.split('/')
    val pathSegments = if (normalized.endsWith("/")) segments.dropLast(1) else segments
    require(pathSegments.isNotEmpty() && pathSegments.all { it.isNotBlank() }) {
        "Archive path contains an empty segment"
    }
    require(pathSegments.none { it == "." || it == ".." }) { "Unsafe archive path" }
    return pathSegments
}
