package com.vault.srd.security

import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager {
    private val pinHashPrefixV3 = "v3:"
    private val pinHashPrefixV2 = "v2:"
    private val pinHashPrefixV1 = "v1:"
    private val pinIterationsLegacy = 10_000
    private val pinIterationsV2 = 600_000
    private val pinIterationsCurrent = 200_000

    private data class StoredPinHash(val iterations: Int, val hashBase64: String)

    fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        return salt
    }

    fun hashPin(pin: String, salt: ByteArray, iterations: Int): String {
        val hash = hashPinWithIterations(pin, salt, iterations)
        return "${pinHashPrefixV3}${iterations}:$hash"
    }

    fun verifyPin(inputPin: String, storedHash: String, storedSalt: String): Boolean {
        if (inputPin.isBlank() || storedHash.isBlank() || storedSalt.isBlank()) return false
        return try {
            val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
            val parsed = parseStoredPinHash(storedHash)
            val newHash = hashPinWithIterations(inputPin, salt, parsed.iterations)
            constantTimeEqualsBase64(newHash, parsed.hashBase64)
        } catch (_: Exception) {
            false
        }
    }

    fun needsPinHashUpgrade(storedHash: String, targetIterations: Int): Boolean {
        if (storedHash.isBlank()) return false
        val parsed = parseStoredPinHash(storedHash)
        return parsed.iterations != targetIterations || !storedHash.startsWith(pinHashPrefixV3)
    }

    fun upgradedPinHashOrNull(inputPin: String, storedHash: String, storedSalt: String, targetIterations: Int): String? {
        if (!verifyPin(inputPin, storedHash, storedSalt)) return null
        if (!needsPinHashUpgrade(storedHash, targetIterations)) return null
        return try {
            val salt = Base64.decode(storedSalt, Base64.NO_WRAP)
            hashPin(inputPin, salt, targetIterations)
        } catch (_: Exception) {
            null
        }
    }

    private fun hashPinWithIterations(pin: String, salt: ByteArray, iterations: Int): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun parseStoredPinHash(storedHash: String): StoredPinHash {
        return when {
            storedHash.startsWith(pinHashPrefixV3) -> {
                val payload = storedHash.removePrefix(pinHashPrefixV3)
                val parts = payload.split(":", limit = 2)
                val iterations = parts.getOrNull(0)?.toIntOrNull() ?: pinIterationsCurrent
                val hash = parts.getOrNull(1) ?: ""
                StoredPinHash(iterations, hash)
            }
            storedHash.startsWith(pinHashPrefixV2) -> {
                StoredPinHash(pinIterationsV2, storedHash.removePrefix(pinHashPrefixV2))
            }
            storedHash.startsWith(pinHashPrefixV1) -> {
                StoredPinHash(pinIterationsLegacy, storedHash.removePrefix(pinHashPrefixV1))
            }
            else -> {
                StoredPinHash(pinIterationsLegacy, storedHash)
            }
        }
    }

    private fun constantTimeEqualsBase64(lhs: String, rhs: String): Boolean {
        val left = Base64.decode(lhs, Base64.NO_WRAP)
        val right = Base64.decode(rhs, Base64.NO_WRAP)
        return MessageDigest.isEqual(left, right)
    }

    fun getFailedAttempts(prefs: SharedPreferences): Int = prefs.getInt("failed_attempts", 0)

    fun incrementFailedAttempts(prefs: SharedPreferences): Int {
        val next = getFailedAttempts(prefs) + 1
        prefs.edit().putInt("failed_attempts", next).apply()
        return next
    }

    fun resetFailedAttempts(prefs: SharedPreferences) {
        prefs.edit().putInt("failed_attempts", 0).apply()
    }

    fun getLockoutCount(prefs: SharedPreferences): Int = prefs.getInt("lockout_count", 0)

    fun incrementLockoutCount(prefs: SharedPreferences) {
        prefs.edit().putInt("lockout_count", getLockoutCount(prefs) + 1).apply()
    }

    fun resetLockoutCount(prefs: SharedPreferences) {
        prefs.edit().putInt("lockout_count", 0).apply()
    }

    fun setLockoutEndTime(prefs: SharedPreferences, endTimeMillis: Long) {
        prefs.edit().putLong("lockout_end_time", endTimeMillis).apply()
    }

    fun getLockoutEndTime(prefs: SharedPreferences): Long = prefs.getLong("lockout_end_time", 0L)

    fun getAutoWipeFailedAttempts(prefs: SharedPreferences, vaultId: Int): Int =
        prefs.getInt("auto_wipe_failed_attempts_$vaultId", 0)

    fun incrementAutoWipeFailedAttempts(prefs: SharedPreferences, vaultId: Int): Int {
        val next = getAutoWipeFailedAttempts(prefs, vaultId) + 1
        prefs.edit().putInt("auto_wipe_failed_attempts_$vaultId", next).apply()
        return next
    }

    fun resetAutoWipeFailedAttempts(prefs: SharedPreferences, vaultId: Int) {
        prefs.edit().putInt("auto_wipe_failed_attempts_$vaultId", 0).apply()
    }

    fun clearAutoWipeFailedAttempts(prefs: SharedPreferences) {
        val editor = prefs.edit()
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith("auto_wipe_failed_attempts_") || it == "auto_wipe_failed_attempts" }
            .forEach { key -> editor.remove(key) }
        editor.apply()
    }
}
