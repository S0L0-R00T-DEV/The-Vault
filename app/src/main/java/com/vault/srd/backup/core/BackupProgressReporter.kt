package com.vault.srd.backup.core

import java.util.Locale

class BackupProgressReporter {
    fun humanReadableBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble()
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            value >= gb -> String.format(Locale.US, "%.2f GB", value / gb)
            value >= mb -> String.format(Locale.US, "%.2f MB", value / mb)
            value >= kb -> String.format(Locale.US, "%.2f KB", value / kb)
            else -> "${bytes.coerceAtLeast(0L)} B"
        }
    }

    fun buildTransferProgressMessage(
        stage: String,
        processedBytes: Long,
        totalBytes: Long?,
        startedAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val safeProcessed = processedBytes.coerceAtLeast(0L)
        val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(1L)
        val bytesPerSecond = (safeProcessed * 1000.0) / elapsedMs.toDouble()
        val speedText = if (bytesPerSecond.isFinite() && bytesPerSecond > 0.0) {
            "${humanReadableBytes(bytesPerSecond.toLong())}/s"
        } else {
            "0 B/s"
        }

        val safeTotal = totalBytes?.coerceAtLeast(0L)
        return if (safeTotal != null && safeTotal > 0L) {
            val clampedProcessed = safeProcessed.coerceAtMost(safeTotal)
            val percent = ((clampedProcessed.toDouble() / safeTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            val etaSeconds = if (bytesPerSecond > 1.0 && clampedProcessed < safeTotal) {
                ((safeTotal - clampedProcessed) / bytesPerSecond).toLong().coerceAtLeast(0L)
            } else {
                0L
            }
            "$stage: ${humanReadableBytes(clampedProcessed)} / ${humanReadableBytes(safeTotal)} (${String.format(Locale.US, "%.1f", percent)}%) | $speedText | ETA ${formatEta(etaSeconds)}"
        } else {
            "$stage: ${humanReadableBytes(safeProcessed)} | $speedText"
        }
    }

    fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val h = safe / 3600
        val m = (safe % 3600) / 60
        val s = safe % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
