package com.vault.srd.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptionManager(
    context: Context,
    private val prefs: SharedPreferences
) {
    private val provider = "AndroidKeyStore"
    private val alias = "VaultLifeMasterKey"
    private val authAlias = "VaultLifeMasterKeyAuth"
    private val transformation = "AES/GCM/NoPadding"
    private val secureRandom = SecureRandom()
    private val vaultDataKeyPrefPrefix = "vault_data_key_"
    private val vaultCipherPrefix = "vlt2:"
    private val vaultBiometricKeyAliasPrefix = "VaultLifeVaultBiometricKey_"
    private val vaultBiometricBlobPrefix = "vault_bio_blob_"
    private val vaultStrictModePrefix = "vault_bio_strict_mode_"
    private val globalBiometricKeyAlias = "VaultLifeGlobalBiometricKey"
    private val globalBiometricBlobKey = "vault_global_bio_blob"
    private val strictSessionTtlMs = 5 * 60 * 1000L
    private val strictSessionExpiryByVault = ConcurrentHashMap<Int, Long>()

    init {
        generateMasterKey()
        generatePerUseAuthMasterKey()
    }

    data class VaultBiometricUnlockMaterial(
        val cipher: Cipher,
        val payload: ByteArray
    )

    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encryptedText: String): String {
        val data = Base64.decode(encryptedText, Base64.NO_WRAP)
        val iv = data.sliceArray(0 until 12)
        val encrypted = data.sliceArray(12 until data.size)

        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun encryptForVault(vaultId: Int, plainText: String): String {
        if (vaultId <= 0) return encrypt(plainText)
        val value = encryptWithKey(getVaultDataKey(vaultId), plainText)
        return "$vaultCipherPrefix$value"
    }

    fun decryptForVault(vaultId: Int, encryptedText: String): String {
        if (vaultId <= 0) return decrypt(encryptedText)
        val payload = normalizeVaultCipherPayload(encryptedText)
        return try {
            decryptWithKey(getVaultDataKey(vaultId), payload)
        } catch (_: Exception) {
            decrypt(encryptedText)
        }
    }

    fun decryptForVaultStrict(vaultId: Int, encryptedText: String): String {
        if (vaultId <= 0) return decrypt(encryptedText)
        val payload = normalizeVaultCipherPayload(encryptedText)
        if (isVaultStrictBiometricModeEnabled(vaultId) && !hasStrictSession(vaultId)) {
            throw SecurityException("Biometric authentication required for strict vault decrypt")
        }
        return decryptWithKey(getVaultDataKey(vaultId), payload)
    }

    fun ensureHardwareBoundKey(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        val hwAlias = "VaultHardwareKey"
        if (!keyStore.containsAlias(hwAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    hwAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return keyStore.getKey(hwAlias, null) as SecretKey
    }

    fun isVaultStrictBiometricModeEnabled(vaultId: Int): Boolean {
        if (vaultId <= 0) return false
        return prefs.getBoolean("$vaultStrictModePrefix$vaultId", false)
    }

    fun setVaultStrictBiometricModeEnabled(vaultId: Int, enabled: Boolean) {
        if (vaultId <= 0) return
        prefs.edit().putBoolean("$vaultStrictModePrefix$vaultId", enabled).apply()
        if (!enabled) {
            clearStrictSession(vaultId)
        }
    }

    fun prepareVaultBiometricEnrollmentCipher(vaultId: Int): Cipher? {
        if (vaultId <= 0) return null
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateVaultBiometricKey(vaultId))
        return cipher
    }

    fun finalizeVaultBiometricEnrollment(vaultId: Int, cipher: Cipher): Boolean {
        if (vaultId <= 0) return false
        val marker = biometricMarker(vaultId)
        val encrypted = cipher.doFinal(marker.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        if (iv.isEmpty() || encrypted.isEmpty()) return false
        val stored = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        prefs.edit()
            .putString("$vaultBiometricBlobPrefix$vaultId", stored)
            .putBoolean("$vaultStrictModePrefix$vaultId", true)
            .apply()
        grantStrictSession(vaultId)
        return true
    }

    @Throws(KeyPermanentlyInvalidatedException::class)
    fun prepareVaultBiometricUnlock(vaultId: Int): VaultBiometricUnlockMaterial? {
        if (vaultId <= 0) return null
        val stored = prefs.getString("$vaultBiometricBlobPrefix$vaultId", null) ?: return null
        val raw = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (raw.size <= 12) return null
        val iv = raw.copyOfRange(0, 12)
        val payload = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance(transformation)
        val key = getOrCreateVaultBiometricKey(vaultId)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return VaultBiometricUnlockMaterial(cipher = cipher, payload = payload)
    }

    fun verifyVaultBiometricUnlock(vaultId: Int, cipher: Cipher, payload: ByteArray): Boolean {
        if (vaultId <= 0 || payload.isEmpty()) return false
        val plain = runCatching { cipher.doFinal(payload) }.getOrNull() ?: return false
        val marker = biometricMarker(vaultId)
        return if (marker == String(plain, Charsets.UTF_8)) {
            grantStrictSession(vaultId)
            true
        } else {
            false
        }
    }

    fun isGlobalBiometricEnabled(): Boolean {
        return prefs.contains(globalBiometricBlobKey)
    }

    fun prepareGlobalBiometricEnrollmentCipher(): Cipher? {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateGlobalBiometricKey())
        return cipher
    }

    fun finalizeGlobalBiometricEnrollment(cipher: Cipher): Boolean {
        val marker = globalBiometricMarker()
        val encrypted = cipher.doFinal(marker.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        if (iv.isEmpty() || encrypted.isEmpty()) return false
        val stored = Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        prefs.edit().putString(globalBiometricBlobKey, stored).apply()
        return true
    }

    @Throws(KeyPermanentlyInvalidatedException::class)
    fun prepareGlobalBiometricUnlock(): VaultBiometricUnlockMaterial? {
        val stored = prefs.getString(globalBiometricBlobKey, null) ?: return null
        val raw = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (raw.size <= 12) return null
        val iv = raw.copyOfRange(0, 12)
        val payload = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance(transformation)
        val key = getOrCreateGlobalBiometricKey()
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return VaultBiometricUnlockMaterial(cipher = cipher, payload = payload)
    }

    fun verifyGlobalBiometricUnlock(cipher: Cipher, payload: ByteArray): Boolean {
        if (payload.isEmpty()) return false
        val plain = runCatching { cipher.doFinal(payload) }.getOrNull() ?: return false
        return globalBiometricMarker() == String(plain, Charsets.UTF_8)
    }

    fun clearGlobalBiometricBinding() {
        prefs.edit().remove(globalBiometricBlobKey).apply()
        runCatching {
            val keyStore = KeyStore.getInstance(provider).apply { load(null) }
            if (keyStore.containsAlias(globalBiometricKeyAlias)) {
                keyStore.deleteEntry(globalBiometricKeyAlias)
            }
        }
    }

    fun clearVaultBiometricBinding(vaultId: Int) {
        if (vaultId <= 0) return
        prefs.edit()
            .remove("$vaultBiometricBlobPrefix$vaultId")
            .remove("$vaultStrictModePrefix$vaultId")
            .apply()
        clearStrictSession(vaultId)
        val aliasName = "$vaultBiometricKeyAliasPrefix$vaultId"
        runCatching {
            val keyStore = KeyStore.getInstance(provider).apply { load(null) }
            if (keyStore.containsAlias(aliasName)) {
                keyStore.deleteEntry(aliasName)
            }
        }
    }

    fun clearAllStrictSessions() {
        strictSessionExpiryByVault.clear()
    }

    private fun generateMasterKey() {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun generatePerUseAuthMasterKey() {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        if (!keyStore.containsAlias(authAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(authAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        return keyStore.getKey(alias, null) as SecretKey
    }

    private fun getVaultDataKey(vaultId: Int): SecretKey {
        val prefKey = "$vaultDataKeyPrefPrefix$vaultId"
        val wrapped = prefs.getString(prefKey, null)
        val keyBytes = if (!wrapped.isNullOrBlank()) {
            runCatching {
                val decoded = Base64.decode(decrypt(wrapped), Base64.NO_WRAP)
                if (decoded.size == 32) decoded else null
            }.getOrNull()
        } else {
            null
        } ?: run {
            val fresh = ByteArray(32)
            secureRandom.nextBytes(fresh)
            val encoded = Base64.encodeToString(fresh, Base64.NO_WRAP)
            val encrypted = encrypt(encoded)
            prefs.edit().putString(prefKey, encrypted).apply()
            fresh
        }
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getOrCreateVaultBiometricKey(vaultId: Int): SecretKey {
        val aliasName = "$vaultBiometricKeyAliasPrefix$vaultId"
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        if (!keyStore.containsAlias(aliasName)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    aliasName,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return keyStore.getKey(aliasName, null) as SecretKey
    }

    private fun getOrCreateGlobalBiometricKey(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        if (!keyStore.containsAlias(globalBiometricKeyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    globalBiometricKeyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build()
            )
            keyGenerator.generateKey()
        }
        return keyStore.getKey(globalBiometricKeyAlias, null) as SecretKey
    }

    private fun globalBiometricMarker(): String {
        return "GLOBAL_VAULT_BIOMETRIC_MARKER"
    }

    private fun normalizeVaultCipherPayload(encryptedText: String): String {
        return if (encryptedText.startsWith(vaultCipherPrefix)) {
            encryptedText.removePrefix(vaultCipherPrefix)
        } else {
            encryptedText
        }
    }

    private fun hasStrictSession(vaultId: Int): Boolean {
        val now = System.currentTimeMillis()
        val expiresAt = strictSessionExpiryByVault[vaultId] ?: return false
        return if (expiresAt > now) {
            true
        } else {
            strictSessionExpiryByVault.remove(vaultId)
            false
        }
    }

    private fun grantStrictSession(vaultId: Int) {
        if (vaultId <= 0) return
        strictSessionExpiryByVault[vaultId] = System.currentTimeMillis() + strictSessionTtlMs
    }

    private fun clearStrictSession(vaultId: Int) {
        strictSessionExpiryByVault.remove(vaultId)
    }

    private fun biometricMarker(vaultId: Int): String = "vault_bio_unlock_marker:$vaultId"

    private fun encryptWithKey(secretKey: SecretKey, plainText: String): String {
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decryptWithKey(secretKey: SecretKey, encryptedText: String): String {
        val data = Base64.decode(encryptedText, Base64.NO_WRAP)
        val iv = data.sliceArray(0 until 12)
        val encrypted = data.sliceArray(12 until data.size)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
