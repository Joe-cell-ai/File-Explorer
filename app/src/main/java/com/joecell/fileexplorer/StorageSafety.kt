package com.joecell.fileexplorer

internal fun isUsableTreeGrant(
    hasPersistedRead: Boolean,
    hasPersistedWrite: Boolean,
    exists: Boolean,
    isDirectory: Boolean,
    canRead: Boolean,
    canWrite: Boolean,
    requireWrite: Boolean = true
): Boolean = hasPersistedRead &&
    (!requireWrite || hasPersistedWrite) &&
    exists &&
    isDirectory &&
    canRead &&
    (!requireWrite || canWrite)

internal fun isSafeCopyDestination(
    sourceUri: String,
    destinationUri: String,
    sourceIsDirectory: Boolean,
    destinationInsideSource: Boolean
): Boolean = sourceUri != destinationUri &&
    (!sourceIsDirectory || !destinationInsideSource)
