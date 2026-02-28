package com.vault.srd.backup

import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.workDataOf
import java.util.Locale

internal class BackupWorkerProgressLogger(
    private val worker: CoroutineWorker,
    private val progressKey: String,
    private val foregroundFactory: (String, String) -> ForegroundInfo
) {
    private val startedAtMs = System.currentTimeMillis()
    private var lastSummary: String = ""
    private var lastNotificationUpdateMs: Long = 0L

    fun update(message: String, forceNotification: Boolean = false) {
        val normalized = message.trim()
        if (normalized.isBlank()) return

        val summary = buildSummary(normalized)
        worker.setProgressAsync(workDataOf(progressKey to summary))

        val now = System.currentTimeMillis()
        if (forceNotification || now - lastNotificationUpdateMs >= 1200L || summary != lastSummary) {
            lastNotificationUpdateMs = now
            lastSummary = summary
            worker.setForegroundAsync(
                foregroundFactory(summary, "")
            )
        }
    }

    fun elapsedTimeText(nowMs: Long = System.currentTimeMillis()): String {
        return formatDuration((nowMs - startedAtMs).coerceAtLeast(0L))
    }

    private fun buildSummary(message: String): String {
        val elapsed = elapsedTimeText()
        return "$message  •  Elapsed $elapsed"
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
