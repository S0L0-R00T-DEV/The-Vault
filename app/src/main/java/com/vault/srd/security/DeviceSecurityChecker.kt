package com.vault.srd.security

import android.content.Context
import android.content.pm.ApplicationInfo

class DeviceSecurityChecker(
    private val context: Context
) {
    fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (java.io.File(path).exists()) return true
        }
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    fun isDebuggerAttached(): Boolean {
        return android.os.Debug.isDebuggerConnected()
    }

    fun verifyIntegrity(): Boolean {
        val applicationInfo = context.applicationInfo
        val isDebuggable =
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) return false

        val maps = java.io.File("/proc/self/maps")
        if (maps.exists()) {
            try {
                val content = maps.readText()
                if (content.contains("frida-agent") || content.contains("libfrida")) return false
            } catch (_: Exception) {
            }
        }
        return true
    }
}
