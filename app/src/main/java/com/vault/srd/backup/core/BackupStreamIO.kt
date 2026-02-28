package com.vault.srd.backup.core

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupStreamIO {
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
        return cipher.doFinal(plain)
    }

    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        val secretKey = SecretKeySpec(key, "AES")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(cipherText)
    }

    fun splitCipherTextAndTag(encrypted: ByteArray): Pair<ByteArray, ByteArray> {
        if (encrypted.size < 16) {
            throw IllegalArgumentException("Invalid encrypted payload")
        }
        val cut = encrypted.size - 16
        return encrypted.copyOfRange(0, cut) to encrypted.copyOfRange(cut, encrypted.size)
    }
}
