package com.joecell.fileexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

class RollbackJournalTest {
    @Test fun rollsBackInReverseCreationOrder() {
        val deleted = mutableListOf<Int>()
        val journal = RollbackJournal<Int> { deleted.add(it) }
        journal.record(1)
        journal.record(2)
        journal.record(3)
        journal.rollback()
        assertEquals(listOf(3, 2, 1), deleted)
    }

    @Test fun commitPreventsRollback() {
        val deleted = mutableListOf<String>()
        val journal = RollbackJournal<String> { deleted.add(it) }
        journal.record("created-file")
        journal.commit()
        journal.rollback()
        assertEquals(emptyList<String>(), deleted)
    }

    @Test fun rollbackIsIdempotent() {
        val deleted = mutableListOf<String>()
        val journal = RollbackJournal<String> { deleted.add(it) }
        journal.record("partial-output")
        journal.rollback()
        journal.rollback()
        assertEquals(listOf("partial-output"), deleted)
    }
}
