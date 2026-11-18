package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CryptoManager {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
    fun resetKeys()
}

class AndroidKeyStoreCryptoManager(
    private val keyAlias: String = "free_public_vpn_master_aes_key"
) : CryptoManager {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val PREFIX_V1 = "v1:"
    }

    override fun encrypt(plaintext: String): String {
        val secretKey = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val buffer = ByteBuffer.allocate(1 + iv.size + encrypted.size)
        buffer.put(iv.size.toByte())
        buffer.put(iv)
        buffer.put(encrypted)
        return PREFIX_V1 + Base64.encodeToString(buffer.array(), Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(PREFIX_V1)) return null
        return runCatching {
            val raw = Base64.decode(ciphertext.removePrefix(PREFIX_V1), Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(raw)
            val ivLen = buffer.get().toInt() and 0xFF
            if (ivLen != IV_LENGTH_BYTES || buffer.remaining() <= 16) return null
            val iv = ByteArray(ivLen)
            buffer.get(iv)
            val encrypted = ByteArray(buffer.remaining())
            buffer.get(encrypted)

            val secretKey = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun resetKeys() {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) {
                keyStore.deleteEntry(keyAlias)
            }
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
