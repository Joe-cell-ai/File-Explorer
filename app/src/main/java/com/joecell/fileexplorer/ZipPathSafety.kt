package com.joecell.fileexplorer

/**
 * Validates an untrusted ZIP entry name before it is resolved under a SAF destination.
 * Returns normalized path segments without a trailing directory separator.
 */
internal fun validatedZipEntryPath(raw: String): List<String> {
    require(raw.isNotBlank()) { "Archive contains an empty path" }
    val normalized = raw.replace('\\\\', '/')
    require(!normalized.startsWith("/") && !normalized.contains('\u0000')) { "Unsafe archive path" }
    val segments = normalized.split('/')
    require(segments.none { it == "." || it == ".." }) { "Unsafe archive path" }
    require(segments.none { it.isBlank() } || (segments.size == 2 && segments[0].isNotBlank() && segments[1].isBlank())) {
        "Archive path contains an empty segment"
    }
    return segments.filter { it.isNotBlank() }
}
