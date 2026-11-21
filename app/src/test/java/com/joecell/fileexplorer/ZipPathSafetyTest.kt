package com.joecell.fileexplorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZipPathSafetyTest {
    @Test fun acceptsSimpleAndNestedPaths() {
        assertEquals(listOf("report.pdf"), validatedZipEntryPath("report.pdf"))
        assertEquals(listOf("docs", "2026", "report.pdf"), validatedZipEntryPath("docs/2026/report.pdf"))
    }

    @Test fun normalizesWindowsSeparators() {
        assertEquals(listOf("docs", "report.pdf"), validatedZipEntryPath("docs\\\\report.pdf"))
    }

    @Test fun acceptsDirectoryEntriesWithTrailingSlash() {
        assertEquals(listOf("docs", "images"), validatedZipEntryPath("docs/images/"))
    }

    @Test fun rejectsTraversalAndAbsolutePaths() {
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("../secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("docs/../../secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("docs/./secret.txt") }
    }

    @Test fun rejectsEmptySegmentsAndNullBytes() {
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("") }
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("docs//secret.txt") }
        assertThrows(IllegalArgumentException::class.java) { validatedZipEntryPath("docs/secret\\u0000.txt") }
    }
}
