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

internal class RollbackJournal<T>(private val deleteCreated: (T) -> Unit) {
    private val created = mutableListOf<T>()

    fun record(item: T) {
        created.add(item)
    }

    fun commit() {
        created.clear()
    }

    fun rollback() {
        created.asReversed().forEach { item -> runCatching { deleteCreated(item) } }
        created.clear()
    }
}
