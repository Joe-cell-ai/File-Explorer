package com.joecell.fileexplorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecycleBinMetadataTest {
    @Test fun roundTripsUnicodeAndNewlineNames() {
        val expected = TrashMetadata("résumé\nfinal.docx", listOf("Downloads", "தமிழ்", "2026"))
        assertEquals(expected, decodeTrashMetadata(encodeTrashMetadata(expected)))
    }

    @Test fun handlesRootLevelItems() {
        val expected = TrashMetadata("photo.jpg", emptyList())
        assertEquals(expected, decodeTrashMetadata(encodeTrashMetadata(expected)))
    }

    @Test fun rejectsMalformedMetadata() {
        assertNull(decodeTrashMetadata(""))
        assertNull(decodeTrashMetadata("not-base64"))
    }
}
