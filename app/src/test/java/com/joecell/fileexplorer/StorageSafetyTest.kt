package com.joecell.fileexplorer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSafetyTest {
    @Test fun acceptsPersistedReadWriteFolderGrant() {
        assertTrue(isUsableTreeGrant(true, true, true, true, true, true))
    }

    @Test fun rejectsMissingOrRevokedPermission() {
        assertFalse(isUsableTreeGrant(false, true, true, true, true, true))
        assertFalse(isUsableTreeGrant(true, false, true, true, true, true))
    }

    @Test fun rejectsInvalidOrReadOnlyDestination() {
        assertFalse(isUsableTreeGrant(true, true, false, true, true, true))
        assertFalse(isUsableTreeGrant(true, true, true, false, true, true))
        assertFalse(isUsableTreeGrant(true, true, true, true, false, true))
        assertFalse(isUsableTreeGrant(true, true, true, true, true, false))
    }

    @Test fun allowsReadOnlyFolderForAnalysis() {
        assertTrue(isUsableTreeGrant(true, false, true, true, true, false, requireWrite = false))
    }

    @Test fun blocksCopyIntoSelfOrDescendant() {
        assertFalse(isSafeCopyDestination("content://source", "content://source", true, false))
        assertFalse(isSafeCopyDestination("content://source", "content://child", true, true))
    }

    @Test fun allowsFilesAndSafeSiblingOrParentDestinations() {
        assertTrue(isSafeCopyDestination("content://file", "content://other", false, false))
        assertTrue(isSafeCopyDestination("content://folder", "content://parent", true, false))
        assertTrue(isSafeCopyDestination("content://folder", "content://sibling", true, false))
    }
}
