package com.vault.srd.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vault.srd.backup.core.BackupManager
import org.koin.java.KoinJavaComponent
import kotlinx.coroutines.withTimeout

class ManualFullRestoreBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "manual_full_restore_backup_work"

        const val KEY_URI = "uri"
        const val KEY_KEY_URI = "key_uri"
        const val KEY_BIOMETRIC_CONFIRMED = "biometric_confirmed"
        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "vault_manual_backup_channel"
        private const val CHANNEL_NAME = "Vault Backup"
        private const val NOTIFICATION_ID = 4325
        private const val MAX_FULL_RESTORE_TIMEOUT_MS = 12 * 60 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        val progressLogger = BackupWorkerProgressLogger(
            worker = this,
            progressKey = KEY_PROGRESS_MESSAGE,
            foregroundFactory = { content, detail -> createForegroundInfo(content, detail) }
        )
        try {
            setForeground(createForegroundInfo("Restoring full backup package...", ""))
            progressLogger.update("Restoring full backup package...", forceNotification = true)
        } catch (t: Throwable) {
            return failure("Unable to start restore service: ${t.message ?: "unknown error"}")
        }

        val uriValue = inputData.getString(KEY_URI).orEmpty()
        val keyUriValue = inputData.getString(KEY_KEY_URI).orEmpty()
        if (uriValue.isBlank()) return failure("Backup file URI is missing.")
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return failure("Invalid backup file URI.")
        val keyUri = if (keyUriValue.isNotBlank()) {
            runCatching { Uri.parse(keyUriValue) }.getOrNull()
        } else {
            null
        }
        val biometricConfirmed = inputData.getBoolean(KEY_BIOMETRIC_CONFIRMED, false)

        val backupManager: BackupManager = KoinJavaComponent.get(BackupManager::class.java)

        return try {
            progressLogger.update("Validating full backup package...", forceNotification = true)
            val restoreResult = withTimeout(MAX_FULL_RESTORE_TIMEOUT_MS) {
                if (keyUri != null) {
                    backupManager.restoreFullBackupFiles(
                        backupUri = uri,
                        keyUri = keyUri,
                        onProgress = { stage ->
                            progressLogger.update(stage)
                        }
                    )
                } else {
                    backupManager.restoreFullBackupPackage(
                        uri = uri,
                        biometricConfirmed = biometricConfirmed,
                        onProgress = { stage ->
                            progressLogger.update(stage)
                        }
                    )
                }
            }
            if (restoreResult.success) {
                progressLogger.update(
                    "Restore complete. Time taken ${progressLogger.elapsedTimeText()}",
                    forceNotification = true
                )
                Result.success()
            } else {
                failure(restoreResult.error ?: "Full backup restore failed.")
            }
        } catch (t: Throwable) {
            failure(t.message ?: "Full backup restore failed.")
        }
    }

    private fun createForegroundInfo(contentText: String, detailText: String = ""): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Full backup restore in progress")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(if (detailText.isBlank()) contentText else detailText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun failure(message: String): Result {
        return Result.failure(
            workDataOf(
                KEY_ERROR_MESSAGE to message
            )
        )
    }
}
