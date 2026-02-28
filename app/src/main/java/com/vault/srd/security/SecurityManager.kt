package com.vault.srd.security

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecurityManager(private val context: Context) {

    companion object {
        const val PIN_ITERATIONS_MIN = 50_000
        const val PIN_ITERATIONS_BALANCED = 100_000
        const val PIN_ITERATIONS_STRONG = 200_000
        const val PIN_ITERATIONS_EXTRA_STRONG = 700_000
        const val MAX_ATTEMPTS_BEFORE_SELFIE = 3
        const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 5
    }

    private val provider = "AndroidKeyStore"

    /**
     * Terms of Service Acceptance check
     */
    private val prefs = context.getSharedPreferences("vault_life_prefs", Context.MODE_PRIVATE)
    private val encryptionManager = EncryptionManager(context, prefs)
    private val pinManager = PinManager()

    fun getPinHashIterations(): Int =
        prefs.getInt("pin_hash_iterations", PIN_ITERATIONS_STRONG)

    fun setPinHashIterations(iterations: Int) {
        val normalized = iterations.coerceIn(PIN_ITERATIONS_MIN, PIN_ITERATIONS_EXTRA_STRONG)
        prefs.edit().putInt("pin_hash_iterations", normalized).apply()
    }

    fun getPinHashIterationOptions(): List<Int> =
        listOf(PIN_ITERATIONS_MIN, PIN_ITERATIONS_BALANCED, PIN_ITERATIONS_STRONG, PIN_ITERATIONS_EXTRA_STRONG)

    private val deviceSecurityChecker = DeviceSecurityChecker(context)
    private val stealthManager = StealthManager(context, prefs)

    data class VaultBiometricUnlockChallenge(
        val cipher: Cipher,
        val payload: ByteArray
    )
    
    fun isTosAccepted(): Boolean = prefs.getBoolean("tos_accepted", false)
    fun acceptTos() = prefs.edit().putBoolean("tos_accepted", true).apply()

    fun encrypt(plainText: String): String = encryptionManager.encrypt(plainText)

    fun decrypt(encryptedText: String): String = encryptionManager.decrypt(encryptedText)

    fun generateSalt(): ByteArray = pinManager.generateSalt()

    fun hashPin(pin: String, salt: ByteArray): String = pinManager.hashPin(pin, salt, getPinHashIterations())

    fun verifyPin(inputPin: String, storedHash: String, storedSalt: String): Boolean {
        return pinManager.verifyPin(inputPin, storedHash, storedSalt)
    }

    fun needsPinHashUpgrade(storedHash: String): Boolean =
        pinManager.needsPinHashUpgrade(storedHash, getPinHashIterations())

    fun upgradedPinHashOrNull(inputPin: String, storedHash: String, storedSalt: String): String? {
        return pinManager.upgradedPinHashOrNull(inputPin, storedHash, storedSalt, getPinHashIterations())
    }

    fun upgradedPinHashFromVerified(inputPin: String, storedHash: String, storedSalt: String): String? {
        if (!pinManager.needsPinHashUpgrade(storedHash, getPinHashIterations())) return null
        return try {
            val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
            pinManager.hashPin(inputPin, salt, getPinHashIterations())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Environment Security Detection (Item 07)
     */
    fun isRooted(): Boolean = deviceSecurityChecker.isRooted()

    fun isDebuggerAttached(): Boolean = deviceSecurityChecker.isDebuggerAttached()

    /**
     * App Integrity & Anti-Tamper (Item 06)
     */
    fun verifyIntegrity(): Boolean = deviceSecurityChecker.verifyIntegrity()

    /**
     * Stealth Mode Disguise (Item 'App Icon Disguise')
     */
    fun setStealthMode(enabled: Boolean, aliasName: String? = null) {
        stealthManager.setStealthMode(enabled, aliasName)
    }

    fun isStealthModeEnabled(): Boolean = stealthManager.isStealthModeEnabled()

    fun getActiveAlias(): String? = stealthManager.getActiveAlias()

    fun getIconBackgroundMode(): String = stealthManager.getIconBackgroundMode()

    fun setIconBackgroundMode(mode: String) {
        stealthManager.setIconBackgroundMode(mode)
    }

    /**
     * Decoy PIN Logic
     */
    fun setDecoyPin(pin: String) {
        stealthManager.setDecoyPin(
            pin = pin,
            generateSalt = ::generateSalt,
            hashPin = ::hashPin,
            encrypt = ::encrypt
        )
    }

    fun verifyDecoyPin(pin: String): Boolean {
        return stealthManager.verifyDecoyPin(pin, ::verifyPin)
    }

    fun setDecoyVaultId(id: Int) {
        stealthManager.setDecoyVaultId(id)
    }

    fun getDecoyVaultId(): Int = stealthManager.getDecoyVaultId()

    fun hasDecoyPin(): Boolean = stealthManager.hasDecoyPin()

    fun getDecoyPinRaw(): String? = stealthManager.getDecoyPinRaw(::decrypt)

    /**
     * Brute Force Protection Persistence
     */
    fun getFailedAttempts(): Int = pinManager.getFailedAttempts(prefs)

    fun incrementFailedAttempts(): Int {
        return pinManager.incrementFailedAttempts(prefs)
    }

    fun resetFailedAttempts() {
        pinManager.resetFailedAttempts(prefs)
    }

    fun getLockoutCount(): Int = pinManager.getLockoutCount(prefs)
    fun incrementLockoutCount() = pinManager.incrementLockoutCount(prefs)
    fun resetLockoutCount() = pinManager.resetLockoutCount(prefs)

    fun setLockoutEndTime(endTimeMillis: Long) {
        pinManager.setLockoutEndTime(prefs, endTimeMillis)
    }

    fun getLockoutEndTime(): Long = pinManager.getLockoutEndTime(prefs)

    /**
     * Settings flags
     */
    fun shouldDeleteOriginalFiles(): Boolean = prefs.getBoolean("delete_original_files", false)
    fun setDeleteOriginalFiles(enabled: Boolean) {
        prefs.edit().putBoolean("delete_original_files", enabled).apply()
    }

    fun isIntruderCaptureEnabled(): Boolean = prefs.getBoolean("intruder_capture_enabled", false)
    fun setIntruderCaptureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("intruder_capture_enabled", enabled).apply()
    }

    // Auto-wipe extreme mode
    fun isAutoWipeEnabled(): Boolean = prefs.getBoolean("auto_wipe_enabled", false)
    fun setAutoWipeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_wipe_enabled", enabled).apply()
    }
    fun getAutoWipeVaultId(): Int = prefs.getInt("auto_wipe_vault_id", -1)
    fun setAutoWipeVaultId(id: Int) {
        prefs.edit().putInt("auto_wipe_vault_id", id).apply()
    }
    fun getAutoWipeThreshold(): Int = prefs.getInt("auto_wipe_threshold", 10)
    fun setAutoWipeThreshold(threshold: Int) {
        prefs.edit().putInt("auto_wipe_threshold", threshold.coerceAtLeast(5)).apply()
    }
    fun getAutoWipeFailedAttempts(vaultId: Int): Int = pinManager.getAutoWipeFailedAttempts(prefs, vaultId)
    fun incrementAutoWipeFailedAttempts(vaultId: Int): Int =
        pinManager.incrementAutoWipeFailedAttempts(prefs, vaultId)
    fun resetAutoWipeFailedAttempts(vaultId: Int) {
        pinManager.resetAutoWipeFailedAttempts(prefs, vaultId)
    }
    fun clearAutoWipeFailedAttempts() {
        pinManager.clearAutoWipeFailedAttempts(prefs)
    }

    // Vault activity tracking
    fun recordUserInteraction() {
        prefs.edit().putLong("last_active_time", System.currentTimeMillis()).apply()
    }

    fun getLastActiveTime(): Long = prefs.getLong("last_active_time", 0L)

    // Clipboard auto-clear (enabled + delay)
    fun isClipboardAutoClearEnabled(): Boolean = prefs.getBoolean("clipboard_auto_clear_enabled", true)
    fun setClipboardAutoClearEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("clipboard_auto_clear_enabled", enabled).apply()
    }
    fun getClipboardClearDelaySeconds(): Int = prefs.getInt("clipboard_clear_delay_seconds", 30)
    fun getClipboardDelayOptionsSeconds(): List<Int> = listOf(10, 30, 60, 0)
    fun setClipboardClearDelaySeconds(seconds: Int) {
        val normalized = if (seconds == 0) 0 else seconds.coerceIn(1, 120)
        prefs.edit().putInt("clipboard_clear_delay_seconds", normalized).apply()
    }

    // One-time warning before first backup creation.
    fun hasShownBackupIdentityWarning(): Boolean =
        prefs.getBoolean("backup_identity_warning_shown", false)

    fun markBackupIdentityWarningShown() {
        prefs.edit().putBoolean("backup_identity_warning_shown", true).apply()
    }

    // No-clipboard mode (blocks copy from vault views)
    fun isNoClipboardModeEnabled(): Boolean = prefs.getBoolean("no_clipboard_mode_enabled", false)
    fun setNoClipboardModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("no_clipboard_mode_enabled", enabled).apply()
    }

    // Randomized PIN pad
    fun isRandomPinPadEnabled(): Boolean = prefs.getBoolean("random_pin_pad_enabled", false)
    fun setRandomPinPadEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("random_pin_pad_enabled", enabled).apply()
    }

    // Panic PIN
    fun setPanicPin(pin: String) {
        stealthManager.setPanicPin(
            pin = pin,
            generateSalt = ::generateSalt,
            hashPin = ::hashPin
        )
    }

    fun clearPanicPin() {
        stealthManager.clearPanicPin()
    }

    fun hasPanicPin(): Boolean = stealthManager.hasPanicPin()

    fun isPanicPin(pin: String): Boolean = stealthManager.isPanicPin(pin, ::verifyPin)

    fun encryptForVault(vaultId: Int, plainText: String): String {
        return encryptionManager.encryptForVault(vaultId, plainText)
    }

    fun decryptForVault(vaultId: Int, encryptedText: String): String {
        return encryptionManager.decryptForVault(vaultId, encryptedText)
    }

    fun decryptForVaultStrict(vaultId: Int, encryptedText: String): String {
        return encryptionManager.decryptForVaultStrict(vaultId, encryptedText)
    }

    fun isVaultStrictBiometricModeEnabled(vaultId: Int): Boolean {
        return encryptionManager.isVaultStrictBiometricModeEnabled(vaultId)
    }

    fun prepareVaultBiometricEnrollmentCipher(vaultId: Int): Cipher? {
        return runCatching { encryptionManager.prepareVaultBiometricEnrollmentCipher(vaultId) }
            .getOrNull()
    }

    fun finalizeVaultBiometricEnrollment(vaultId: Int, cipher: Cipher): Boolean {
        return try {
            encryptionManager.finalizeVaultBiometricEnrollment(vaultId, cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearVaultBiometricBinding(vaultId)
            false
        } catch (_: Exception) {
            false
        }
    }

    fun prepareVaultBiometricUnlock(vaultId: Int): VaultBiometricUnlockChallenge? {
        return try {
            val material = encryptionManager.prepareVaultBiometricUnlock(vaultId) ?: return null
            VaultBiometricUnlockChallenge(
                cipher = material.cipher,
                payload = material.payload
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearVaultBiometricBinding(vaultId)
            null
        } catch (_: Exception) {
            null
        }
    }

    fun verifyVaultBiometricUnlock(vaultId: Int, cipher: Cipher, payload: ByteArray): Boolean {
        return try {
            encryptionManager.verifyVaultBiometricUnlock(vaultId, cipher, payload)
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearVaultBiometricBinding(vaultId)
            false
        } catch (_: Exception) {
            false
        }
    }

    fun clearVaultBiometricBinding(vaultId: Int) {
        encryptionManager.clearVaultBiometricBinding(vaultId)
    }

    fun isGlobalVaultBiometricEnabled(): Boolean {
        return encryptionManager.isGlobalBiometricEnabled()
    }

    fun isGlobalBiometricAllVaults(): Boolean {
        return prefs.getBoolean("global_bio_all_vaults", true)
    }

    fun setGlobalBiometricAllVaults(enabled: Boolean) {
        prefs.edit().putBoolean("global_bio_all_vaults", enabled).apply()
    }

    fun getGlobalBiometricVaultIds(): Set<Int> {
        val raw = prefs.getStringSet("global_bio_vault_ids", emptySet()) ?: emptySet()
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setGlobalBiometricVaultIds(ids: Set<Int>) {
        val raw = ids.map { it.toString() }.toSet()
        prefs.edit().putStringSet("global_bio_vault_ids", raw).apply()
    }

    fun isGlobalBiometricAllowedForVault(vaultId: Int): Boolean {
        if (vaultId <= 0 || !isGlobalVaultBiometricEnabled()) return false
        if (isGlobalBiometricAllVaults()) return true
        return getGlobalBiometricVaultIds().contains(vaultId)
    }

    fun prepareGlobalVaultBiometricEnrollmentCipher(): Cipher? {
        return runCatching { encryptionManager.prepareGlobalBiometricEnrollmentCipher() }
            .getOrNull()
    }

    fun finalizeGlobalVaultBiometricEnrollment(cipher: Cipher): Boolean {
        return try {
            encryptionManager.finalizeGlobalBiometricEnrollment(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearGlobalBiometricBinding()
            false
        } catch (_: Exception) {
            false
        }
    }

    fun prepareGlobalVaultBiometricUnlock(): VaultBiometricUnlockChallenge? {
        return try {
            val material = encryptionManager.prepareGlobalBiometricUnlock() ?: return null
            VaultBiometricUnlockChallenge(
                cipher = material.cipher,
                payload = material.payload
            )
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearGlobalBiometricBinding()
            null
        } catch (_: Exception) {
            null
        }
    }

    fun verifyGlobalVaultBiometricUnlock(cipher: Cipher, payload: ByteArray): Boolean {
        return try {
            encryptionManager.verifyGlobalBiometricUnlock(cipher, payload)
        } catch (_: KeyPermanentlyInvalidatedException) {
            encryptionManager.clearGlobalBiometricBinding()
            false
        } catch (_: Exception) {
            false
        }
    }

    fun clearGlobalVaultBiometricBinding() {
        encryptionManager.clearGlobalBiometricBinding()
        prefs.edit()
            .remove("global_bio_all_vaults")
            .remove("global_bio_vault_ids")
            .apply()
    }

    fun clearAllVaultStrictBiometricSessions() {
        encryptionManager.clearAllStrictSessions()
    }

    /**
     * Hardware-bound key helper placeholder for Extreme backups.
     * Currently generates/returns a non-exportable AES key from Android Keystore.
     */
    fun ensureHardwareBoundKey(): SecretKey {
        return encryptionManager.ensureHardwareBoundKey()
    }

    /**
     * Device fingerprint hash for Extreme mode backups.
     * Uses the preferred candidate (stable same-device key) from compatibility candidates.
     */
    fun getDeviceFingerprintHash(): ByteArray {
        return getDeviceFingerprintHashCandidates().firstOrNull() ?: ByteArray(0)
    }

    fun getDeviceFingerprintRecoveryToken(): String {
        val hash = getDeviceFingerprintHash()
        if (hash.isEmpty()) return ""
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Compatibility candidates for device-bound restore.
     * First entry keeps legacy behavior; additional entries improve stability across app/build changes.
     */
    fun getDeviceFingerprintHashCandidates(): List<ByteArray> {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        val signatureHash = getAppSignatureHash()
        val bootState = getVerifiedBootState()
        val hmacToken = getHardwareHmacToken()
        val tokenB64 = Base64.encodeToString(hmacToken, Base64.NO_WRAP)

        val combinedCandidates = linkedSetOf<String>()
        // Preferred stable formats for new backups.
        if (androidId.isNotBlank()) {
            combinedCandidates.add("$androidId|${context.packageName}")
            combinedCandidates.add(androidId)
        }

        // Token-based formats used by some prior builds.
        if (tokenB64.isNotBlank()) {
            combinedCandidates.add("$androidId|$tokenB64|${context.packageName}")
            combinedCandidates.add("$androidId|$tokenB64")
        }

        // Legacy strict format and token-empty fallback for compatibility.
        combinedCandidates.add("$androidId|$signatureHash|$bootState|$tokenB64")
        combinedCandidates.add("$androidId|$signatureHash|$bootState|")
        combinedCandidates.add("$androidId||${context.packageName}")
        combinedCandidates.add("$androidId|")

        if (combinedCandidates.isEmpty()) {
            combinedCandidates.add("${context.packageName}|$signatureHash|$bootState")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        return combinedCandidates
            .filter { it.isNotBlank() }
            .map { digest.digest(it.toByteArray(Charsets.UTF_8)) }
    }

    private fun getHardwareHmacToken(): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(provider).apply { load(null) }
            val alias = "VaultHardwareHmacKey"
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, provider)
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
                keyGenerator.generateKey()
            }
            val secretKey = keyStore.getKey(alias, null) as SecretKey
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(secretKey)
            mac.doFinal("vault_device_fingerprint".toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    private fun getAppSignatureHash(): String {
        return try {
            val pm = context.packageManager
            val pkg = context.packageName
            val info = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
            }
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val sigBytes = signatures.firstOrNull()?.toByteArray() ?: ByteArray(0)
            val hash = MessageDigest.getInstance("SHA-256").digest(sigBytes)
            Base64.encodeToString(hash, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getVerifiedBootState(): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java)
            get.invoke(null, "ro.boot.verifiedbootstate") as String
        } catch (_: Exception) {
            "unknown"
        }
    }

}
