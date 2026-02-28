package com.vault.srd.security

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Base64

class StealthManager(
    private val context: Context,
    private val prefs: SharedPreferences
) {
    fun setStealthMode(enabled: Boolean, aliasName: String? = null) {
        val pm = context.packageManager
        val normalAliases = listOf(
            ComponentName(context, "com.vault.srd.VaultBlackAlias"),
            ComponentName(context, "com.vault.srd.VaultWhiteAlias"),
            ComponentName(context, "com.vault.srd.VaultGrayAlias")
        )
        val stealthAliases = listOf(
            ComponentName(context, "com.vault.srd.AssistantAlias"),
            ComponentName(context, "com.vault.srd.CloudAlias"),
            ComponentName(context, "com.vault.srd.PodcastsAlias"),
            ComponentName(context, "com.vault.srd.GoogleAlias")
        )

        if (enabled && aliasName != null) {
            val targetAlias = ComponentName(context, "com.vault.srd.$aliasName")
            normalAliases.forEach { alias ->
                pm.setComponentEnabledSetting(
                    alias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            stealthAliases.forEach { alias ->
                val state = if (alias == targetAlias) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
            }
            prefs.edit().putString("stealth_alias", aliasName).apply()
        } else {
            stealthAliases.forEach { alias ->
                pm.setComponentEnabledSetting(
                    alias,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
            applyNormalIconAlias(getIconBackgroundMode())
            prefs.edit().remove("stealth_alias").apply()
        }
    }

    fun isStealthModeEnabled(): Boolean = prefs.contains("stealth_alias")

    fun getActiveAlias(): String? = prefs.getString("stealth_alias", null)

    fun getIconBackgroundMode(): String = prefs.getString("icon_bg_mode", "BLACK") ?: "BLACK"

    fun setIconBackgroundMode(mode: String) {
        val normalized = mode.uppercase()
        val safeMode = if (normalized in setOf("BLACK", "WHITE", "GRAY")) normalized else "BLACK"
        prefs.edit().putString("icon_bg_mode", safeMode).apply()
        if (!isStealthModeEnabled()) {
            applyNormalIconAlias(safeMode)
        }
    }

    private fun applyNormalIconAlias(mode: String) {
        val pm = context.packageManager
        val aliases = mapOf(
            "BLACK" to ComponentName(context, "com.vault.srd.VaultBlackAlias"),
            "WHITE" to ComponentName(context, "com.vault.srd.VaultWhiteAlias"),
            "GRAY" to ComponentName(context, "com.vault.srd.VaultGrayAlias")
        )
        val target = aliases[mode] ?: aliases.getValue("BLACK")
        aliases.values.forEach { alias ->
            val state = if (alias == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }
    }

    fun setDecoyPin(
        pin: String,
        generateSalt: () -> ByteArray,
        hashPin: (String, ByteArray) -> String,
        encrypt: (String) -> String
    ) {
        val salt = generateSalt()
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = hashPin(pin, salt)
        val encryptedPin = encrypt(pin)
        prefs.edit()
            .putString("decoy_pin_hash", hash)
            .putString("decoy_pin_salt", saltStr)
            .putString("decoy_pin_raw_encrypted", encryptedPin)
            .apply()
    }

    fun verifyDecoyPin(pin: String, verifyPin: (String, String, String) -> Boolean): Boolean {
        if (pin.isBlank()) return false
        val hash = prefs.getString("decoy_pin_hash", null) ?: return false
        val salt = prefs.getString("decoy_pin_salt", null) ?: return false
        return verifyPin(pin, hash, salt)
    }

    fun setDecoyVaultId(id: Int) {
        prefs.edit().putInt("decoy_vault_id", id).apply()
    }

    fun getDecoyVaultId(): Int = prefs.getInt("decoy_vault_id", -1)

    fun hasDecoyPin(): Boolean = prefs.contains("decoy_pin_hash")

    fun getDecoyPinRaw(decrypt: (String) -> String): String? {
        val encrypted = prefs.getString("decoy_pin_raw_encrypted", null) ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    fun setPanicPin(
        pin: String,
        generateSalt: () -> ByteArray,
        hashPin: (String, ByteArray) -> String
    ) {
        val salt = generateSalt()
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString("panic_pin_hash", hash)
            .putString("panic_pin_salt", saltStr)
            .apply()
    }

    fun clearPanicPin() {
        prefs.edit()
            .remove("panic_pin_hash")
            .remove("panic_pin_salt")
            .apply()
    }

    fun hasPanicPin(): Boolean = prefs.contains("panic_pin_hash")

    fun isPanicPin(pin: String, verifyPin: (String, String, String) -> Boolean): Boolean {
        if (pin.isBlank()) return false
        val hash = prefs.getString("panic_pin_hash", null) ?: return false
        val salt = prefs.getString("panic_pin_salt", null) ?: return false
        return verifyPin(pin, hash, salt)
    }
}
