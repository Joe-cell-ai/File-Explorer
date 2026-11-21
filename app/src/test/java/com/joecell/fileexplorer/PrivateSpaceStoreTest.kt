package com.joecell.fileexplorer

import org.junit.Assert.*
import org.junit.Test

class PrivateSpaceStoreTest {
    @Test fun pinHashIsStableForSamePinAndSalt() {
        val salt = ByteArray(16) { it.toByte() }
        val first = PrivateSpaceStore.derivePinHash("4826".toCharArray(), salt)
        val second = PrivateSpaceStore.derivePinHash("4826".toCharArray(), salt)
        assertArrayEquals(first, second)
    }

    @Test fun differentPinDoesNotVerify() {
        val salt = ByteArray(16) { (it + 1).toByte() }
        val hash = PrivateSpaceStore.derivePinHash("4826".toCharArray(), salt)
        assertFalse(PrivateSpaceStore.verifyPin("4827".toCharArray(), salt, hash))
        assertTrue(PrivateSpaceStore.verifyPin("4826".toCharArray(), salt, hash))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsShortPin() {
        PrivateSpaceStore.derivePinHash("123".toCharArray(), ByteArray(16))
    }
}
