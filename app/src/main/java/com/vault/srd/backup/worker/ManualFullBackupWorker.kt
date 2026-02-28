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
import com.vault.srd.security.SecurityManager
import org.koin.java.KoinJavaComponent

class ManualFullBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "manual_full_backup_work"

        const val KEY_ENCRYPTED_MASTER_KEY = "encrypted_master_key"
        const val KEY_ENCRYPTED_GENERATED_KEY = "encrypted_generated_key"
        const val KEY_ENCRYPTED_PHRASE = "encrypted_phrase"
        const val KEY_EXTREME_ZIP = "extreme_zip"

        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_RESULT_FILE_NAME = "result_file_name"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "vault_manual_backup_channel"
        private const val CHANNEL_NAME = "Vault Backup"
        private const val NOTIFICATION_ID = 4324
    }

    override suspend fun doWork(): Result {
        val progressLogger = BackupWorkerProgressLogger(
            worker = this,
            progressKey = KEY_PROGRESS_MESSAGE,
            foregroundFactory = { content, detail -> createForegroundInfo(content, detail) }
        )
        try {
            setForeground(createForegroundInfo("Creating full backup package...", ""))
            progressLogger.update("Creating full backup package...", forceNotification = true)
        } catch (t: Throwable) {
            return failure("Unable to start backup service: ${t.message ?: "unknown error"}")
        }

        val encryptedMaster = inputData.getString(KEY_ENCRYPTED_MASTER_KEY).orEmpty()
        val encryptedGenerated = inputData.getString(KEY_ENCRYPTED_GENERATED_KEY).orEmpty()
        val encryptedPhrase = inputData.getString(KEY_ENCRYPTED_PHRASE).orEmpty()
        val extremeZip = inputData.getBoolean(KEY_EXTREME_ZIP, false)
        if (encryptedMaster.isBlank() || encryptedGenerated.isBlank() || encryptedPhrase.isBlank()) {
            return failure("Master key, generated key, and recovery phrase are required.")
        }

        val securityManager: SecurityManager = KoinJavaComponent.get(SecurityManager::class.java)
        val backupManager: BackupManager = KoinJavaComponent.get(BackupManager::class.java)

        val masterKey = try {
            securityManager.decrypt(encryptedMaster)
        } catch (_: Exception) {
            return failure("Unable to decrypt master key.")
        }
        val generatedKey = try {
            securityManager.decrypt(encryptedGenerated)
        } catch (_: Exception) {
            return failure("Unable to decrypt generated key.")
        }
        val phrase = try {
            securityManager.decrypt(encryptedPhrase)
        } catch (_: Exception) {
            return failure("Unable to decrypt recovery phrase.")
        }

        val masterChars = masterKey.toCharArray()
        val generatedChars = generatedKey.toCharArray()
        return try {
            progressLogger.update("Preparing full backup package...", forceNotification = true)
            val request = BackupManager.FullBackupCreateRequest(
                masterKey = masterChars,
                generatedKey = generatedChars,
                phrase = phrase,
                extremeZip = extremeZip
            )
            val result = if (extremeZip) {
                backupManager.createFullBackupPackage(
                    request,
                    onProgress = { stage ->
                        progressLogger.update(stage)
                    }
                )
            } else {
                backupManager.createFullBackupFolder(
                    request,
                    onProgress = { stage ->
                        progressLogger.update(stage)
                    }
                )
            }
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
            failure(t.message ?: "Full backup creation failed.")
        } finally {
            java.util.Arrays.fill(masterChars, '\u0000')
            java.util.Arrays.fill(generatedChars, '\u0000')
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
            .setContentTitle("Full backup in progress")
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
