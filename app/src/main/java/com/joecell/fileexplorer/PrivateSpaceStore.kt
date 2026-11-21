package com.joecell.fileexplorer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Encrypted storage engine for Private Space.
 *
 * Files are stored under the app's private files directory with random names.
 * File names and contents are encrypted together using AES-256-GCM; the AES key
 * is generated and retained by Android Keystore and is never exported.
 */
internal class PrivateSpaceStore(context: Context) {
    private val directory = File(context.filesDir, "private_space").apply { mkdirs() }
    private val keyAlias = "com.joecell.fileexplorer.private-space.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun importFile(source: File, originalName: String = source.name): String {
        require(source.isFile && source.canRead()) { "Source file is not readable" }
        val id = java.util.UUID.randomUUID().toString() + ".vault"
        val target = File(directory, id)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        try {
            FileOutputStream(target).use { raw ->
                raw.write(cipher.iv.size)
                raw.write(cipher.iv)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    encrypted.write(originalName.ifBlank { source.name }.toByteArray(Charsets.UTF_8))
                    encrypted.write(0)
                    FileInputStream(source).use { it.copyTo(encrypted) }
                }
            }
            return id
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    fun exportToTemp(id: String, cacheDirectory: File): File {
        require(id.matches(Regex("[a-f0-9-]{36}\\.vault"))) { "Invalid private file id" }
        val encrypted = File(directory, id)
        require(encrypted.isFile && encrypted.canonicalFile.parentFile == directory.canonicalFile) {
            "Private file does not exist"
        }
        val temp = File.createTempFile("private-space-", ".tmp", cacheDirectory)
        try {
            FileInputStream(encrypted).use { raw ->
                val ivLength = raw.read()
                require(ivLength in 12..16) { "Invalid encrypted file header" }
                val iv = ByteArray(ivLength)
                require(raw.read(iv) == ivLength) { "Truncated encrypted file header" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                CipherInputStream(raw, cipher).use { decrypted ->
                    val name = java.io.ByteArrayOutputStream()
                    while (true) {
                        val b = decrypted.read()
                        if (b < 0 || b == 0) break
                        require(name.size() < 1024) { "Invalid private file metadata" }
                        name.write(b)
                    }
                    require(name.size() > 0) { "Missing private file metadata" }
                    FileOutputStream(temp).use { decrypted.copyTo(it) }
                }
            }
            return temp
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun displayName(id: String): String? {
        require(id.matches(Regex("[a-f0-9-]{36}\\.vault"))) { "Invalid private file id" }
        val encrypted = File(directory, id)
        if (!encrypted.isFile || encrypted.canonicalFile.parentFile != directory.canonicalFile) return null
        return runCatching {
            FileInputStream(encrypted).use { raw ->
                val ivLength = raw.read()
                require(ivLength in 12..16) { "Invalid encrypted file header" }
                val iv = ByteArray(ivLength)
                require(raw.read(iv) == ivLength) { "Truncated encrypted file header" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                CipherInputStream(raw, cipher).use { decrypted ->
                    val name = java.io.ByteArrayOutputStream()
                    while (true) {
                        val value = decrypted.read()
                        if (value < 0 || value == 0) break
                        require(name.size() < 1024) { "Invalid private file metadata" }
                        name.write(value)
                    }
                    require(name.size() > 0) { "Missing private file metadata" }
                    val buffer = ByteArray(32768)
                    while (decrypted.read(buffer) >= 0) { /* drain to verify the GCM tag */ }
                    String(name.toByteArray(), Charsets.UTF_8)
                }
            }
        }.getOrNull()
    }

    fun delete(id: String): Boolean {
        if (!id.matches(Regex("[a-f0-9-]{36}\\.vault"))) return false
        val file = File(directory, id)
        return file.canonicalFile.parentFile == directory.canonicalFile && file.delete()
    }

    fun listIds(): List<String> = directory.listFiles()
        ?.filter { it.isFile && it.name.matches(Regex("[a-f0-9-]{36}\\.vault")) }
        ?.map { it.name }?.sorted().orEmpty()

    companion object {
        private const val PIN_ITERATIONS = 180_000
        private const val PIN_BITS = 256

        internal fun createPinSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        internal fun derivePinHash(pin: CharArray, salt: ByteArray): ByteArray {
            require(pin.size in 4..12) { "PIN must contain 4 to 12 digits" }
            require(pin.all { it in '0'..'9' }) { "PIN must contain digits only" }
            val spec = PBEKeySpec(pin, salt, PIN_ITERATIONS, PIN_BITS)
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }

        internal fun verifyPin(pin: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
            MessageDigest.isEqual(derivePinHash(pin, salt), expected)
    }
}
