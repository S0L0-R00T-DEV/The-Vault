package com.vault.srd.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
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

class ManualNormalBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val UNIQUE_WORK_NAME = "manual_normal_backup_work"

        const val KEY_VAULT_ID = "vault_id"
        const val KEY_ENCRYPTED_VAULT_PIN = "encrypted_vault_pin"
        const val KEY_ENCRYPTED_MASTER_KEY = "encrypted_master_key"

        const val KEY_PROGRESS_MESSAGE = "progress_message"
        const val KEY_RESULT_FILE_NAME = "result_file_name"
        const val KEY_ERROR_MESSAGE = "error_message"

        private const val CHANNEL_ID = "vault_manual_backup_channel"
        private const val CHANNEL_NAME = "Vault Backup"
        private const val NOTIFICATION_ID = 4321
    }

    override suspend fun doWork(): Result {
        val progressLogger = BackupWorkerProgressLogger(
            worker = this,
            progressKey = KEY_PROGRESS_MESSAGE,
            foregroundFactory = { content, detail -> createForegroundInfo(content, detail) }
        )
        try {
            setForeground(createForegroundInfo("Creating normal backup...", ""))
            progressLogger.update("Creating normal backup...", forceNotification = true)
        } catch (t: Throwable) {
            return failure("Unable to start backup service: ${t.message ?: "unknown error"}")
        }

        val vaultId = inputData.getInt(KEY_VAULT_ID, -1)
        val encryptedVaultPin = inputData.getString(KEY_ENCRYPTED_VAULT_PIN).orEmpty()
        val encryptedMasterKey = inputData.getString(KEY_ENCRYPTED_MASTER_KEY).orEmpty()
        if (vaultId <= 0 || encryptedVaultPin.isBlank() || encryptedMasterKey.isBlank()) {
            return failure("Invalid backup request.")
        }

        val securityManager: SecurityManager = KoinJavaComponent.get(SecurityManager::class.java)
        val dao: VaultDao = KoinJavaComponent.get(VaultDao::class.java)
        val targetVault = dao.getVaultById(vaultId) ?: return failure("Vault not found.")

        val vaultPin = try {
            securityManager.decrypt(encryptedVaultPin)
        } catch (_: Exception) {
            return failure("Unable to decrypt vault PIN.")
        }
        val masterKey = try {
            securityManager.decrypt(encryptedMasterKey)
        } catch (_: Exception) {
            return failure("Unable to decrypt master backup key.")
        }
        if (masterKey.length !in 8..20) {
            return failure("Master backup key must be 8 to 20 characters.")
        }

        if (!securityManager.verifyPin(vaultPin, targetVault.pinHash, targetVault.pinSalt)) {
            return failure("Vault PIN is invalid.")
        }

        val backupManager: BackupManager = KoinJavaComponent.get(BackupManager::class.java)

        val pinChars = vaultPin.toCharArray()
        val masterChars = masterKey.toCharArray()
        return try {
            progressLogger.update("Preparing normal backup...", forceNotification = true)
            val result = backupManager.createBackup(
                BackupManager.CreateRequest(
                    mode = BackupMode.NORMAL,
                    scope = BackupScope.SINGLE_VAULT,
                    targetVaultId = vaultId,
                    vaultPin = pinChars,
                    masterKey1 = masterChars,
                    generatePhrase = false
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
            failure(t.message ?: "Backup creation failed.")
        } finally {
            java.util.Arrays.fill(pinChars, '\u0000')
            java.util.Arrays.fill(masterChars, '\u0000')
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
