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
import com.vault.srd.backup.model.BackupMode
import com.vault.srd.security.SecurityManager
import org.koin.java.KoinJavaComponent
import kotlinx.coroutines.withTimeout

class ManualRestoreBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "manual_restore_backup_work"

        const val KEY_URI = "uri"
        const val KEY_MODE = "mode"
        const val KEY_ENCRYPTED_VAULT_PIN = "encrypted_vault_pin"
        const val KEY_ENCRYPTED_MASTER_KEY = "encrypted_master_key"
        const val KEY_BIOMETRIC_CONFIRMED = "biometric_confirmed"

        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "vault_manual_backup_channel"
        private const val CHANNEL_NAME = "Vault Backup"
        private const val NOTIFICATION_ID = 4323
        private const val MAX_RESTORE_TIMEOUT_MS = 12 * 60 * 60 * 1000L
    }

    override suspend fun doWork(): Result {
        val progressLogger = BackupWorkerProgressLogger(
            worker = this,
            progressKey = KEY_PROGRESS_MESSAGE,
            foregroundFactory = { content, detail -> createForegroundInfo(content, detail) }
        )
        try {
            setForeground(createForegroundInfo("Restoring backup...", ""))
            progressLogger.update("Restoring backup...", forceNotification = true)
        } catch (t: Throwable) {
            return failure("Unable to start restore service: ${t.message ?: "unknown error"}")
        }

        val uriValue = inputData.getString(KEY_URI).orEmpty()
        if (uriValue.isBlank()) return failure("Backup file URI is missing.")
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return failure("Invalid backup URI.")
        val mode = runCatching {
            BackupMode.valueOf(inputData.getString(KEY_MODE) ?: BackupMode.NORMAL.name)
        }.getOrElse { BackupMode.NORMAL }

        val securityManager: SecurityManager = KoinJavaComponent.get(SecurityManager::class.java)
        val backupManager: BackupManager = KoinJavaComponent.get(BackupManager::class.java)

        return try {
            progressLogger.update("Preparing restore...", forceNotification = true)
            val restoreResult = when (mode) {
                BackupMode.NORMAL -> {
                    val encryptedPin = inputData.getString(KEY_ENCRYPTED_VAULT_PIN).orEmpty()
                    val encryptedMaster = inputData.getString(KEY_ENCRYPTED_MASTER_KEY).orEmpty()
                    if (encryptedPin.isBlank() || encryptedMaster.isBlank()) {
                        return failure("Vault PIN and master key are required.")
                    }
                    val vaultPin = try {
                        securityManager.decrypt(encryptedPin)
                    } catch (_: Exception) {
                        return failure("Unable to decrypt vault PIN.")
                    }
                    val masterKey = try {
                        securityManager.decrypt(encryptedMaster)
                    } catch (_: Exception) {
                        return failure("Unable to decrypt master backup key.")
                    }
                    if (masterKey.length !in 8..20) {
                        return failure("Master backup key must be 8 to 20 characters.")
                    }
                    val pinChars = vaultPin.toCharArray()
                    val masterChars = masterKey.toCharArray()
                    try {
                        withTimeout(MAX_RESTORE_TIMEOUT_MS) {
                            backupManager.restoreBackupFromUri(
                                uri,
                                BackupManager.RestoreRequest(
                                    vaultPin = pinChars,
                                    masterKey1 = masterChars
                                ),
                                onProgress = { stage ->
                                    progressLogger.update(stage)
                                }
                            )
                        }
                    } finally {
                        java.util.Arrays.fill(pinChars, '\u0000')
                        java.util.Arrays.fill(masterChars, '\u0000')
                    }
                }
                BackupMode.EXTREME -> {
                    val biometricConfirmed = inputData.getBoolean(KEY_BIOMETRIC_CONFIRMED, false)
                    withTimeout(MAX_RESTORE_TIMEOUT_MS) {
                        backupManager.restoreBackupFromUri(
                            uri,
                            BackupManager.RestoreRequest(
                                biometricConfirmed = biometricConfirmed
                            ),
                            onProgress = { stage ->
                                progressLogger.update(stage)
                            }
                        )
                    }
                }
            }

            if (restoreResult.success) {
                progressLogger.update(
                    "Restore complete. Time taken ${progressLogger.elapsedTimeText()}",
                    forceNotification = true
                )
                Result.success()
            } else {
                failure(restoreResult.error ?: "Backup restore failed.")
            }
        } catch (t: Throwable) {
            failure(t.message ?: "Backup restore failed.")
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
            .setContentTitle("Vault restore in progress")
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
