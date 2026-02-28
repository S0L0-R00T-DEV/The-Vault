package com.vault.srd.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.vault.srd.backup.core.BackupManager
import com.vault.srd.backup.model.BackupMode
import com.vault.srd.backup.model.BackupScope
import com.vault.srd.data.VaultDao
import com.vault.srd.security.SecurityManager
import org.koin.java.KoinJavaComponent

class ManualExtremeBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "manual_extreme_backup_work"

        @Deprecated("Extreme backup supports single-vault only")
        const val KEY_SCOPE = "scope"
        const val KEY_VAULT_ID = "vault_id"

        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_RESULT_FILE_NAME = "result_file_name"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "vault_manual_backup_channel"
        private const val CHANNEL_NAME = "Vault Backup"
        private const val NOTIFICATION_ID = 4322
    }

    override suspend fun doWork(): Result {
        val progressLogger = BackupWorkerProgressLogger(
            worker = this,
            progressKey = KEY_PROGRESS_MESSAGE,
            foregroundFactory = { content, detail -> createForegroundInfo(content, detail) }
        )
        try {
            setForeground(createForegroundInfo("Creating extreme backup...", ""))
            progressLogger.update("Creating extreme backup...", forceNotification = true)
        } catch (t: Throwable) {
            return failure("Unable to start backup service: ${t.message ?: "unknown error"}")
        }

        val vaultId = inputData.getInt(KEY_VAULT_ID, -1)

        val securityManager: SecurityManager = KoinJavaComponent.get(SecurityManager::class.java)
        val dao: VaultDao = KoinJavaComponent.get(VaultDao::class.java)

        if (vaultId <= 0) return failure("Select a vault for extreme backup.")
        if (dao.getVaultById(vaultId) == null) return failure("Selected vault not found.")

        val backupManager: BackupManager = KoinJavaComponent.get(BackupManager::class.java)

        return try {
            progressLogger.update("Preparing extreme backup...", forceNotification = true)
            val result = backupManager.createBackup(
                BackupManager.CreateRequest(
                    mode = BackupMode.EXTREME,
                    scope = BackupScope.SINGLE_VAULT,
                    targetVaultId = vaultId
                ),
                onProgress = { stage ->
                    progressLogger.update(stage)
                }
            )
            progressLogger.update(
                "Backup complete. Time taken ${progressLogger.elapsedTimeText()}",
                forceNotification = true
            )
            Result.success(
                workDataOf(
                    KEY_RESULT_FILE_NAME to result.file.name
                )
            )
        } catch (t: Throwable) {
            failure(t.message ?: "Extreme backup creation failed.")
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
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Vault backup in progress")
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
