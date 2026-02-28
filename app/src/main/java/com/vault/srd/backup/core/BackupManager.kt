package com.vault.srd.backup.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.biometric.BiometricManager
import com.github.luben.zstd.Zstd
import com.vault.srd.backup.model.BackupArchive
import com.vault.srd.backup.model.BackupCompression
import com.vault.srd.backup.model.BackupFile
import com.vault.srd.backup.model.BackupHeader
import com.vault.srd.backup.model.BackupAuxJsonCodec
import com.vault.srd.backup.model.BackupMode
import com.vault.srd.backup.model.BackupProfile
import com.vault.srd.backup.model.BackupPayloadJsonCodec
import com.vault.srd.backup.model.BackupScope
import com.vault.srd.backup.model.BackupSerializer
import com.vault.srd.backup.model.KdfAlgorithm
import com.vault.srd.backup.model.KdfParams
import com.vault.srd.backup.model.KeyWrap
import com.vault.srd.backup.model.KeyWrapType
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultDao
import com.vault.srd.data.VaultDatabase
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItem
import com.vault.srd.data.VaultItemTagCrossRef
import com.vault.srd.data.VaultTag
import com.vault.srd.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Fully offline backup manager for .vltbck containers.
 */
class BackupManager(
    private val context: Context,
    private val dao: VaultDao,
    private val securityManager: SecurityManager,
    private val database: VaultDatabase
) {

    data class CreateRequest(
        val mode: BackupMode,
        val scope: BackupScope = BackupScope.ENTIRE_APP,
        val targetVaultId: Int? = null,
        val vaultPin: CharArray? = null,
        val masterKey1: CharArray? = null,
        val masterKey2: CharArray? = null,
        val phrase: String? = null,
        val generatePhrase: Boolean = true,
        val writeDownloadCopy: Boolean = true
    )

    data class RestoreRequest(
        val vaultPin: CharArray? = null,
        val masterKey1: CharArray? = null,
        val masterKey2: CharArray? = null,
        val phraseFileUri: Uri? = null,
        val manualPhrase: String? = null,
        val biometricConfirmed: Boolean = false
    )

    data class BackupDescriptor(
        val mode: BackupMode,
        val profile: BackupProfile,
        val scope: BackupScope,
        val targetVaultId: Int?,
        val backupId: String,
        val includesSettings: Boolean,
        val requiresVaultPin: Boolean,
        val requiresMasterKey1: Boolean,
        val requiresMasterKey2: Boolean,
        val requiresPhraseFile: Boolean,
        val requiresBiometric: Boolean,
        val isLegacy: Boolean
    )

    data class FullBackupCreateRequest(
        val masterKey: CharArray,
        val generatedKey: CharArray,
        val phrase: String,
        val extremeZip: Boolean = false
    )

    data class FullBackupPackageDescriptor(
        val extremeZip: Boolean,
        val backupEntryName: String,
        val keyEntryName: String,
        val backupId: String?
    )

    data class BackupResult(
        val file: File,
        val sizeBytes: Long,
        val recoveryPhrase: String?,
        val phraseFile: File? = null,
        val downloadBackupUri: Uri? = null,
        val downloadPhraseUri: Uri? = null
    )

    data class RestoreResult(
        val success: Boolean,
        val error: String? = null
    )

    data class BackupInfo(
        val file: File,
        val sizeBytes: Long,
        val createdAt: Long,
        val mode: BackupMode,
        val scope: BackupScope,
        val isHealthy: Boolean
    )

    data class BackupHealthReport(
        val isValid: Boolean,
        val message: String,
        val fileVersion: Int? = null,
        val createdAt: Long? = null,
        val mode: BackupMode? = null,
        val scope: BackupScope? = null,
        val estimatedVaultCount: Int? = null,
        val integrityPassed: Boolean = false
    )

    data class BackupHistoryEntry(
        val file: File,
        val sizeBytes: Long,
        val createdAt: Long,
        val mode: BackupMode,
        val scope: BackupScope,
        val targetVaultId: Int?,
        val isHealthy: Boolean
    )

    private data class Snapshot(
        val vaults: List<Vault>,
        val items: List<VaultItem>,
        val folders: List<VaultFolder>,
        val tags: List<VaultTag>,
        val itemTags: List<VaultItemTagCrossRef>,
        val targetVaultName: String?
    )

    private data class FullNormalStreamHeader(
        val version: Int,
        val createdAt: Long,
        val backupId: String,
        val includesSettings: Boolean,
        val kdf: KdfParams,
        val salt: ByteArray,
        val nonce: ByteArray
    )

    private data class FullEncryptedPackageHeader(
        val version: Int,
        val backupId: String,
        val kdf: KdfParams,
        val salt: ByteArray,
        val nonce: ByteArray
    )

    private data class FullBackupKeyData(
        val backupId: String,
        val masterKey: String,
        val generatedKey: String,
        val phrase: String
    )

    private data class FullPackageScanResult(
        val backupEntryName: String,
        val keyEntryName: String,
        val keyData: FullBackupKeyData?
    )

    private data class Quadruple<A, B, C, D>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D
    )

    private data class NormalStreamHeader(
        val version: Int,
        val createdAt: Long,
        val targetVaultId: Int,
        val backupId: String,
        val targetVaultName: String,
        val kdf: KdfParams,
        val salt: ByteArray,
        val nonce: ByteArray
    )

    private data class ExtremeStreamHeader(
        val version: Int,
        val createdAt: Long,
        val scope: BackupScope,
        val targetVaultId: Int?,
        val backupId: String,
        val includesSettings: Boolean,
        val kdf: KdfParams,
        val salt: ByteArray,
        val nonce: ByteArray
    )

    private val backupDir: File = File(context.filesDir, "backups")
    private val random = SecureRandom()
    private val backupKeyDerivation = BackupKeyDerivation()
    private val backupStreamIO = BackupStreamIO()
    private val backupPayloadBuilder = BackupPayloadBuilder()
    private val backupProgressReporter = BackupProgressReporter()
    private val backupRestoreEngine = BackupRestoreEngine(
        dao = dao,
        database = database,
        securityManager = securityManager
    )
    private val backupSegmentedEnvelope by lazy {
        BackupSegmentedEnvelope(
            chunkSize = segmentedGcmChunkSize,
            secureRandom = random
        )
    }

    private val backupExtension = "vltbck"
    private val phraseExtension = "vltkey"
    private val fullKeyExtension = "vltk"
    private val fullPackageExtension = "zip"
    private val normalStreamMagic = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'T'.code.toByte(),
        'N'.code.toByte(),
        'R'.code.toByte(),
        'M'.code.toByte(),
        '1'.code.toByte()
    )
    private val normalStreamVersion = 3
    private val normalStreamV2Version = 2
    private val normalStreamLegacyVersion = 1
    private val fullNormalStreamMagic = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'T'.code.toByte(),
        'F'.code.toByte(),
        'U'.code.toByte(),
        'L'.code.toByte(),
        '1'.code.toByte()
    )
    private val fullNormalStreamVersion = 3
    private val fullNormalStreamV2Version = 2
    private val fullNormalStreamLegacyVersion = 1
    private val extremeStreamMagic = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'T'.code.toByte(),
        'X'.code.toByte(),
        'T'.code.toByte(),
        'R'.code.toByte(),
        '1'.code.toByte()
    )
    private val extremeStreamVersion = 3
    private val extremeStreamV2Version = 2
    private val extremeStreamLegacyVersion = 1
    private val fullEncryptedPackageMagic = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'T'.code.toByte(),
        'P'.code.toByte(),
        'K'.code.toByte(),
        'E'.code.toByte(),
        '1'.code.toByte()
    )
    private val fullEncryptedPackageVersion = 2
    private val fullEncryptedPackageLegacyVersion = 1
    private val fullKeyFileMagic = byteArrayOf(
        'V'.code.toByte(),
        'L'.code.toByte(),
        'T'.code.toByte(),
        'K'.code.toByte(),
        '2'.code.toByte()
    )
    private val fullKeyFileVersion = 2
    private val fullKeyDerivationLabel = "vault.full.backup.keyfile.v2"
    private val fullPackageBackupEntry = "backup.vltbck"
    private val fullPackageKeyEntry = "key.vltk"
    private val maxLegacyContainerBytes = 96 * 1024 * 1024
    private val segmentedGcmChunkSize = 16 * 1024 * 1024
    private val backupIoBufferSize = 8 * 1024 * 1024
    private val streamCopyBufferSize = 1024 * 1024
    private val progressReportChunkBytes = 64L * 1024L * 1024L
    private val fileLikeItemTypes = setOf("FILE", "IMAGE")
    private val maxEncryptedFieldLengthForBackupDecrypt = 8 * 1024 * 1024

    suspend fun createBackup(
        mode: BackupMode,
        password: CharArray,
        includeRecoveryPhrase: Boolean = mode == BackupMode.NORMAL,
        onProgress: ((String) -> Unit)? = null
    ): BackupResult {
        // Legacy wrapper: previous behavior produced full-app backups.
        return createBackup(
            CreateRequest(
                mode = mode,
                scope = BackupScope.ENTIRE_APP,
                masterKey1 = password,
                masterKey2 = password,
                generatePhrase = includeRecoveryPhrase
            ),
            onProgress = onProgress
        )
    }

    suspend fun createBackup(
        request: CreateRequest,
        onProgress: ((String) -> Unit)? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        ensureBackupDir()
        onProgress?.invoke("Collecting vault data...")

        if (request.mode == BackupMode.EXTREME && !isBiometricAvailable()) {
            throw IllegalStateException("Fingerprint is required for extreme backups")
        }
        if (request.mode == BackupMode.EXTREME && request.scope != BackupScope.SINGLE_VAULT) {
            throw IllegalArgumentException("Extreme backup supports single vault only")
        }

        val snapshot = collectSnapshot(request.scope, request.targetVaultId)
        if (snapshot.vaults.isEmpty()) {
            throw IllegalStateException("No vault data available for backup")
        }

        val createdAt = System.currentTimeMillis()
        val profile = resolveProfile(request.mode, request.scope)
        val backupId = UUID.randomUUID().toString()

        if (profile == BackupProfile.NORMAL_SINGLE) {
            return@withContext createNormalSingleStreamingBackup(
                request = request,
                snapshot = snapshot,
                createdAt = createdAt,
                backupId = backupId,
                onProgress = onProgress
            )
        }
        if (profile == BackupProfile.EXTREME) {
            return@withContext createExtremeStreamingBackup(
                request = request,
                snapshot = snapshot,
                createdAt = createdAt,
                backupId = backupId,
                onProgress = onProgress
            )
        }
        if (profile == BackupProfile.NORMAL_ENTIRE) {
            return@withContext createNormalEntireStreamingBackup(
                request = request,
                snapshot = snapshot,
                createdAt = createdAt,
                backupId = backupId,
                onProgress = onProgress
            )
        }

        val phrase = if (request.mode == BackupMode.NORMAL) {
            when (profile) {
                BackupProfile.NORMAL_SINGLE -> null
                BackupProfile.NORMAL_ENTIRE -> {
                    val source = when {
                        !request.phrase.isNullOrBlank() -> request.phrase
                        request.generatePhrase -> RecoveryPhrase.generate()
                        else -> null
                    } ?: throw IllegalArgumentException("Recovery phrase is required for normal full backup")
                    RecoveryPhrase.normalize(source)
                }
                else -> null
            }
        } else {
            null
        }

        val attachments = mutableMapOf<String, ByteArray>()
        val vaultsWithLogoRefs = snapshot.vaults.map { vault ->
            val logoPath = vault.logoPath
            if (!logoPath.isNullOrBlank()) {
                val file = File(logoPath)
                if (file.exists()) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes != null) {
                        val ref = storeAttachment(bytes, attachments)
                        return@map vault.copy(logoPath = "ref:$ref")
                    }
                }
            }
            vault
        }

        val backupItems = snapshot.items.map { item ->
            val decryptedContent = item.content?.let {
                try {
                    securityManager.decryptForVault(item.vaultId, it)
                } catch (_: Exception) {
                    it
                }
            }
            val decryptedUsername = item.username?.let {
                try {
                    securityManager.decryptForVault(item.vaultId, it)
                } catch (_: Exception) {
                    it
                }
            }
            val decryptedEmail = item.email?.let {
                try {
                    securityManager.decryptForVault(item.vaultId, it)
                } catch (_: Exception) {
                    it
                }
            }
            val decryptedPhone = item.phoneNumber?.let {
                try {
                    securityManager.decryptForVault(item.vaultId, it)
                } catch (_: Exception) {
                    it
                }
            }
            val fileRef = item.filePath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes != null) storeAttachment(bytes, attachments) else null
                } else {
                    null
                }
            }
            val logoRef = item.logoPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    val bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes != null) storeAttachment(bytes, attachments) else null
                } else {
                    null
                }
            }
            BackupArchive.BackupItem(
                id = item.id,
                vaultId = item.vaultId,
                type = item.type,
                name = item.name,
                description = item.description,
                content = decryptedContent,
                username = decryptedUsername,
                passCategory = item.passCategory,
                link = item.link,
                fileRef = fileRef,
                logoRef = logoRef,
                extension = item.extension,
                email = decryptedEmail,
                phoneNumber = decryptedPhone,
                folderId = item.folderId,
                createdAt = item.createdAt,
                updatedAt = item.updatedAt
            )
        }

        val settingsEntries = if (request.scope == BackupScope.ENTIRE_APP) {
            exportSettingsSnapshot()
        } else {
            emptyList()
        }

        val payload = backupPayloadBuilder.buildPayload(
            version = 2,
            createdAt = createdAt,
            appVersion = getAppVersion(),
            vaults = vaultsWithLogoRefs,
            folders = snapshot.folders,
            items = backupItems,
            settings = settingsEntries,
            tags = snapshot.tags,
            itemTags = snapshot.itemTags
        )

        val archiveBytes = BackupArchive.pack(BackupArchive.ArchiveBundle(payload, attachments))
        val compressed = Zstd.compress(archiveBytes)

        val dek = randomBytes(32)
        val kdfParams = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 3,
            memoryKiB = 64 * 1024,
            parallelism = 1
        )

        val compositeCredential = buildCompositeCredential(request, profile, phrase)

        val keyWraps = mutableListOf<KeyWrap>()
        val wrapType = if (request.mode == BackupMode.EXTREME) {
            KeyWrapType.EXTREME_DEVICE
        } else {
            KeyWrapType.MASTER_COMPOSITE
        }
        keyWraps.add(
            buildKeyWrap(
                type = wrapType,
                mode = request.mode,
                credential = compositeCredential,
                kdfParams = kdfParams,
                dataKey = dek
            )
        )

        val payloadNonce = randomBytes(12)
        val encryptedPayload = aesGcmEncrypt(dek, payloadNonce, compressed)
        val (cipherText, authTag) = splitCipherTextAndTag(encryptedPayload)

        val deviceHash = if (request.mode == BackupMode.EXTREME) {
            securityManager.getDeviceFingerprintHash()
        } else {
            null
        }

        val phraseHash = phrase?.let { BackupSerializer.computeSha256(it.toByteArray(Charsets.UTF_8)) }

        val header = BackupHeader(
            version = 7,
            mode = request.mode,
            compression = BackupCompression.ZSTD,
            createdAt = createdAt,
            payloadNonce = payloadNonce,
            deviceFingerprintHash = deviceHash,
            keyWraps = keyWraps,
            profile = profile,
            scope = request.scope,
            targetVaultId = request.targetVaultId,
            backupId = backupId,
            phraseHash = phraseHash,
            includesSettings = request.scope == BackupScope.ENTIRE_APP
        )

        val placeholder = BackupFile(header, cipherText, authTag, ByteArray(32))
        val serializedPlaceholder = BackupSerializer.serialize(placeholder)
        val (content, _) = BackupSerializer.splitHashArea(serializedPlaceholder)
        val hash = BackupSerializer.computeSha256(content)
        val finalBytes = BackupSerializer.serialize(BackupFile(header, cipherText, authTag, hash))

        val fileBaseName = buildBackupBaseName(
            mode = request.mode,
            scope = request.scope,
            targetVaultName = snapshot.targetVaultName,
            createdAt = createdAt
        )
        val backupFile = File(backupDir, "$fileBaseName.$backupExtension")
        backupFile.writeBytes(finalBytes)

        val phraseFile: File? = if (request.mode == BackupMode.NORMAL && phrase != null) {
            val phraseSecret = when (profile) {
                BackupProfile.NORMAL_SINGLE -> request.masterKey1
                BackupProfile.NORMAL_ENTIRE -> request.masterKey2 ?: request.masterKey1
                else -> request.masterKey1
            }
            if (phraseSecret != null) {
                val phraseBytes = createPhraseSidecar(backupId, phrase, phraseSecret)
                val localPhraseFile = File(backupDir, "$fileBaseName.$phraseExtension")
                localPhraseFile.writeBytes(phraseBytes)
                localPhraseFile
            } else {
                null
            }
        } else {
            null
        }

        val downloadSubDirectory = when (profile) {
            BackupProfile.NORMAL_SINGLE -> "Vault/Backups/Normal Backup"
            else -> "Vault/Backups"
        }
        val replaceDownload = profile == BackupProfile.NORMAL_SINGLE

        val backupDownloadUri = writeToDownloads(
            fileName = "$fileBaseName.$backupExtension",
            mimeType = "application/octet-stream",
            bytes = finalBytes,
            replace = replaceDownload,
            subDirectory = downloadSubDirectory
        )

        val phraseDownloadUri = phraseFile?.let {
            writeToDownloads(
                fileName = "$fileBaseName.$phraseExtension",
                mimeType = "application/octet-stream",
                bytes = it.readBytes(),
                replace = replaceDownload,
                subDirectory = downloadSubDirectory
            )
        }

        enforceRetention()

        wipe(dek)
        wipe(compressed)
        wipe(archiveBytes)
        wipe(compositeCredential)

        BackupResult(
            file = backupFile,
            sizeBytes = backupFile.length(),
            recoveryPhrase = phrase,
            phraseFile = phraseFile,
            downloadBackupUri = backupDownloadUri,
            downloadPhraseUri = phraseDownloadUri
        )
    }

    fun generateFullBackupGeneratedKey(): String {
        val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#*"
        val length = 20 + random.nextInt(11)
        val out = StringBuilder(length)
        repeat(length) {
            out.append(charset[random.nextInt(charset.length)])
        }
        return out.toString()
    }

    suspend fun createFullBackupPackage(
        request: FullBackupCreateRequest,
        onProgress: ((String) -> Unit)? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        val masterKey = String(request.masterKey)
        val generatedKey = String(request.generatedKey)
        val normalizedPhrase = RecoveryPhrase.normalize(request.phrase)

        if (masterKey.length !in 8..20) {
            throw IllegalArgumentException("Master key must be 8 to 20 characters")
        }
        if (!isValidFullGeneratedKey(generatedKey)) {
            throw IllegalArgumentException("Generated key must be 20 to 30 chars using letters, numbers, #, *")
        }
        if (!isValidRecoveryPhrase(normalizedPhrase)) {
            throw IllegalArgumentException("Recovery phrase must contain at least 12 words")
        }

        onProgress?.invoke("Phase 1/3: Creating full backup payload...")
        val fullBackup = createBackup(
            CreateRequest(
                mode = BackupMode.NORMAL,
                scope = BackupScope.ENTIRE_APP,
                masterKey1 = request.masterKey,
                masterKey2 = request.generatedKey,
                phrase = normalizedPhrase,
                generatePhrase = false,
                writeDownloadCopy = false
            ),
            onProgress = { stage ->
                onProgress?.invoke("Phase 1/3: $stage")
            }
        )

        val backupDescriptor = readBackupDescriptorFromFile(fullBackup.file)
            ?: throw IllegalStateException("Unable to read full backup metadata")
        val backupId = backupDescriptor.backupId.ifBlank { UUID.randomUUID().toString() }
        val keyBytes = createFullBackupKeyFileBytes(
            backupId = backupId,
            masterKey = masterKey,
            generatedKey = generatedKey,
            phrase = normalizedPhrase
        )
        val keyFile = File(backupDir, "key_$backupId.$fullKeyExtension")
        if (keyFile.exists()) keyFile.delete()
        keyFile.writeBytes(keyBytes)
        val estimatedPackageBytes = fullBackup.file.length().coerceAtLeast(0L) + keyFile.length().coerceAtLeast(0L)
        onProgress?.invoke("Phase 2/3: Estimated package data ${humanReadableBytes(estimatedPackageBytes)}")

        val packageFileName = "full_application_backup_${System.currentTimeMillis()}.$fullPackageExtension"
        val packageFile = File(backupDir, packageFileName)
        if (packageFile.exists()) packageFile.delete()

        val packageDownloadUri = try {
            onProgress?.invoke("Phase 2/3: Packaging backup files...")
            if (request.extremeZip) {
                createEncryptedFullPackageZip(
                    packageFile = packageFile,
                    backupFile = fullBackup.file,
                    keyFile = keyFile,
                    backupId = backupId,
                    onProgress = { stage ->
                        onProgress?.invoke("Phase 2/3: $stage")
                    }
                )
            } else {
                createPlainFullPackageZip(
                    packageFile = packageFile,
                    backupFile = fullBackup.file,
                    keyFile = keyFile,
                    onProgress = { stage ->
                        onProgress?.invoke("Phase 2/3: $stage")
                    }
                )
            }

            val downloadSubDirectory = "Vault/Backups/Full Backup"
            onProgress?.invoke("Phase 3/3: Copying package to Downloads...")
            writeFileToDownloadsWithProgress(
                fileName = packageFile.name,
                mimeType = "application/zip",
                file = packageFile,
                replace = false,
                subDirectory = downloadSubDirectory,
                onProgress = { stage ->
                    onProgress?.invoke("Phase 3/3: $stage")
                }
            )
        } finally {
            runCatching { fullBackup.file.delete() }
            runCatching { keyFile.delete() }
        }

        BackupResult(
            file = packageFile,
            sizeBytes = packageFile.length(),
            recoveryPhrase = normalizedPhrase,
            phraseFile = null,
            downloadBackupUri = packageDownloadUri,
            downloadPhraseUri = null
        )
    }

    suspend fun createFullBackupFolder(
        request: FullBackupCreateRequest,
        onProgress: ((String) -> Unit)? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        val masterKey = String(request.masterKey)
        val generatedKey = String(request.generatedKey)
        val normalizedPhrase = RecoveryPhrase.normalize(request.phrase)

        if (masterKey.length !in 8..20) {
            throw IllegalArgumentException("Master key must be 8 to 20 characters")
        }
        if (!isValidFullGeneratedKey(generatedKey)) {
            throw IllegalArgumentException("Generated key must be 20 to 30 chars using letters, numbers, #, *")
        }
        if (!isValidRecoveryPhrase(normalizedPhrase)) {
            throw IllegalArgumentException("Recovery phrase must contain at least 12 words")
        }

        onProgress?.invoke("Phase 1/2: Creating full backup payload...")
        val fullBackup = createBackup(
            CreateRequest(
                mode = BackupMode.NORMAL,
                scope = BackupScope.ENTIRE_APP,
                masterKey1 = request.masterKey,
                masterKey2 = request.generatedKey,
                phrase = normalizedPhrase,
                generatePhrase = false,
                writeDownloadCopy = false
            ),
            onProgress = { stage ->
                onProgress?.invoke("Phase 1/2: $stage")
            }
        )

        val backupDescriptor = readBackupDescriptorFromFile(fullBackup.file)
            ?: throw IllegalStateException("Unable to read full backup metadata")
        val backupId = backupDescriptor.backupId.ifBlank { UUID.randomUUID().toString() }
        val keyBytes = createFullBackupKeyFileBytes(
            backupId = backupId,
            masterKey = masterKey,
            generatedKey = generatedKey,
            phrase = normalizedPhrase
        )
        val keyFile = File(backupDir, "key_$backupId.$fullKeyExtension")
        if (keyFile.exists()) keyFile.delete()
        keyFile.writeBytes(keyBytes)

        val estimatedPackageBytes = fullBackup.file.length().coerceAtLeast(0L) + keyFile.length().coerceAtLeast(0L)
        onProgress?.invoke("Phase 2/2: Estimated package data ${humanReadableBytes(estimatedPackageBytes)}")

        val folderName = nextFullBackupFolderName()
        val downloadSubDirectory = "Vault/Backups/Full Backup/$folderName"
        val packageDownloadUri = try {
            onProgress?.invoke("Phase 2/2: Copying backup files...")
            writeFileToDownloadsWithProgress(
                fileName = "backup.$backupExtension",
                mimeType = "application/octet-stream",
                file = fullBackup.file,
                replace = false,
                subDirectory = downloadSubDirectory,
                onProgress = { stage ->
                    onProgress?.invoke("Phase 2/2: $stage")
                }
            )
            writeFileToDownloadsWithProgress(
                fileName = "key.$fullKeyExtension",
                mimeType = "application/octet-stream",
                file = keyFile,
                replace = false,
                subDirectory = downloadSubDirectory,
                onProgress = { stage ->
                    onProgress?.invoke("Phase 2/2: $stage")
                }
            )
        } finally {
            runCatching { fullBackup.file.delete() }
            runCatching { keyFile.delete() }
        }

        BackupResult(
            file = File(folderName),
            sizeBytes = estimatedPackageBytes,
            recoveryPhrase = normalizedPhrase,
            phraseFile = null,
            downloadBackupUri = packageDownloadUri,
            downloadPhraseUri = null
        )
    }

    suspend fun restoreFullBackupFiles(
        backupUri: Uri,
        keyUri: Uri,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        onProgress?.invoke("Reading full backup key file...")
        val keyBytes = context.contentResolver.openInputStream(keyUri)?.use { readBytesWithLimit(it, 512 * 1024) }
            ?: return@withContext RestoreResult(false, "Unable to read key file")
        val keyData = parseFullBackupKeyFileBytes(keyBytes)
            ?: return@withContext RestoreResult(false, "Invalid full backup key file")

        val backupDescriptor = readBackupDescriptor(backupUri)
            ?: return@withContext RestoreResult(false, "Invalid backup file")
        if (backupDescriptor.scope != BackupScope.ENTIRE_APP) {
            return@withContext RestoreResult(false, "Selected file is not a full backup")
        }
        if (backupDescriptor.backupId.isNotBlank() && backupDescriptor.backupId != keyData.backupId) {
            return@withContext RestoreResult(false, "Backup keys do not match this file")
        }

        val masterChars = keyData.masterKey.toCharArray()
        val generatedChars = keyData.generatedKey.toCharArray()
        try {
            onProgress?.invoke("Restoring application data...")
            restoreBackupFromUri(
                backupUri,
                RestoreRequest(
                    masterKey1 = masterChars,
                    masterKey2 = generatedChars,
                    manualPhrase = keyData.phrase
                ),
                onProgress = onProgress
            )
        } finally {
            wipe(masterChars)
            wipe(generatedChars)
        }
    }

    suspend fun readFullBackupPackageDescriptor(uri: Uri): FullBackupPackageDescriptor? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
            if (isFullEncryptedPackageInput(buffered)) {
                val header = runCatching { readFullEncryptedPackageHeader(DataInputStream(buffered)) }.getOrNull()
                    ?: return@withContext null
                return@withContext FullBackupPackageDescriptor(
                    extremeZip = true,
                    backupEntryName = fullPackageBackupEntry,
                    keyEntryName = fullPackageKeyEntry,
                    backupId = header.backupId
                )
            }

            val scan = scanFullPackageEntries(ZipInputStream(buffered))
                ?: return@withContext null
            return@withContext FullBackupPackageDescriptor(
                extremeZip = false,
                backupEntryName = scan.backupEntryName,
                keyEntryName = scan.keyEntryName,
                backupId = scan.keyData?.backupId
            )
        }
    }

    suspend fun restoreFullBackupPackage(
        uri: Uri,
        biometricConfirmed: Boolean,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        onProgress?.invoke("Opening full backup package...")
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext RestoreResult(false, "Unable to read full backup package")
        val tempBackup = File.createTempFile("full_restore_", ".vltbck", context.cacheDir)
        val restoreResult = try {
            input.use { raw ->
                val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
                val extracted = if (isFullEncryptedPackageInput(buffered)) {
                    if (!biometricConfirmed) {
                        return@withContext RestoreResult(false, "Fingerprint authentication required")
                    }
                    onProgress?.invoke("Decrypting extreme zip package...")
                    val header = readFullEncryptedPackageHeader(DataInputStream(buffered))
                        ?: return@withContext RestoreResult(false, "Invalid full backup package")
                    val extractedEntries = extractEncryptedFullPackageEntries(
                        encryptedInput = buffered,
                        header = header,
                        backupTarget = tempBackup,
                        onProgress = onProgress
                    )
                    if (extractedEntries == null) {
                        return@withContext RestoreResult(false, "Backup cannot be restored on this device")
                    }
                    extractedEntries
                } else {
                    onProgress?.invoke("Extracting backup and key files...")
                    extractFullPackageEntries(
                        zipInput = ZipInputStream(buffered),
                        backupTarget = tempBackup,
                        onProgress = onProgress
                    )
                        ?: return@withContext RestoreResult(false, "Invalid full backup package")
                }

                val keyData = extracted.keyData
                if (keyData == null) {
                    return@withContext RestoreResult(false, "Invalid full backup key file")
                }
                onProgress?.invoke("Validating backup key material...")

                val backupDescriptor = readBackupDescriptorFromFile(tempBackup)
                    ?: return@withContext RestoreResult(false, "Invalid backup file")
                if (backupDescriptor.scope != BackupScope.ENTIRE_APP) {
                    return@withContext RestoreResult(false, "Selected package is not a full backup")
                }
                if (backupDescriptor.backupId.isNotBlank() && backupDescriptor.backupId != keyData.backupId) {
                    return@withContext RestoreResult(false, "Backup keys do not match this package")
                }

                val masterChars = keyData.masterKey.toCharArray()
                val generatedChars = keyData.generatedKey.toCharArray()
                try {
                    onProgress?.invoke("Restoring application data...")
                    restoreBackupFromFile(
                        tempBackup,
                        RestoreRequest(
                            masterKey1 = masterChars,
                            masterKey2 = generatedChars,
                            manualPhrase = keyData.phrase
                        ),
                        onProgress = onProgress
                    )
                } finally {
                    wipe(masterChars)
                    wipe(generatedChars)
                }
            }
        } catch (e: Exception) {
            RestoreResult(false, e.message ?: "Failed to restore full backup")
        } finally {
            runCatching { tempBackup.delete() }
        }

        return@withContext restoreResult
    }

    private fun createNormalSingleStreamingBackup(
        request: CreateRequest,
        snapshot: Snapshot,
        createdAt: Long,
        backupId: String,
        onProgress: ((String) -> Unit)?
    ): BackupResult {
        val vaultPin = request.vaultPin ?: throw IllegalArgumentException("Vault PIN is required")
        val masterKey = request.masterKey1 ?: throw IllegalArgumentException("Master key is required")
        if (masterKey.size !in 8..20) {
            throw IllegalArgumentException("Master backup key must be 8 to 20 characters")
        }
        val targetVault = snapshot.vaults.firstOrNull()
            ?: throw IllegalArgumentException("Vault not found for backup")

        val attachments = linkedMapOf<String, File>()
        val vaultsWithLogoRefs = snapshot.vaults.map { vault ->
            val logoRef = vault.logoPath?.let { addAttachmentFileRef(it, attachments) }
            if (logoRef != null) vault.copy(logoPath = "ref:$logoRef") else vault
        }

        val backupItems = snapshot.items.map { item -> mapVaultItemToBackupItem(item, attachments) }

        val payload = backupPayloadBuilder.buildPayload(
            version = 2,
            createdAt = createdAt,
            appVersion = getAppVersion(),
            vaults = vaultsWithLogoRefs,
            folders = snapshot.folders,
            items = backupItems,
            settings = emptyList(),
            tags = snapshot.tags,
            itemTags = snapshot.itemTags
        )
        val estimatedTotalBytes = estimateStreamingBackupPlainBytes(payload, attachments)
        onProgress?.invoke("Estimated backup data: ${humanReadableBytes(estimatedTotalBytes)}")

        val credentialBytes = "${String(vaultPin)}|${String(masterKey)}".toByteArray(Charsets.UTF_8)
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdfParams = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 4,
            memoryKiB = 16 * 1024,
            parallelism = 1
        )
        val dataKey = try {
            argon2id(
                input = credentialBytes,
                salt = salt,
                params = kdfParams,
                outLen = 32
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to derive backup key", t)
        }
        wipe(credentialBytes)

        val fileBaseName = buildBackupBaseName(
            mode = BackupMode.NORMAL,
            scope = BackupScope.SINGLE_VAULT,
            targetVaultName = snapshot.targetVaultName,
            createdAt = createdAt
        )
        val backupFile = File(backupDir, "$fileBaseName.$backupExtension")
        if (backupFile.exists()) {
            backupFile.delete()
        }
        val progressReporter = ByteProgressReporter(
            stage = "Encrypting normal backup",
            onProgress = onProgress,
            totalBytes = estimatedTotalBytes
        )

        val header = NormalStreamHeader(
            version = normalStreamVersion,
            createdAt = createdAt,
            targetVaultId = targetVault.id,
            backupId = backupId,
            targetVaultName = targetVault.name,
            kdf = kdfParams,
            salt = salt,
            nonce = nonce
        )

        try {
            FileOutputStream(backupFile).use { fos ->
                val buffered = BufferedOutputStream(fos, backupIoBufferSize)
                writeNormalStreamHeader(buffered, header)

                backupSegmentedEnvelope.openEncryptingStream(
                    out = buffered,
                    key = dataKey,
                    appendIntegrityTrailer = header.version >= 3,
                    onChunkPlainBytesWritten = { processed ->
                        progressReporter.report(processed)
                    }
                ).use { segmentedOut ->
                    ZipOutputStream(BufferedOutputStream(segmentedOut, backupIoBufferSize)).use { zipOut ->
                        onProgress?.invoke("Writing backup metadata...")
                        writePayloadZipEntry(zipOut, payload)
                        onProgress?.invoke("Archiving attachments...")
                        writeZipAttachmentEntries(zipOut, attachments, onProgress)
                    }
                }
            }
        } catch (e: Throwable) {
            backupFile.delete()
            val detail = e.message?.takeIf { it.isNotBlank() }
            val message = if (detail != null) "Unable to create backup file: $detail" else "Unable to create backup file"
            throw IllegalStateException(message, e)
        } finally {
            wipe(dataKey)
        }

        val backupDownloadUri = if (request.writeDownloadCopy) {
            val downloadSubDirectory = "Vault/Backups/Normal Backup"
            onProgress?.invoke("Copying backup to Downloads...")
            writeFileToDownloads(
                fileName = backupFile.name,
                mimeType = "application/octet-stream",
                file = backupFile,
                replace = true,
                subDirectory = downloadSubDirectory
            )
        } else {
            null
        }

        if (request.writeDownloadCopy) {
            enforceRetention()
        }

        return BackupResult(
            file = backupFile,
            sizeBytes = backupFile.length(),
            recoveryPhrase = null,
            phraseFile = null,
            downloadBackupUri = backupDownloadUri,
            downloadPhraseUri = null
        )
    }

    private fun createExtremeStreamingBackup(
        request: CreateRequest,
        snapshot: Snapshot,
        createdAt: Long,
        backupId: String,
        onProgress: ((String) -> Unit)?
    ): BackupResult {
        val attachments = linkedMapOf<String, File>()
        val vaultsWithLogoRefs = snapshot.vaults.map { vault ->
            val logoRef = vault.logoPath?.let { addAttachmentFileRef(it, attachments) }
            if (logoRef != null) vault.copy(logoPath = "ref:$logoRef") else vault
        }

        val backupItems = snapshot.items.map { item -> mapVaultItemToBackupItem(item, attachments) }

        val settingsEntries = if (request.scope == BackupScope.ENTIRE_APP) {
            exportSettingsSnapshot()
        } else {
            emptyList()
        }

        val payload = backupPayloadBuilder.buildPayload(
            version = 2,
            createdAt = createdAt,
            appVersion = getAppVersion(),
            vaults = vaultsWithLogoRefs,
            folders = snapshot.folders,
            items = backupItems,
            settings = settingsEntries,
            tags = snapshot.tags,
            itemTags = snapshot.itemTags
        )
        val estimatedTotalBytes = estimateStreamingBackupPlainBytes(payload, attachments)
        onProgress?.invoke("Estimated backup data: ${humanReadableBytes(estimatedTotalBytes)}")

        val fingerprintInput = securityManager.getDeviceFingerprintHash()
        if (fingerprintInput.isEmpty()) {
            throw IllegalStateException("Unable to read device fingerprint key")
        }
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdfParams = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 4,
            memoryKiB = 16 * 1024,
            parallelism = 1
        )
        val dataKey = try {
            argon2id(
                input = fingerprintInput,
                salt = salt,
                params = kdfParams,
                outLen = 32
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to derive extreme backup key", t)
        }

        val resolvedTargetVaultId = if (request.scope == BackupScope.SINGLE_VAULT) {
            request.targetVaultId ?: snapshot.vaults.firstOrNull()?.id
        } else {
            null
        }
        val fileBaseName = buildBackupBaseName(
            mode = BackupMode.EXTREME,
            scope = request.scope,
            targetVaultName = snapshot.targetVaultName,
            createdAt = createdAt
        )
        val backupFile = File(backupDir, "$fileBaseName.$backupExtension")
        if (backupFile.exists()) {
            backupFile.delete()
        }
        val progressReporter = ByteProgressReporter(
            stage = "Encrypting extreme backup",
            onProgress = onProgress,
            totalBytes = estimatedTotalBytes
        )

        val header = ExtremeStreamHeader(
            version = extremeStreamVersion,
            createdAt = createdAt,
            scope = request.scope,
            targetVaultId = resolvedTargetVaultId,
            backupId = backupId,
            includesSettings = request.scope == BackupScope.ENTIRE_APP,
            kdf = kdfParams,
            salt = salt,
            nonce = nonce
        )

        try {
            FileOutputStream(backupFile).use { fos ->
                val buffered = BufferedOutputStream(fos, backupIoBufferSize)
                writeExtremeStreamHeader(buffered, header)

                backupSegmentedEnvelope.openEncryptingStream(
                    out = buffered,
                    key = dataKey,
                    appendIntegrityTrailer = header.version >= 3,
                    onChunkPlainBytesWritten = { processed ->
                        progressReporter.report(processed)
                    }
                ).use { segmentedOut ->
                    ZipOutputStream(BufferedOutputStream(segmentedOut, backupIoBufferSize)).use { zipOut ->
                        onProgress?.invoke("Writing backup metadata...")
                        writePayloadZipEntry(zipOut, payload)
                        onProgress?.invoke("Archiving attachments...")
                        writeZipAttachmentEntries(zipOut, attachments, onProgress)
                    }
                }
            }
        } catch (e: Throwable) {
            backupFile.delete()
            val detail = e.message?.takeIf { it.isNotBlank() }
            val message = if (detail != null) "Unable to create extreme backup file: $detail" else "Unable to create extreme backup file"
            throw IllegalStateException(message, e)
        } finally {
            wipe(dataKey)
        }

        val backupDownloadUri = if (request.writeDownloadCopy) {
            val downloadSubDirectory = "Vault/Backups/Extreme Backup"
            onProgress?.invoke("Copying backup to Downloads...")
            writeFileToDownloads(
                fileName = backupFile.name,
                mimeType = "application/octet-stream",
                file = backupFile,
                replace = true,
                subDirectory = downloadSubDirectory
            )
        } else {
            null
        }

        if (request.writeDownloadCopy) {
            enforceRetention()
        }

        return BackupResult(
            file = backupFile,
            sizeBytes = backupFile.length(),
            recoveryPhrase = null,
            phraseFile = null,
            downloadBackupUri = backupDownloadUri,
            downloadPhraseUri = null
        )
    }

    private fun createNormalEntireStreamingBackup(
        request: CreateRequest,
        snapshot: Snapshot,
        createdAt: Long,
        backupId: String,
        onProgress: ((String) -> Unit)?
    ): BackupResult {
        val masterKey = request.masterKey1 ?: throw IllegalArgumentException("Master key is required")
        val generatedKey = request.masterKey2 ?: throw IllegalArgumentException("Generated key is required")
        if (masterKey.size !in 8..20) {
            throw IllegalArgumentException("Master key must be 8 to 20 characters")
        }
        val generatedKeyText = String(generatedKey)
        if (!isValidFullGeneratedKey(generatedKeyText)) {
            throw IllegalArgumentException("Generated key must be 20 to 30 chars using letters, numbers, #, *")
        }

        val phraseSource = when {
            !request.phrase.isNullOrBlank() -> request.phrase
            request.generatePhrase -> RecoveryPhrase.generate()
            else -> null
        } ?: throw IllegalArgumentException("Recovery phrase is required for full backup")
        val normalizedPhrase = RecoveryPhrase.normalize(phraseSource)
        if (!isValidRecoveryPhrase(normalizedPhrase)) {
            throw IllegalArgumentException("Recovery phrase must contain at least 12 words")
        }

        val attachments = linkedMapOf<String, File>()
        val vaultsWithLogoRefs = snapshot.vaults.map { vault ->
            val logoRef = vault.logoPath?.let { addAttachmentFileRef(it, attachments) }
            if (logoRef != null) vault.copy(logoPath = "ref:$logoRef") else vault
        }

        val backupItems = snapshot.items.map { item -> mapVaultItemToBackupItem(item, attachments) }

        val settingsEntries = buildList {
            addAll(exportSettingsSnapshot())
            addAll(exportIntruderCaptureSettings(attachments))
        }

        val payload = backupPayloadBuilder.buildPayload(
            version = 2,
            createdAt = createdAt,
            appVersion = getAppVersion(),
            vaults = vaultsWithLogoRefs,
            folders = snapshot.folders,
            items = backupItems,
            settings = settingsEntries,
            tags = snapshot.tags,
            itemTags = snapshot.itemTags
        )
        val estimatedTotalBytes = estimateStreamingBackupPlainBytes(payload, attachments)
        onProgress?.invoke("Estimated backup data: ${humanReadableBytes(estimatedTotalBytes)}")

        val credentialBytes = "${String(masterKey)}|$generatedKeyText|$normalizedPhrase".toByteArray(Charsets.UTF_8)
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdfParams = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 4,
            memoryKiB = 16 * 1024,
            parallelism = 1
        )
        val dataKey = try {
            argon2id(
                input = credentialBytes,
                salt = salt,
                params = kdfParams,
                outLen = 32
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to derive full backup key", t)
        } finally {
            wipe(credentialBytes)
        }

        val fileBaseName = buildBackupBaseName(
            mode = BackupMode.NORMAL,
            scope = BackupScope.ENTIRE_APP,
            targetVaultName = null,
            createdAt = createdAt
        )
        val backupFile = File(backupDir, "$fileBaseName.$backupExtension")
        if (backupFile.exists()) {
            backupFile.delete()
        }
        val progressReporter = ByteProgressReporter(
            stage = "Encrypting full backup",
            onProgress = onProgress,
            totalBytes = estimatedTotalBytes
        )

        val header = FullNormalStreamHeader(
            version = fullNormalStreamVersion,
            createdAt = createdAt,
            backupId = backupId,
            includesSettings = true,
            kdf = kdfParams,
            salt = salt,
            nonce = nonce
        )

        try {
            FileOutputStream(backupFile).use { fos ->
                val buffered = BufferedOutputStream(fos, backupIoBufferSize)
                writeFullNormalStreamHeader(buffered, header)

                backupSegmentedEnvelope.openEncryptingStream(
                    out = buffered,
                    key = dataKey,
                    appendIntegrityTrailer = header.version >= 3,
                    onChunkPlainBytesWritten = { processed ->
                        progressReporter.report(processed)
                    }
                ).use { segmentedOut ->
                    ZipOutputStream(BufferedOutputStream(segmentedOut, backupIoBufferSize)).use { zipOut ->
                        onProgress?.invoke("Writing backup metadata...")
                        writePayloadZipEntry(zipOut, payload)
                        onProgress?.invoke("Archiving attachments...")
                        writeZipAttachmentEntries(zipOut, attachments, onProgress)
                    }
                }
            }
        } catch (t: Throwable) {
            backupFile.delete()
            val detail = t.message?.takeIf { it.isNotBlank() }
            val message = if (detail != null) "Unable to create full backup file: $detail" else "Unable to create full backup file"
            throw IllegalStateException(message, t)
        } finally {
            wipe(dataKey)
        }

        val backupDownloadUri = if (request.writeDownloadCopy) {
            val downloadSubDirectory = "Vault/Backups/Full Backup"
            onProgress?.invoke("Copying backup to Downloads...")
            writeFileToDownloads(
                fileName = backupFile.name,
                mimeType = "application/octet-stream",
                file = backupFile,
                replace = false,
                subDirectory = downloadSubDirectory
            )
        } else {
            null
        }

        if (request.writeDownloadCopy) {
            enforceRetention()
        }

        return BackupResult(
            file = backupFile,
            sizeBytes = backupFile.length(),
            recoveryPhrase = normalizedPhrase,
            phraseFile = null,
            downloadBackupUri = backupDownloadUri,
            downloadPhraseUri = null
        )
    }

    private suspend fun restoreNormalSingleStreamFromInput(
        input: InputStream,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        val result = try {
            val dataInput = DataInputStream(BufferedInputStream(input, backupIoBufferSize))
            onProgress?.invoke("Validating normal backup...")
            val parsedHeader = readNormalStreamHeader(dataInput) ?: return RestoreResult(false, "Invalid backup file")

            val pin = request.vaultPin ?: return RestoreResult(false, "Invalid credentials")
            val master1 = request.masterKey1 ?: return RestoreResult(false, "Invalid credentials")
            val credentialBytes = "${String(pin)}|${String(master1)}".toByteArray(Charsets.UTF_8)
            val key = try {
                argon2id(
                    input = credentialBytes,
                    salt = parsedHeader.salt,
                    params = parsedHeader.kdf,
                    outLen = 32
                )
            } catch (t: Throwable) {
                wipe(credentialBytes)
                return RestoreResult(false, "Unable to derive backup key")
            }
            wipe(credentialBytes)

            val attachmentPaths = mutableMapOf<String, String>()
            var payload: BackupArchive.BackupPayload? = null
            val decryptProgress = ByteProgressReporter(
                stage = "Decrypting normal backup",
                onProgress = onProgress
            )

            try {
                onProgress?.invoke("Decrypting backup stream...")
                openBackupZipInputStream(
                    dataInput = dataInput,
                    key = key,
                    nonce = parsedHeader.nonce,
                    streamVersion = parsedHeader.version,
                    onChunkPlainBytesRead = { processed ->
                        decryptProgress.report(processed)
                    }
                ).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var extractedCount = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name ?: ""
                            when {
                                entryName == "payload.json" -> {
                                    onProgress?.invoke("Reading backup metadata...")
                                    payload = decodePayloadWithLog(zipIn, onProgress)
                                }
                                entryName.startsWith("attachments/") -> {
                                    val ref = entryName.removePrefix("attachments/").trim()
                                    if (ref.isNotBlank()) {
                                        val attachmentIndex = extractedCount + 1
                                        onProgress?.invoke("Extracting attachment $attachmentIndex...")
                                        val outFile = File(context.filesDir, UUID.randomUUID().toString())
                                        FileOutputStream(outFile).use { output ->
                                            copyStreamWithStallGuard(zipIn, output) { copied ->
                                                onProgress?.invoke(
                                                    "Attachment $attachmentIndex: ${humanReadableBytes(copied)} extracted"
                                                )
                                            }
                                        }
                                        attachmentPaths[ref] = outFile.absolutePath
                                        extractedCount++
                                        onProgress?.invoke("Extracted attachment $extractedCount")
                                    }
                                }
                                else -> {
                                    drainStreamWithStallGuard(zipIn)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Exception) {
                wipe(key)
                cleanupAttachmentFiles(attachmentPaths.values)
                val root = e.rootCause()
                return if (isAuthFailure(root)) {
                    RestoreResult(false, "Invalid credentials")
                } else {
                    invalidPayloadResult(root)
                }
            }

            wipe(key)

            val finalPayload = payload ?: run {
                cleanupAttachmentFiles(attachmentPaths.values)
                return RestoreResult(false, "Invalid backup payload")
            }
            onProgress?.invoke("Applying restored data...")
            val applyResult = applyRestoredPayload(
                payload = finalPayload,
                attachmentPaths = attachmentPaths,
                scope = BackupScope.SINGLE_VAULT,
                targetVaultId = parsedHeader.targetVaultId,
                includesSettings = false,
                onProgress = onProgress
            )
            if (!applyResult.success) {
                cleanupAttachmentFiles(attachmentPaths.values)
            }
            return applyResult
        } catch (_: Exception) {
            return RestoreResult(false, "Invalid backup file")
        }

        return result
    }

    private suspend fun restoreExtremeStreamFromInput(
        input: InputStream,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        if (!request.biometricConfirmed) {
            return RestoreResult(false, "Fingerprint authentication required")
        }

        val result = try {
            val dataInput = DataInputStream(BufferedInputStream(input, backupIoBufferSize))
            onProgress?.invoke("Validating extreme backup...")
            val header = readExtremeStreamHeader(dataInput) ?: return RestoreResult(false, "Invalid backup file")

            val fingerprintInput = securityManager.getDeviceFingerprintHash()
            if (fingerprintInput.isEmpty()) {
                return RestoreResult(false, "Unable to read device fingerprint key")
            }

            val key = try {
                argon2id(
                    input = fingerprintInput,
                    salt = header.salt,
                    params = header.kdf,
                    outLen = 32
                )
            } catch (_: Throwable) {
                return RestoreResult(false, "Backup cannot be restored on this device")
            }

            val attachmentPaths = mutableMapOf<String, String>()
            var payload: BackupArchive.BackupPayload? = null
            val decryptProgress = ByteProgressReporter(
                stage = "Decrypting extreme backup",
                onProgress = onProgress
            )

            try {
                onProgress?.invoke("Decrypting backup stream...")
                openBackupZipInputStream(
                    dataInput = dataInput,
                    key = key,
                    nonce = header.nonce,
                    streamVersion = header.version,
                    onChunkPlainBytesRead = { processed ->
                        decryptProgress.report(processed)
                    }
                ).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var extractedCount = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name ?: ""
                            when {
                                entryName == "payload.json" -> {
                                    onProgress?.invoke("Reading backup metadata...")
                                    payload = decodePayloadWithLog(zipIn, onProgress)
                                }
                                entryName.startsWith("attachments/") -> {
                                    val ref = entryName.removePrefix("attachments/").trim()
                                    if (ref.isNotBlank()) {
                                        val attachmentIndex = extractedCount + 1
                                        onProgress?.invoke("Extracting attachment $attachmentIndex...")
                                        val outFile = File(context.filesDir, UUID.randomUUID().toString())
                                        FileOutputStream(outFile).use { output ->
                                            copyStreamWithStallGuard(zipIn, output) { copied ->
                                                onProgress?.invoke(
                                                    "Attachment $attachmentIndex: ${humanReadableBytes(copied)} extracted"
                                                )
                                            }
                                        }
                                        attachmentPaths[ref] = outFile.absolutePath
                                        extractedCount++
                                        onProgress?.invoke("Extracted attachment $extractedCount")
                                    }
                                }
                                else -> {
                                    drainStreamWithStallGuard(zipIn)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Exception) {
                wipe(key)
                cleanupAttachmentFiles(attachmentPaths.values)
                val root = e.rootCause()
                return if (isAuthFailure(root)) {
                    RestoreResult(false, "Backup cannot be restored on this device")
                } else {
                    invalidPayloadResult(root)
                }
            }

            wipe(key)

            val finalPayload = payload ?: run {
                cleanupAttachmentFiles(attachmentPaths.values)
                return RestoreResult(false, "Invalid backup payload")
            }
            onProgress?.invoke("Applying restored data...")
            val applyResult = applyRestoredPayload(
                payload = finalPayload,
                attachmentPaths = attachmentPaths,
                scope = header.scope,
                targetVaultId = header.targetVaultId,
                includesSettings = header.includesSettings,
                onProgress = onProgress
            )
            if (!applyResult.success) {
                cleanupAttachmentFiles(attachmentPaths.values)
            }
            return applyResult
        } catch (_: Exception) {
            return RestoreResult(false, "Invalid backup file")
        }

        return result
    }

    private suspend fun restoreNormalEntireStreamFromInput(
        input: InputStream,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        val result = try {
            val dataInput = DataInputStream(BufferedInputStream(input, backupIoBufferSize))
            onProgress?.invoke("Validating full backup...")
            val header = readFullNormalStreamHeader(dataInput) ?: return RestoreResult(false, "Invalid backup file")

            val masterKey = request.masterKey1 ?: return RestoreResult(false, "Invalid credentials")
            val generatedKey = request.masterKey2 ?: return RestoreResult(false, "Invalid credentials")
            val normalizedPhrase = RecoveryPhrase.normalize(request.manualPhrase ?: return RestoreResult(false, "Invalid credentials"))
            if (!isValidRecoveryPhrase(normalizedPhrase)) {
                return RestoreResult(false, "Recovery phrase must contain at least 12 words")
            }
            if (!isValidFullGeneratedKey(String(generatedKey))) {
                return RestoreResult(false, "Invalid generated key")
            }
            val credentialBytes = "${String(masterKey)}|${String(generatedKey)}|$normalizedPhrase".toByteArray(Charsets.UTF_8)

            val key = try {
                argon2id(
                    input = credentialBytes,
                    salt = header.salt,
                    params = header.kdf,
                    outLen = 32
                )
            } catch (t: Throwable) {
                wipe(credentialBytes)
                return RestoreResult(false, "Unable to derive backup key")
            }
            wipe(credentialBytes)

            val attachmentPaths = mutableMapOf<String, String>()
            var payload: BackupArchive.BackupPayload? = null
            val decryptProgress = ByteProgressReporter(
                stage = "Decrypting full backup",
                onProgress = onProgress
            )

            try {
                onProgress?.invoke("Decrypting backup stream...")
                openBackupZipInputStream(
                    dataInput = dataInput,
                    key = key,
                    nonce = header.nonce,
                    streamVersion = header.version,
                    onChunkPlainBytesRead = { processed ->
                        decryptProgress.report(processed)
                    }
                ).use { zipIn ->
                    var entry = zipIn.nextEntry
                    var extractedCount = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val entryName = entry.name ?: ""
                            when {
                                entryName == "payload.json" -> {
                                    onProgress?.invoke("Reading backup metadata...")
                                    payload = decodePayloadWithLog(zipIn, onProgress)
                                }
                                entryName.startsWith("attachments/") -> {
                                    val ref = entryName.removePrefix("attachments/").trim()
                                    if (ref.isNotBlank()) {
                                        val attachmentIndex = extractedCount + 1
                                        onProgress?.invoke("Extracting attachment $attachmentIndex...")
                                        val outFile = File(context.filesDir, UUID.randomUUID().toString())
                                        FileOutputStream(outFile).use { output ->
                                            copyStreamWithStallGuard(zipIn, output) { copied ->
                                                onProgress?.invoke(
                                                    "Attachment $attachmentIndex: ${humanReadableBytes(copied)} extracted"
                                                )
                                            }
                                        }
                                        attachmentPaths[ref] = outFile.absolutePath
                                        extractedCount++
                                        onProgress?.invoke("Extracted attachment $extractedCount")
                                    }
                                }
                                else -> {
                                    drainStreamWithStallGuard(zipIn)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } catch (e: Exception) {
                wipe(key)
                cleanupAttachmentFiles(attachmentPaths.values)
                val root = e.rootCause()
                return if (isAuthFailure(root)) {
                    RestoreResult(false, "Invalid credentials")
                } else {
                    invalidPayloadResult(root)
                }
            }

            wipe(key)

            val finalPayload = payload ?: run {
                cleanupAttachmentFiles(attachmentPaths.values)
                return RestoreResult(false, "Invalid backup payload")
            }
            onProgress?.invoke("Applying restored data...")
            val applyResult = applyRestoredPayload(
                payload = finalPayload,
                attachmentPaths = attachmentPaths,
                scope = BackupScope.ENTIRE_APP,
                targetVaultId = null,
                includesSettings = header.includesSettings,
                onProgress = onProgress
            )
            if (!applyResult.success) {
                cleanupAttachmentFiles(attachmentPaths.values)
            }
            return applyResult
        } catch (_: Exception) {
            return RestoreResult(false, "Invalid backup file")
        }

        return result
    }

    private suspend fun applyRestoredPayload(
        payload: BackupArchive.BackupPayload,
        attachmentPaths: Map<String, String>,
        scope: BackupScope,
        targetVaultId: Int?,
        includesSettings: Boolean,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        val result = backupRestoreEngine.applyRestoredPayload(
            payload = payload,
            attachmentPaths = attachmentPaths,
            scope = scope,
            targetVaultId = targetVaultId,
            includesSettings = includesSettings,
            onProgress = onProgress,
            restoreSettingsSnapshot = ::restoreSettingsSnapshot,
            restoreIntruderCaptureSnapshot = ::restoreIntruderCaptureSnapshot
        )
        return RestoreResult(
            success = result.success,
            error = result.error
        )
    }

    private fun mapVaultItemToBackupItem(
        item: VaultItem,
        attachments: MutableMap<String, File>
    ): BackupArchive.BackupItem {
        val normalizedType = item.type.uppercase()
        val content = if (fileLikeItemTypes.contains(normalizedType)) {
            // For FILE/IMAGE entries, content is not needed and may hold oversized legacy data.
            null
        } else {
            decryptFieldForBackup(item.vaultId, item.content)
        }
        val fileRef = item.filePath?.let { addAttachmentFileRef(it, attachments) }
        val logoRef = item.logoPath?.let { addAttachmentFileRef(it, attachments) }
        return BackupArchive.BackupItem(
            id = item.id,
            vaultId = item.vaultId,
            type = item.type,
            name = item.name,
            description = item.description,
            content = content,
            username = decryptFieldForBackup(item.vaultId, item.username),
            passCategory = item.passCategory,
            link = item.link,
            fileRef = fileRef,
            logoRef = logoRef,
            extension = item.extension,
            email = decryptFieldForBackup(item.vaultId, item.email),
            phoneNumber = decryptFieldForBackup(item.vaultId, item.phoneNumber),
            folderId = item.folderId,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }

    private fun decryptFieldForBackup(vaultId: Int, value: String?): String? {
        if (value.isNullOrEmpty()) return value
        if (value.length > maxEncryptedFieldLengthForBackupDecrypt) {
            // Avoid huge Base64 decode allocations on malformed/legacy oversized rows.
            return value
        }
        return try {
            securityManager.decryptForVault(vaultId, value)
        } catch (_: Exception) {
            value
        }
    }

    suspend fun restoreBackupFromUri(
        uri: Uri,
        credential: CharArray,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        return restoreBackupFromUri(
            uri,
            RestoreRequest(masterKey1 = credential),
            onProgress = onProgress
        )
    }

    suspend fun restoreBackupFromUri(
        uri: Uri,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext RestoreResult(false, "Unable to read backup")
        input.use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
            if (isExtremeStreamInput(buffered)) {
                return@withContext restoreExtremeStreamFromInput(buffered, request, onProgress)
            }
            restoreBackupFromInput(buffered, request, onProgress)
        }
    }

    private suspend fun restoreBackupFromFile(
        file: File,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.isFile) {
            return@withContext RestoreResult(false, "Backup file is missing")
        }
        FileInputStream(file).use { raw ->
            restoreBackupFromInput(raw, request, onProgress)
        }
    }

    private suspend fun restoreBackupFromInput(
        input: InputStream,
        request: RestoreRequest,
        onProgress: ((String) -> Unit)? = null
    ): RestoreResult {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, backupIoBufferSize)
        if (isNormalStreamInput(buffered)) {
            return restoreNormalSingleStreamFromInput(buffered, request, onProgress)
        }
        if (isFullNormalStreamInput(buffered)) {
            return restoreNormalEntireStreamFromInput(buffered, request, onProgress)
        }
        if (isExtremeStreamInput(buffered)) {
            return restoreExtremeStreamFromInput(buffered, request, onProgress)
        }
        val bytes = readBytesWithLimit(buffered, maxLegacyContainerBytes)
            ?: return RestoreResult(false, "Backup is too large or unreadable")
        onProgress?.invoke("Restoring legacy backup format...")
        return restoreBackupFromBytes(bytes, request)
    }

    suspend fun restoreBackupFromBytes(bytes: ByteArray, credential: CharArray): RestoreResult {
        return restoreBackupFromBytes(bytes, RestoreRequest(masterKey1 = credential))
    }

    suspend fun restoreBackupFromBytes(bytes: ByteArray, request: RestoreRequest): RestoreResult = withContext(Dispatchers.IO) {
        if (isNormalStreamBytes(bytes)) {
            return@withContext restoreNormalSingleStreamFromInput(bytes.inputStream(), request)
        }
        if (isFullNormalStreamBytes(bytes)) {
            return@withContext restoreNormalEntireStreamFromInput(bytes.inputStream(), request)
        }
        if (isExtremeStreamBytes(bytes)) {
            return@withContext restoreExtremeStreamFromInput(bytes.inputStream(), request)
        }

        val (content, storedHash) = BackupSerializer.splitHashArea(bytes)
        val computed = BackupSerializer.computeSha256(content)
        if (!MessageDigest.isEqual(storedHash, computed)) {
            return@withContext RestoreResult(false, "Backup corrupted or tampered")
        }

        val backupFile = runCatching { BackupSerializer.deserialize(bytes) }.getOrElse {
            return@withContext RestoreResult(false, "Invalid backup file")
        }

        if (backupFile.header.mode == BackupMode.EXTREME) {
            if (!request.biometricConfirmed) {
                return@withContext RestoreResult(false, "Fingerprint authentication required")
            }
            val expected = backupFile.header.deviceFingerprintHash
            val candidates = securityManager.getDeviceFingerprintHashCandidates()
            val matched = expected != null && candidates.any { current ->
                MessageDigest.isEqual(expected, current)
            }
            if (!matched) {
                return@withContext RestoreResult(false, "Backup cannot be restored on this device")
            }
        }

        val credential = buildRestoreCredential(backupFile, request)
            ?: return@withContext RestoreResult(false, "Invalid credentials")

        val dek = unwrapDataKey(backupFile, credential)
            ?: return@withContext RestoreResult(false, "Invalid credentials")

        val combined = backupFile.cipherText + backupFile.authTag
        val decrypted = try {
            aesGcmDecrypt(dek, backupFile.header.payloadNonce, combined)
        } catch (_: Exception) {
            wipe(dek)
            wipe(credential)
            return@withContext RestoreResult(false, "Invalid credentials")
        }

        val decompressed = try {
            val size = Zstd.getFrameContentSize(decrypted)
            if (size <= 0 || size == -1L || size == -2L || size > 500_000_000) {
                return@withContext RestoreResult(false, "Invalid backup payload")
            }
            Zstd.decompress(decrypted, size.toInt())
        } catch (_: Exception) {
            wipe(dek)
            wipe(decrypted)
            wipe(credential)
            return@withContext RestoreResult(false, "Invalid backup payload")
        }

        val bundle = runCatching { BackupArchive.unpack(decompressed) }.getOrElse {
            wipe(dek)
            wipe(decrypted)
            wipe(decompressed)
            wipe(credential)
            return@withContext RestoreResult(false, "Invalid backup archive")
        }

        val attachmentPaths = restoreAttachments(bundle.attachments)
        val applyResult = applyRestoredPayload(
            payload = bundle.payload,
            attachmentPaths = attachmentPaths,
            scope = backupFile.header.scope,
            targetVaultId = backupFile.header.targetVaultId,
            includesSettings = backupFile.header.scope == BackupScope.ENTIRE_APP && backupFile.header.includesSettings
        )
        if (!applyResult.success) {
            cleanupAttachmentFiles(attachmentPaths.values)
        }

        wipe(dek)
        wipe(decrypted)
        wipe(decompressed)
        wipe(credential)

        applyResult
    }

    suspend fun verifyBackup(uri: Uri): BackupHealthReport = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: return@withContext BackupHealthReport(
                isValid = false,
                message = "Unable to read backup file.",
                integrityPassed = false
            )
        input.use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
            if (isNormalStreamInput(buffered)) {
                val dataInput = DataInputStream(buffered)
                val header = runCatching { readNormalStreamHeader(dataInput) }.getOrNull()
                    ?: return@withContext BackupHealthReport(
                        isValid = false,
                        message = "Invalid normal backup header.",
                        mode = BackupMode.NORMAL,
                        scope = BackupScope.SINGLE_VAULT,
                        integrityPassed = false
                    )
                val envelopeValid = backupSegmentedEnvelope.verifyEnvelope(
                    input = dataInput,
                    requireIntegrityTrailer = header.version >= 3
                )
                return@withContext BackupHealthReport(
                    isValid = envelopeValid,
                    message = if (envelopeValid) "Backup verified." else "Backup payload envelope is incomplete.",
                    fileVersion = header.version,
                    createdAt = header.createdAt,
                    mode = BackupMode.NORMAL,
                    scope = BackupScope.SINGLE_VAULT,
                    estimatedVaultCount = 1,
                    integrityPassed = envelopeValid
                )
            }
            if (isFullNormalStreamInput(buffered)) {
                val dataInput = DataInputStream(buffered)
                val header = runCatching { readFullNormalStreamHeader(dataInput) }.getOrNull()
                    ?: return@withContext BackupHealthReport(
                        isValid = false,
                        message = "Invalid full backup header.",
                        mode = BackupMode.NORMAL,
                        scope = BackupScope.ENTIRE_APP,
                        integrityPassed = false
                    )
                val envelopeValid = backupSegmentedEnvelope.verifyEnvelope(
                    input = dataInput,
                    requireIntegrityTrailer = header.version >= 3
                )
                return@withContext BackupHealthReport(
                    isValid = envelopeValid,
                    message = if (envelopeValid) "Backup verified." else "Backup payload envelope is incomplete.",
                    fileVersion = header.version,
                    createdAt = header.createdAt,
                    mode = BackupMode.NORMAL,
                    scope = BackupScope.ENTIRE_APP,
                    estimatedVaultCount = null,
                    integrityPassed = envelopeValid
                )
            }
            if (isExtremeStreamInput(buffered)) {
                val dataInput = DataInputStream(buffered)
                val header = runCatching { readExtremeStreamHeader(dataInput) }.getOrNull()
                    ?: return@withContext BackupHealthReport(
                        isValid = false,
                        message = "Invalid extreme backup header.",
                        mode = BackupMode.EXTREME,
                        integrityPassed = false
                    )
                val envelopeValid = backupSegmentedEnvelope.verifyEnvelope(
                    input = dataInput,
                    requireIntegrityTrailer = header.version >= 3
                )
                return@withContext BackupHealthReport(
                    isValid = envelopeValid,
                    message = if (envelopeValid) "Backup verified." else "Backup payload envelope is incomplete.",
                    fileVersion = header.version,
                    createdAt = header.createdAt,
                    mode = BackupMode.EXTREME,
                    scope = header.scope,
                    estimatedVaultCount = if (header.scope == BackupScope.SINGLE_VAULT) 1 else null,
                    integrityPassed = envelopeValid
                )
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            readBytesWithLimit(it, maxLegacyContainerBytes)
        } ?: return@withContext BackupHealthReport(
            isValid = false,
            message = "Unable to read backup payload.",
            integrityPassed = false
        )
        return@withContext try {
            val (content, storedHash) = BackupSerializer.splitHashArea(bytes)
            val computed = BackupSerializer.computeSha256(content)
            val integrity = MessageDigest.isEqual(storedHash, computed)
            val backupFile = runCatching { BackupSerializer.deserialize(bytes) }.getOrNull()
            BackupHealthReport(
                isValid = integrity && backupFile != null,
                message = if (integrity) "Backup verified." else "Backup integrity check failed.",
                fileVersion = backupFile?.header?.version,
                createdAt = backupFile?.header?.createdAt,
                mode = backupFile?.header?.mode,
                scope = backupFile?.header?.scope,
                estimatedVaultCount = backupFile?.header?.targetVaultId?.let { 1 },
                integrityPassed = integrity
            )
        } catch (e: Exception) {
            BackupHealthReport(
                isValid = false,
                message = e.message ?: "Backup verification failed.",
                integrityPassed = false
            )
        }
    }

    suspend fun checkBackupHealth(uri: Uri): Boolean {
        val report = verifyBackup(uri)
        return report.isValid && report.integrityPassed
    }

    suspend fun readBackupMode(uri: Uri): BackupMode? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
            if (isNormalStreamInput(buffered)) {
                return@withContext BackupMode.NORMAL
            }
            if (isFullNormalStreamInput(buffered)) {
                return@withContext BackupMode.NORMAL
            }
            if (isExtremeStreamInput(buffered)) {
                return@withContext BackupMode.EXTREME
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBytesWithLimit(it, maxLegacyContainerBytes) }
            ?: return@withContext null
        return@withContext try {
            BackupSerializer.deserialize(bytes).header.mode
        } catch (_: Exception) {
            null
        }
    }

    suspend fun readBackupDescriptor(uri: Uri): BackupDescriptor? = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        input.use { raw ->
            val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
            if (isNormalStreamInput(buffered)) {
                val header = runCatching { readNormalStreamHeader(DataInputStream(buffered)) }.getOrNull()
                    ?: return@withContext null
                return@withContext BackupDescriptor(
                    mode = BackupMode.NORMAL,
                    profile = BackupProfile.NORMAL_SINGLE,
                    scope = BackupScope.SINGLE_VAULT,
                    targetVaultId = header.targetVaultId,
                    backupId = header.backupId,
                    includesSettings = false,
                    requiresVaultPin = true,
                    requiresMasterKey1 = true,
                    requiresMasterKey2 = false,
                    requiresPhraseFile = false,
                    requiresBiometric = false,
                    isLegacy = false
                )
            }
            if (isFullNormalStreamInput(buffered)) {
                val header = runCatching { readFullNormalStreamHeader(DataInputStream(buffered)) }.getOrNull()
                    ?: return@withContext null
                return@withContext BackupDescriptor(
                    mode = BackupMode.NORMAL,
                    profile = BackupProfile.NORMAL_ENTIRE,
                    scope = BackupScope.ENTIRE_APP,
                    targetVaultId = null,
                    backupId = header.backupId,
                    includesSettings = header.includesSettings,
                    requiresVaultPin = false,
                    requiresMasterKey1 = true,
                    requiresMasterKey2 = true,
                    requiresPhraseFile = false,
                    requiresBiometric = false,
                    isLegacy = false
                )
            }
            if (isExtremeStreamInput(buffered)) {
                val header = runCatching { readExtremeStreamHeader(DataInputStream(buffered)) }.getOrNull()
                    ?: return@withContext null
                return@withContext BackupDescriptor(
                    mode = BackupMode.EXTREME,
                    profile = BackupProfile.EXTREME,
                    scope = header.scope,
                    targetVaultId = header.targetVaultId,
                    backupId = header.backupId,
                    includesSettings = header.includesSettings,
                    requiresVaultPin = false,
                    requiresMasterKey1 = false,
                    requiresMasterKey2 = false,
                    requiresPhraseFile = false,
                    requiresBiometric = true,
                    isLegacy = false
                )
            }
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { readBytesWithLimit(it, maxLegacyContainerBytes) }
            ?: return@withContext null
        return@withContext try {
            val backupFile = BackupSerializer.deserialize(bytes)
            buildDescriptor(backupFile)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun exportBackup(file: File, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            FileInputStream(file).use { input ->
                input.copyTo(out, streamCopyBufferSize)
            }
            out.flush()
            true
        } ?: false
    }

    fun getLatestBackupInfo(): BackupInfo? {
        val latest = listBackups().maxByOrNull { it.lastModified() } ?: return null
        val streamInfo = runCatching {
            FileInputStream(latest).use { input ->
                val buffered = BufferedInputStream(input, backupIoBufferSize)
                if (isNormalStreamInput(buffered)) {
                    val header = readNormalStreamHeader(DataInputStream(buffered))
                    header?.let { Triple(BackupMode.NORMAL, BackupScope.SINGLE_VAULT, it.backupId) }
                } else if (isFullNormalStreamInput(buffered)) {
                    val header = readFullNormalStreamHeader(DataInputStream(buffered))
                    header?.let { Triple(BackupMode.NORMAL, BackupScope.ENTIRE_APP, it.backupId) }
                } else if (isExtremeStreamInput(buffered)) {
                    val header = readExtremeStreamHeader(DataInputStream(buffered))
                    header?.let { Triple(BackupMode.EXTREME, it.scope, it.backupId) }
                } else {
                    null
                }
            }
        }.getOrNull()
        val backupFile = if (streamInfo == null) {
            runCatching {
                val bytes = readFileBytesWithLimit(latest, maxLegacyContainerBytes) ?: return@runCatching null
                BackupSerializer.deserialize(bytes)
            }.getOrNull()
        } else {
            null
        }
        val mode = when {
            streamInfo != null -> streamInfo.first
            backupFile != null -> backupFile.header.mode
            latest.name.contains("_extreme") -> BackupMode.EXTREME
            else -> BackupMode.NORMAL
        }
        val scope = when {
            streamInfo != null -> streamInfo.second
            backupFile != null -> backupFile.header.scope
            else -> BackupScope.ENTIRE_APP
        }
        val isHealthy = isBackupHealthy(latest)
        return BackupInfo(
            file = latest,
            sizeBytes = latest.length(),
            createdAt = latest.lastModified(),
            mode = mode,
            scope = scope,
            isHealthy = isHealthy
        )
    }

    fun listBackups(): List<File> {
        if (!backupDir.exists()) return emptyList()
        return backupDir.listFiles { f -> f.extension == backupExtension }?.toList().orEmpty()
    }

    fun listBackupHistory(): List<BackupHistoryEntry> {
        val files = listBackups().sortedByDescending { it.lastModified() }
        return files.mapNotNull { file ->
            val streamInfo = runCatching {
                FileInputStream(file).use { input ->
                    val buffered = BufferedInputStream(input, backupIoBufferSize)
                    when {
                        isNormalStreamInput(buffered) -> {
                            val header = readNormalStreamHeader(DataInputStream(buffered))
                            header?.let { Quadruple(BackupMode.NORMAL, BackupScope.SINGLE_VAULT, header.createdAt, header.targetVaultId) }
                        }
                        isFullNormalStreamInput(buffered) -> {
                            val header = readFullNormalStreamHeader(DataInputStream(buffered))
                            header?.let { Quadruple(BackupMode.NORMAL, BackupScope.ENTIRE_APP, header.createdAt, null) }
                        }
                        isExtremeStreamInput(buffered) -> {
                            val header = readExtremeStreamHeader(DataInputStream(buffered))
                            header?.let { Quadruple(BackupMode.EXTREME, header.scope, header.createdAt, header.targetVaultId) }
                        }
                        else -> null
                    }
                }
            }.getOrNull()

            val fallback = if (streamInfo == null) {
                runCatching {
                    val bytes = readFileBytesWithLimit(file, maxLegacyContainerBytes) ?: return@runCatching null
                    val backupFile = BackupSerializer.deserialize(bytes)
                    Quadruple(
                        backupFile.header.mode,
                        backupFile.header.scope,
                        backupFile.header.createdAt,
                        backupFile.header.targetVaultId
                    )
                }.getOrNull()
            } else {
                null
            }

            val parsed = streamInfo ?: fallback
            if (parsed == null) {
                null
            } else {
                BackupHistoryEntry(
                    file = file,
                    sizeBytes = file.length(),
                    createdAt = parsed.third,
                    mode = parsed.first,
                    scope = parsed.second,
                    targetVaultId = parsed.fourth,
                    isHealthy = isBackupHealthy(file)
                )
            }
        }
    }

    fun deleteBackupEntry(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching { file.delete() }.getOrDefault(false)
    }

    private suspend fun collectSnapshot(scope: BackupScope, targetVaultId: Int?): Snapshot {
        val allVaults = dao.getAllVaultsOnce()
        val vaults = if (scope == BackupScope.SINGLE_VAULT) {
            val id = targetVaultId ?: throw IllegalArgumentException("Vault id is required for single-vault backup")
            allVaults.filter { it.id == id }
        } else {
            allVaults
        }

        if (vaults.isEmpty()) {
            return Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null)
        }

        val vaultIds = vaults.map { it.id }.toSet()
        val folders = dao.getAllFoldersOnce().filter { vaultIds.contains(it.vaultId) }
        val items = dao.getAllItemsOnce().filter { vaultIds.contains(it.vaultId) }
        val tags = dao.getAllTagsOnce().filter { vaultIds.contains(it.vaultId) }
        val itemIds = items.map { it.id }.toSet()
        val itemTags = dao.getAllItemTagRefsOnce().filter { itemIds.contains(it.itemId) }

        val targetName = if (scope == BackupScope.SINGLE_VAULT) vaults.firstOrNull()?.name else null
        return Snapshot(
            vaults = vaults,
            items = items,
            folders = folders,
            tags = tags,
            itemTags = itemTags,
            targetVaultName = targetName
        )
    }

    private fun resolveProfile(mode: BackupMode, scope: BackupScope): BackupProfile {
        return when (mode) {
            BackupMode.EXTREME -> BackupProfile.EXTREME
            BackupMode.NORMAL -> if (scope == BackupScope.SINGLE_VAULT) BackupProfile.NORMAL_SINGLE else BackupProfile.NORMAL_ENTIRE
        }
    }

    private fun buildCompositeCredential(
        request: CreateRequest,
        profile: BackupProfile,
        phrase: String?
    ): CharArray {
        if (request.mode == BackupMode.EXTREME) {
            return CharArray(0)
        }

        val values = when (profile) {
            BackupProfile.NORMAL_SINGLE -> {
                val pin = request.vaultPin ?: throw IllegalArgumentException("Vault PIN is required")
                val master1 = request.masterKey1 ?: throw IllegalArgumentException("Master key is required")
                listOf(String(pin), String(master1))
            }
            BackupProfile.NORMAL_ENTIRE -> {
                val normalizedPhrase = phrase ?: throw IllegalArgumentException("Recovery phrase is required")
                val master1 = request.masterKey1 ?: throw IllegalArgumentException("Master key 1 is required")
                val master2 = request.masterKey2 ?: throw IllegalArgumentException("Master key 2 is required")
                listOf(String(master1), String(master2), normalizedPhrase)
            }
            else -> {
                val normalizedPhrase = phrase ?: throw IllegalArgumentException("Recovery phrase is required")
                val master1 = request.masterKey1 ?: throw IllegalArgumentException("Master key is required")
                listOf(String(master1), normalizedPhrase)
            }
        }
        return values.joinToString("|").toCharArray()
    }

    private fun buildRestoreCredential(
        backupFile: BackupFile,
        request: RestoreRequest
    ): CharArray? {
        val header = backupFile.header

        if (header.profile == BackupProfile.LEGACY || header.version < 6) {
            return request.masterKey1 ?: request.manualPhrase?.toCharArray()
        }

        if (header.mode == BackupMode.EXTREME) {
            return CharArray(0)
        }

        return when (header.profile) {
            BackupProfile.NORMAL_SINGLE -> {
                val pin = request.vaultPin ?: return null
                val master1 = request.masterKey1 ?: return null
                listOf(String(pin), String(master1)).joinToString("|").toCharArray()
            }
            BackupProfile.NORMAL_ENTIRE -> {
                val phrase = when {
                    !request.manualPhrase.isNullOrBlank() -> RecoveryPhrase.normalize(request.manualPhrase)
                    request.phraseFileUri != null -> {
                        val phraseSecret = request.masterKey2 ?: return null
                        readPhraseFromSidecar(request.phraseFileUri, header.backupId, phraseSecret)
                    }
                    else -> null
                } ?: return null

                if (header.phraseHash != null) {
                    val computed = BackupSerializer.computeSha256(phrase.toByteArray(Charsets.UTF_8))
                    if (!MessageDigest.isEqual(header.phraseHash, computed)) {
                        return null
                    }
                }

                val master1 = request.masterKey1 ?: return null
                val master2 = request.masterKey2 ?: return null
                listOf(String(master1), String(master2), phrase).joinToString("|").toCharArray()
            }
            else -> {
                request.masterKey1 ?: return null
            }
        }
    }

    private fun buildDescriptor(backupFile: BackupFile): BackupDescriptor {
        val header = backupFile.header
        val isLegacy = header.profile == BackupProfile.LEGACY || header.version < 6
        return BackupDescriptor(
            mode = header.mode,
            profile = header.profile,
            scope = header.scope,
            targetVaultId = header.targetVaultId,
            backupId = header.backupId,
            includesSettings = header.includesSettings,
            requiresVaultPin = !isLegacy && header.profile == BackupProfile.NORMAL_SINGLE,
            requiresMasterKey1 = isLegacy || header.profile == BackupProfile.NORMAL_SINGLE || header.profile == BackupProfile.NORMAL_ENTIRE,
            requiresMasterKey2 = !isLegacy && header.profile == BackupProfile.NORMAL_ENTIRE,
            requiresPhraseFile = !isLegacy && header.profile == BackupProfile.NORMAL_ENTIRE,
            requiresBiometric = header.mode == BackupMode.EXTREME,
            isLegacy = isLegacy
        )
    }

    private fun createPhraseSidecar(backupId: String, phrase: String, secret: CharArray): ByteArray {
        val salt = BackupSerializer.computeSha256(("phrase:" + backupId).toByteArray(Charsets.UTF_8))
        val key = argon2id(
            input = String(secret).toByteArray(Charsets.UTF_8),
            salt = salt,
            params = KdfParams(KdfAlgorithm.ARGON2ID, iterations = 2, memoryKiB = 32 * 1024, parallelism = 1),
            outLen = 32
        )
        val nonce = randomBytes(12)
        val cipher = aesGcmEncrypt(key, nonce, phrase.toByteArray(Charsets.UTF_8))
        wipe(key)

        return BackupAuxJsonCodec.encodePhraseSidecar(
            BackupAuxJsonCodec.PhraseSidecarDto(
                version = 1,
                backupId = backupId,
                nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                cipher = Base64.encodeToString(cipher, Base64.NO_WRAP)
            )
        )
    }

    private fun readPhraseFromSidecar(uri: Uri, backupId: String, secret: CharArray): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val dto = BackupAuxJsonCodec.decodePhraseSidecar(bytes) ?: return null
        if (dto.version != 1) return null
        if (dto.backupId != backupId) return null

        val nonceB64 = dto.nonce
        val cipherB64 = dto.cipher
        if (nonceB64.isBlank() || cipherB64.isBlank()) return null

        val nonce = runCatching { Base64.decode(nonceB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val cipher = runCatching { Base64.decode(cipherB64, Base64.NO_WRAP) }.getOrNull() ?: return null

        val salt = BackupSerializer.computeSha256(("phrase:" + backupId).toByteArray(Charsets.UTF_8))
        val key = argon2id(
            input = String(secret).toByteArray(Charsets.UTF_8),
            salt = salt,
            params = KdfParams(KdfAlgorithm.ARGON2ID, iterations = 2, memoryKiB = 32 * 1024, parallelism = 1),
            outLen = 32
        )

        val plain = runCatching { aesGcmDecrypt(key, nonce, cipher) }.getOrNull()
        wipe(key)
        return plain?.let { RecoveryPhrase.normalize(String(it, Charsets.UTF_8)) }
    }

    private fun buildBackupBaseName(
        mode: BackupMode,
        scope: BackupScope,
        targetVaultName: String?,
        createdAt: Long
    ): String {
        if (scope == BackupScope.SINGLE_VAULT && mode == BackupMode.NORMAL) {
            val safeName = sanitizeName(targetVaultName ?: "vault")
            return "${safeName}_backup"
        }

        val scopeBase = if (scope == BackupScope.SINGLE_VAULT) {
            val safeName = sanitizeName(targetVaultName ?: "vault")
            "${safeName}_vault_backup"
        } else {
            "application_vault_entire_backup"
        }
        val modeSuffix = if (mode == BackupMode.EXTREME) "_extreme" else ""
        return "${scopeBase}${modeSuffix}_$createdAt"
    }

    private fun sanitizeName(input: String): String {
        return input.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64).ifBlank { "vault" }
    }

    private fun ensureBackupDir() {
        if (!backupDir.exists()) backupDir.mkdirs()
    }

    private fun unwrapDataKey(file: BackupFile, credential: CharArray): ByteArray? {
        file.header.keyWraps.forEach { wrap ->
            val kdfInput = deriveKdfInput(credential, file.header.mode, wrap.type)
            val derivedKey = argon2id(kdfInput, wrap.salt, wrap.kdf, 32)
            try {
                val dek = aesGcmDecrypt(derivedKey, wrap.nonce, wrap.wrappedKey)
                wipe(derivedKey)
                wipe(kdfInput)
                if (dek.size == 32) return dek
            } catch (_: Exception) {
                wipe(derivedKey)
                wipe(kdfInput)
            }
        }
        return null
    }

    private fun buildKeyWrap(
        type: KeyWrapType,
        mode: BackupMode,
        credential: CharArray,
        kdfParams: KdfParams,
        dataKey: ByteArray
    ): KeyWrap {
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdfInput = deriveKdfInput(credential, mode, type)
        val derivedKey = argon2id(kdfInput, salt, kdfParams, 32)
        val wrapped = aesGcmEncrypt(derivedKey, nonce, dataKey)
        wipe(derivedKey)
        wipe(kdfInput)
        return KeyWrap(
            type = type,
            kdf = kdfParams,
            salt = salt,
            nonce = nonce,
            wrappedKey = wrapped
        )
    }

    private fun deriveKdfInput(credential: CharArray, mode: BackupMode, wrapType: KeyWrapType): ByteArray {
        return backupKeyDerivation.deriveKdfInput(
            credential = credential,
            mode = mode,
            wrapType = wrapType,
            deviceFingerprintProvider = { securityManager.getDeviceFingerprintHash() }
        )
    }

    private fun argon2id(input: ByteArray, salt: ByteArray, params: KdfParams, outLen: Int): ByteArray {
        return backupKeyDerivation.argon2id(input, salt, params, outLen)
    }

    private fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        return backupStreamIO.aesGcmEncrypt(key, nonce, plain)
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, cipherText: ByteArray): ByteArray {
        return backupStreamIO.aesGcmDecrypt(key, nonce, cipherText)
    }

    private fun splitCipherTextAndTag(encrypted: ByteArray): Pair<ByteArray, ByteArray> {
        return backupStreamIO.splitCipherTextAndTag(encrypted)
    }

    private fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        random.nextBytes(out)
        return out
    }

    private fun storeAttachment(bytes: ByteArray, target: MutableMap<String, ByteArray>): String {
        val id = UUID.randomUUID().toString()
        target[id] = bytes
        return id
    }

    private fun restoreAttachments(attachments: Map<String, ByteArray>): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        attachments.forEach { (id, bytes) ->
            val name = UUID.randomUUID().toString()
            val file = File(context.filesDir, name)
            file.writeBytes(bytes)
            mapping[id] = file.absolutePath
        }
        return mapping
    }

    private fun createFullBackupKeyFileBytes(
        backupId: String,
        masterKey: String,
        generatedKey: String,
        phrase: String
    ): ByteArray {
        val normalizedPhrase = RecoveryPhrase.normalize(phrase)
        val digestInput = "$backupId|$masterKey|$generatedKey|$normalizedPhrase"
        val materialHash = BackupSerializer.computeSha256(digestInput.toByteArray(Charsets.UTF_8))
        val payloadBytes = BackupAuxJsonCodec.encodeFullKeyPayload(
            BackupAuxJsonCodec.FullKeyPayloadDto(
                version = fullKeyFileVersion,
                backupId = backupId,
                masterKey = masterKey,
                generatedKey = generatedKey,
                phrase = normalizedPhrase,
                wordCount = normalizedPhrase.split(Regex("\\s+")).size
            )
        )

        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdf = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 3,
            memoryKiB = 16 * 1024,
            parallelism = 1
        )
        val key = deriveFullKeyFileAesKey(backupId = backupId, salt = salt, kdf = kdf)
        val cipherText = try {
            aesGcmEncrypt(key, nonce, payloadBytes)
        } finally {
            wipe(key)
            wipe(payloadBytes)
        }

        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dos ->
            dos.write(fullKeyFileMagic)
            dos.writeInt(fullKeyFileVersion)
            writeSizedString(dos, backupId)
            dos.writeInt(kdf.algorithm.ordinal)
            dos.writeInt(kdf.iterations)
            dos.writeInt(kdf.memoryKiB)
            dos.writeInt(kdf.parallelism)
            writeSizedBytes(dos, salt)
            writeSizedBytes(dos, nonce)
            writeSizedBytes(dos, materialHash)
            writeSizedBytes(dos, cipherText)
            dos.flush()
        }
        return output.toByteArray()
    }

    private fun parseFullBackupKeyFileBytes(bytes: ByteArray): FullBackupKeyData? {
        if (
            bytes.size >= fullKeyFileMagic.size &&
            bytes.copyOfRange(0, fullKeyFileMagic.size).contentEquals(fullKeyFileMagic)
        ) {
            return runCatching { parseEncryptedFullBackupKeyFileBytes(bytes) }.getOrNull()
        }
        return runCatching { parseLegacyFullBackupKeyFileBytes(bytes) }.getOrNull()
    }

    private fun parseEncryptedFullBackupKeyFileBytes(bytes: ByteArray): FullBackupKeyData? {
        val dis = DataInputStream(BufferedInputStream(bytes.inputStream()))
        val magic = ByteArray(fullKeyFileMagic.size)
        dis.readFully(magic)
        if (!magic.contentEquals(fullKeyFileMagic)) return null

        val version = dis.readInt()
        if (version != fullKeyFileVersion) return null
        val backupId = readSizedString(dis, 1024)?.trim().orEmpty()
        if (backupId.isBlank()) return null

        val algIdx = dis.readInt()
        val algorithm = KdfAlgorithm.values().getOrElse(algIdx) { KdfAlgorithm.ARGON2ID }
        val iterations = dis.readInt().coerceAtLeast(1)
        val memoryKiB = dis.readInt().coerceAtLeast(8 * 1024)
        val parallelism = dis.readInt().coerceIn(1, 8)
        val salt = readSizedBytes(dis, 128) ?: return null
        val nonce = readSizedBytes(dis, 32) ?: return null
        if (nonce.size != 12) return null
        val expectedMaterialHash = readSizedBytes(dis, 64) ?: return null
        if (expectedMaterialHash.size != 32) return null
        val cipherText = readSizedBytes(dis, 64 * 1024) ?: return null

        val kdf = KdfParams(
            algorithm = algorithm,
            iterations = iterations,
            memoryKiB = memoryKiB,
            parallelism = parallelism
        )
        val key = deriveFullKeyFileAesKey(backupId = backupId, salt = salt, kdf = kdf)
        val plainBytes = try {
            aesGcmDecrypt(key, nonce, cipherText)
        } catch (_: Exception) {
            wipe(key)
            return null
        }
        wipe(key)

        val data = try {
            val payload = BackupAuxJsonCodec.decodeFullKeyPayload(plainBytes) ?: return null
            val payloadBackupId = payload.backupId.trim()
            if (payloadBackupId.isNotBlank() && payloadBackupId != backupId) {
                return null
            }
            val masterKey = payload.masterKey
            val generatedKey = payload.generatedKey
            val phrase = RecoveryPhrase.normalize(payload.phrase)
            parseFullKeyData(
                backupId = backupId,
                masterKey = masterKey,
                generatedKey = generatedKey,
                phrase = phrase,
                expectedMaterialHash = expectedMaterialHash
            )
        } finally {
            wipe(plainBytes)
        }
        return data
    }

    private fun parseLegacyFullBackupKeyFileBytes(bytes: ByteArray): FullBackupKeyData? {
        val payload = BackupAuxJsonCodec.decodeLegacyFullKeyFile(bytes) ?: return null
        if (payload.version != 1) return null

        val backupId = payload.backupId.trim()
        val masterKey = payload.masterKey
        val generatedKey = payload.generatedKey
        val phrase = RecoveryPhrase.normalize(payload.phrase)
        val expectedHashB64 = payload.materialHash
        if (expectedHashB64.isBlank()) return null
        val expectedHash = runCatching { Base64.decode(expectedHashB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        return parseFullKeyData(
            backupId = backupId,
            masterKey = masterKey,
            generatedKey = generatedKey,
            phrase = phrase,
            expectedMaterialHash = expectedHash
        )
    }

    private fun parseFullKeyData(
        backupId: String,
        masterKey: String,
        generatedKey: String,
        phrase: String,
        expectedMaterialHash: ByteArray
    ): FullBackupKeyData? {
        if (backupId.isBlank() || masterKey.isBlank() || generatedKey.isBlank() || phrase.isBlank()) return null
        if (masterKey.length !in 8..20) return null
        if (!isValidFullGeneratedKey(generatedKey)) return null
        if (!isValidRecoveryPhrase(phrase)) return null

        val digestInput = "$backupId|$masterKey|$generatedKey|$phrase"
        val computedHash = BackupSerializer.computeSha256(digestInput.toByteArray(Charsets.UTF_8))
        if (!MessageDigest.isEqual(expectedMaterialHash, computedHash)) return null

        return FullBackupKeyData(
            backupId = backupId,
            masterKey = masterKey,
            generatedKey = generatedKey,
            phrase = phrase
        )
    }

    private fun deriveFullKeyFileAesKey(
        backupId: String,
        salt: ByteArray,
        kdf: KdfParams
    ): ByteArray {
        val input = "$fullKeyDerivationLabel|${context.packageName}|$backupId".toByteArray(Charsets.UTF_8)
        return try {
            argon2id(
                input = input,
                salt = salt,
                params = kdf,
                outLen = 32
            )
        } finally {
            wipe(input)
        }
    }

    private fun isValidFullGeneratedKey(value: String): Boolean {
        if (value.length !in 20..30) return false
        return value.all { it.isLetterOrDigit() || it == '#' || it == '*' }
    }

    private fun isValidRecoveryPhrase(value: String): Boolean {
        val words = value
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        return words.size >= 12
    }

    private fun createPlainFullPackageZip(
        packageFile: File,
        backupFile: File,
        keyFile: File,
        onProgress: ((String) -> Unit)? = null
    ) {
        FileOutputStream(packageFile).use { fos ->
            ZipOutputStream(BufferedOutputStream(fos, backupIoBufferSize)).use { zipOut ->
                onProgress?.invoke("Adding encrypted key file...")
                writeZipFileEntry(zipOut, fullPackageKeyEntry, keyFile) { copied, total ->
                    onProgress?.invoke(progressMessage("Packaging key file", copied, total))
                }
                onProgress?.invoke("Adding backup container...")
                writeZipFileEntry(zipOut, fullPackageBackupEntry, backupFile) { copied, total ->
                    onProgress?.invoke(progressMessage("Packaging backup", copied, total))
                }
            }
        }
    }

    private fun createEncryptedFullPackageZip(
        packageFile: File,
        backupFile: File,
        keyFile: File,
        backupId: String,
        onProgress: ((String) -> Unit)? = null
    ) {
        val fingerprintInput = securityManager.getDeviceFingerprintHash()
        if (fingerprintInput.isEmpty()) {
            throw IllegalStateException("Unable to read device fingerprint key")
        }
        val salt = randomBytes(32)
        val nonce = randomBytes(12)
        val kdfParams = KdfParams(
            algorithm = KdfAlgorithm.ARGON2ID,
            iterations = 4,
            memoryKiB = 16 * 1024,
            parallelism = 1
        )
        val dataKey = try {
            argon2id(
                input = fingerprintInput,
                salt = salt,
                params = kdfParams,
                outLen = 32
            )
        } catch (t: Throwable) {
            throw IllegalStateException("Unable to derive full backup package key", t)
        }

        try {
            FileOutputStream(packageFile).use { fos ->
                val buffered = BufferedOutputStream(fos, backupIoBufferSize)
                writeFullEncryptedPackageHeader(
                    buffered,
                    FullEncryptedPackageHeader(
                        version = fullEncryptedPackageVersion,
                        backupId = backupId,
                        kdf = kdfParams,
                        salt = salt,
                        nonce = nonce
                    )
                )
                onProgress?.invoke("Encrypting full package ZIP...")
                val estimatedPackagePlainBytes = backupFile.length().coerceAtLeast(0L) +
                    keyFile.length().coerceAtLeast(0L) +
                    4L * 1024L
                val streamProgress = ByteProgressReporter(
                    stage = "Encrypting package stream",
                    onProgress = onProgress,
                    totalBytes = estimatedPackagePlainBytes
                )
                backupSegmentedEnvelope.openEncryptingStream(
                    out = buffered,
                    key = dataKey,
                    appendIntegrityTrailer = false,
                    onChunkPlainBytesWritten = { processed ->
                        streamProgress.report(processed)
                    }
                ).use { encryptedOut ->
                    ZipOutputStream(BufferedOutputStream(encryptedOut, backupIoBufferSize)).use { zipOut ->
                        writeZipFileEntry(zipOut, fullPackageKeyEntry, keyFile) { copied, total ->
                            onProgress?.invoke(progressMessage("Encrypting key file", copied, total))
                        }
                        writeZipFileEntry(zipOut, fullPackageBackupEntry, backupFile) { copied, total ->
                            onProgress?.invoke(progressMessage("Encrypting backup", copied, total))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            packageFile.delete()
            throw IllegalStateException("Unable to create encrypted full backup package", t)
        } finally {
            wipe(dataKey)
        }
    }

    private fun scanFullPackageEntries(zipInput: ZipInputStream): FullPackageScanResult? {
        var backupEntryName: String? = null
        var keyEntryName: String? = null
        var keyData: FullBackupKeyData? = null

        zipInput.use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.orEmpty()
                    when {
                        backupEntryName == null && name.lowercase().endsWith(".vltbck") -> {
                            backupEntryName = name
                        }
                        keyEntryName == null && name.lowercase().endsWith(".vltk") -> {
                            keyEntryName = name
                            val keyBytes = readStreamWithLimit(zipIn, 256 * 1024)
                            keyData = parseFullBackupKeyFileBytes(keyBytes)
                        }
                        else -> {
                            if (backupEntryName == null || keyEntryName == null) {
                                drainStreamWithStallGuard(zipIn)
                            }
                        }
                    }
                }
                zipIn.closeEntry()
                if (backupEntryName != null && keyEntryName != null) break
                entry = zipIn.nextEntry
            }
        }

        val backupName = backupEntryName ?: return null
        val keyName = keyEntryName ?: return null
        if (keyData == null) return null
        return FullPackageScanResult(
            backupEntryName = backupName,
            keyEntryName = keyName,
            keyData = keyData
        )
    }

    private fun extractFullPackageEntries(
        zipInput: ZipInputStream,
        backupTarget: File,
        onProgress: ((String) -> Unit)? = null
    ): FullPackageScanResult? {
        var backupEntryName: String? = null
        var keyEntryName: String? = null
        var keyData: FullBackupKeyData? = null

        zipInput.use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.orEmpty()
                    when {
                        name.lowercase().endsWith(".vltk") -> {
                            keyEntryName = name
                            val keyBytes = readStreamWithLimit(zipIn, 256 * 1024)
                            keyData = parseFullBackupKeyFileBytes(keyBytes)
                        }
                        name.lowercase().endsWith(".vltbck") -> {
                            backupEntryName = name
                            FileOutputStream(backupTarget).use { output ->
                                copyStreamWithStallGuard(zipIn, output) { copied ->
                                    onProgress?.invoke("Extracting backup file: ${humanReadableBytes(copied)} processed")
                                }
                            }
                        }
                        else -> {
                            drainStreamWithStallGuard(zipIn)
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        val backupName = backupEntryName ?: return null
        val keyName = keyEntryName ?: return null
        if (keyData == null) return null
        return FullPackageScanResult(
            backupEntryName = backupName,
            keyEntryName = keyName,
            keyData = keyData
        )
    }

    private fun extractEncryptedFullPackageEntries(
        encryptedInput: BufferedInputStream,
        header: FullEncryptedPackageHeader,
        backupTarget: File,
        onProgress: ((String) -> Unit)? = null
    ): FullPackageScanResult? {
        val fingerprintInput = securityManager.getDeviceFingerprintHash()
        if (fingerprintInput.isEmpty()) return null

        val key = try {
            argon2id(
                input = fingerprintInput,
                salt = header.salt,
                params = header.kdf,
                outLen = 32
            )
        } catch (_: Throwable) {
            return null
        }

        return try {
            onProgress?.invoke("Validating device fingerprint...")
            val decryptedZipStream = if (header.version >= 2) {
                val packageDecryptProgress = ByteProgressReporter(
                    stage = "Decrypting package stream",
                    onProgress = onProgress
                )
                BufferedInputStream(
                    backupSegmentedEnvelope.openDecryptingStream(
                        input = encryptedInput,
                        key = key,
                        expectIntegrityTrailer = false,
                        onChunkPlainBytesRead = { processed ->
                            packageDecryptProgress.report(processed)
                        }
                    ),
                    backupIoBufferSize
                )
            } else {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, header.nonce)
                )
                BufferedInputStream(
                    CipherInputStream(encryptedInput, cipher),
                    backupIoBufferSize
                )
            }

            if (!isZipStreamSignature(decryptedZipStream)) {
                return null
            }

            ZipInputStream(decryptedZipStream).use { zis ->
                onProgress?.invoke("Extracting backup and key files...")
                extractFullPackageEntries(
                    zipInput = zis,
                    backupTarget = backupTarget,
                    onProgress = onProgress
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            wipe(key)
        }
    }

    private fun readBackupDescriptorFromFile(file: File): BackupDescriptor? {
        return runCatching {
            FileInputStream(file).use { raw ->
                val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, backupIoBufferSize)
                if (isNormalStreamInput(buffered)) {
                    val header = readNormalStreamHeader(DataInputStream(buffered)) ?: return@runCatching null
                    return@runCatching BackupDescriptor(
                        mode = BackupMode.NORMAL,
                        profile = BackupProfile.NORMAL_SINGLE,
                        scope = BackupScope.SINGLE_VAULT,
                        targetVaultId = header.targetVaultId,
                        backupId = header.backupId,
                        includesSettings = false,
                        requiresVaultPin = true,
                        requiresMasterKey1 = true,
                        requiresMasterKey2 = false,
                        requiresPhraseFile = false,
                        requiresBiometric = false,
                        isLegacy = false
                    )
                }
                if (isFullNormalStreamInput(buffered)) {
                    val header = readFullNormalStreamHeader(DataInputStream(buffered)) ?: return@runCatching null
                    return@runCatching BackupDescriptor(
                        mode = BackupMode.NORMAL,
                        profile = BackupProfile.NORMAL_ENTIRE,
                        scope = BackupScope.ENTIRE_APP,
                        targetVaultId = null,
                        backupId = header.backupId,
                        includesSettings = header.includesSettings,
                        requiresVaultPin = false,
                        requiresMasterKey1 = true,
                        requiresMasterKey2 = true,
                        requiresPhraseFile = false,
                        requiresBiometric = false,
                        isLegacy = false
                    )
                }
                if (isExtremeStreamInput(buffered)) {
                    val header = readExtremeStreamHeader(DataInputStream(buffered)) ?: return@runCatching null
                    return@runCatching BackupDescriptor(
                        mode = BackupMode.EXTREME,
                        profile = BackupProfile.EXTREME,
                        scope = header.scope,
                        targetVaultId = header.targetVaultId,
                        backupId = header.backupId,
                        includesSettings = header.includesSettings,
                        requiresVaultPin = false,
                        requiresMasterKey1 = false,
                        requiresMasterKey2 = false,
                        requiresPhraseFile = false,
                        requiresBiometric = true,
                        isLegacy = false
                    )
                }
            }
            val bytes = readFileBytesWithLimit(file, maxLegacyContainerBytes) ?: return@runCatching null
            buildDescriptor(BackupSerializer.deserialize(bytes))
        }.getOrNull()
    }

    private fun exportIntruderCaptureSettings(attachments: MutableMap<String, File>): List<BackupArchive.BackupSetting> {
        val intruderFiles = context.filesDir.listFiles { file ->
            file.isFile && file.name.startsWith("intruder_") && file.extension.lowercase() == "jpg"
        }.orEmpty()
        if (intruderFiles.isEmpty()) return emptyList()

        return intruderFiles.mapNotNull { file ->
            val ref = addAttachmentFileRef(file.absolutePath, attachments) ?: return@mapNotNull null
            val value = BackupAuxJsonCodec.encodeIntruderCaptureSetting(
                BackupAuxJsonCodec.IntruderCaptureSettingDto(
                    ref = ref,
                    name = file.name
                )
            )
            BackupArchive.BackupSetting(
                key = "intruder_capture",
                type = "intruder_capture",
                value = value
            )
        }
    }

    private fun restoreIntruderCaptureSnapshot(
        entries: List<BackupArchive.BackupSetting>,
        attachmentPaths: Map<String, String>
    ) {
        val existingFiles = context.filesDir.listFiles { file ->
            file.isFile && file.name.startsWith("intruder_") && file.extension.lowercase() == "jpg"
        }.orEmpty()
        existingFiles.forEach { runCatching { it.delete() } }

        val intruderEntries = entries.filter { it.type == "intruder_capture" }
        intruderEntries.forEach { setting ->
            val payload = BackupAuxJsonCodec.decodeIntruderCaptureSetting(setting.value) ?: return@forEach
            val ref = payload.ref.trim()
            if (ref.isBlank()) return@forEach
            val sourcePath = attachmentPaths[ref] ?: return@forEach
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists() || !sourceFile.isFile) return@forEach

            val requestedName = payload.name.trim()
            val fileName = when {
                requestedName.startsWith("intruder_") && requestedName.lowercase().endsWith(".jpg") -> requestedName
                else -> "intruder_${System.currentTimeMillis()}.jpg"
            }
            val destFile = File(context.filesDir, fileName)
            runCatching {
                    FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, streamCopyBufferSize)
                    }
                }
            }
        }
    }

    private fun cleanupAttachmentFiles(paths: Collection<String>) {
        paths.forEach { path ->
            runCatching { File(path).delete() }
        }
    }

    private fun exportSettingsSnapshot(): List<BackupArchive.BackupSetting> {
        val prefs = context.getSharedPreferences("vault_life_prefs", Context.MODE_PRIVATE)
        return prefs.all.entries.mapNotNull { entry ->
            val key = entry.key
            val value = entry.value ?: return@mapNotNull null
            when (value) {
                is Boolean -> BackupArchive.BackupSetting(key, "bool", value.toString())
                is Int -> BackupArchive.BackupSetting(key, "int", value.toString())
                is Long -> BackupArchive.BackupSetting(key, "long", value.toString())
                is Float -> BackupArchive.BackupSetting(key, "float", value.toString())
                is String -> BackupArchive.BackupSetting(key, "string", value)
                is Set<*> -> {
                    val set = value.filterIsInstance<String>()
                    val payload = BackupAuxJsonCodec.encodeStringSet(set.toSet())
                    BackupArchive.BackupSetting(key, "string_set", payload)
                }
                else -> null
            }
        }
    }

    private fun restoreSettingsSnapshot(entries: List<BackupArchive.BackupSetting>) {
        val prefs = context.getSharedPreferences("vault_life_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        entries.forEach { entry ->
            when (entry.type) {
                "bool" -> editor.putBoolean(entry.key, entry.value.toBoolean())
                "int" -> editor.putInt(entry.key, entry.value.toIntOrNull() ?: 0)
                "long" -> editor.putLong(entry.key, entry.value.toLongOrNull() ?: 0L)
                "float" -> editor.putFloat(entry.key, entry.value.toFloatOrNull() ?: 0f)
                "string" -> editor.putString(entry.key, entry.value)
                "string_set" -> {
                    val set = BackupAuxJsonCodec.decodeStringSet(entry.value)
                    editor.putStringSet(entry.key, set)
                }
            }
        }
        editor.apply()
    }

    private fun nextFullBackupFolderName(): String {
        val prefs = context.getSharedPreferences("vault_life_prefs", Context.MODE_PRIVATE)
        val nextIndex = (prefs.getInt("full_backup_folder_counter", 0) + 1).coerceAtLeast(1)
        prefs.edit().putInt("full_backup_folder_counter", nextIndex).apply()
        return "Full Backup $nextIndex"
    }

    private fun addAttachmentFileRef(path: String, target: MutableMap<String, File>): String? {
        val file = File(path)
        if (!file.exists() || !file.isFile || !file.canRead()) return null
        val id = UUID.randomUUID().toString()
        target[id] = file
        return id
    }

    private fun estimateStreamingBackupPlainBytes(
        payload: BackupArchive.BackupPayload,
        attachments: Map<String, File>
    ): Long {
        val payloadBytes = estimatePayloadJsonBytes(payload)
        val attachmentBytes = attachments.values.sumOf { file ->
            if (file.exists() && file.isFile) file.length().coerceAtLeast(0L) else 0L
        }
        val entryOverhead = (attachments.size.toLong() + 1L) * 256L
        return (payloadBytes + attachmentBytes + entryOverhead).coerceAtLeast(payloadBytes)
    }

    private fun estimatePayloadJsonBytes(payload: BackupArchive.BackupPayload): Long {
        val avgVaultBytes = 150L
        val avgFolderBytes = 80L
        val avgItemBytes = 240L
        val avgSettingBytes = 96L
        val jsonOverhead = 96L * 1024L

        val estimated =
            (payload.vaults.size.toLong() * avgVaultBytes) +
            (payload.folders.size.toLong() * avgFolderBytes) +
            (payload.items.size.toLong() * avgItemBytes) +
            (payload.settings.size.toLong() * avgSettingBytes) +
            jsonOverhead

        return estimated.coerceAtLeast(jsonOverhead)
    }

    private fun writeZipEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.time = System.currentTimeMillis()
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    private fun openBackupZipInputStream(
        dataInput: DataInputStream,
        key: ByteArray,
        nonce: ByteArray,
        streamVersion: Int,
        onChunkPlainBytesRead: ((Long) -> Unit)? = null
    ): ZipInputStream {
        return if (streamVersion >= 2) {
            ZipInputStream(
                BufferedInputStream(
                    backupSegmentedEnvelope.openDecryptingStream(
                        input = dataInput,
                        key = key,
                        expectIntegrityTrailer = streamVersion >= 3,
                        onChunkPlainBytesRead = onChunkPlainBytesRead
                    ),
                    backupIoBufferSize
                )
            )
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce)
            )
            ZipInputStream(
                BufferedInputStream(
                    CipherInputStream(dataInput, cipher),
                    backupIoBufferSize
                )
            )
        }
    }

    private fun writePayloadZipEntry(zipOut: ZipOutputStream, payload: BackupArchive.BackupPayload) {
        val entry = ZipEntry("payload.json")
        entry.time = System.currentTimeMillis()
        zipOut.putNextEntry(entry)
        try {
            writePayloadJsonToStream(zipOut, payload)
        } finally {
            zipOut.closeEntry()
        }
    }

    private fun writePayloadJsonToStream(output: OutputStream, payload: BackupArchive.BackupPayload) {
        output.write(BackupPayloadJsonCodec.encodeToByteArray(payload))
        output.flush()
    }

    private fun writeZipFileEntry(
        zipOut: ZipOutputStream,
        name: String,
        file: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        if (!file.exists() || !file.isFile) {
            throw FileNotFoundException("Source file not found: ${file.absolutePath}")
        }
        val totalBytes = file.length().coerceAtLeast(0L)
        var copiedBytes = 0L
        var lastReportedBytes = 0L
        FileInputStream(file).use { input ->
            val entry = ZipEntry(name)
            entry.time = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
            zipOut.putNextEntry(entry)
            try {
                val buffer = ByteArray(streamCopyBufferSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    zipOut.write(buffer, 0, read)
                    copiedBytes += read
                    if (
                        onProgress != null &&
                        (
                            copiedBytes == totalBytes ||
                                copiedBytes - lastReportedBytes >= progressReportChunkBytes
                            )
                    ) {
                        lastReportedBytes = copiedBytes
                        onProgress.invoke(copiedBytes, totalBytes)
                    }
                }
            } finally {
                zipOut.closeEntry()
            }
        }
    }

    private fun writeZipAttachmentEntries(
        zipOut: ZipOutputStream,
        attachments: Map<String, File>,
        onProgress: ((String) -> Unit)? = null
    ) {
        if (attachments.isEmpty()) return
        val total = attachments.size
        var index = 0
        attachments.forEach { (id, file) ->
            index++
            onProgress?.invoke("Archiving attachment $index/$total")
            try {
                writeZipFileEntry(zipOut, "attachments/$id", file) { copied, totalBytes ->
                    onProgress?.invoke(
                        progressMessage(
                            stage = "Attachment $index/$total",
                            processed = copied,
                            total = totalBytes
                        )
                    )
                }
            } catch (_: FileNotFoundException) {
                // Skip stale attachment references so full backup creation can continue.
            } catch (_: SecurityException) {
                // Skip inaccessible attachment files to avoid aborting full backup creation.
            }
        }
    }

    private fun progressMessage(stage: String, processed: Long, total: Long): String {
        val safeProcessed = processed.coerceAtLeast(0L)
        val safeTotal = total.coerceAtLeast(0L)
        return if (safeTotal > 0L) {
            val percent = ((safeProcessed.toDouble() / safeTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)
            "$stage: ${humanReadableBytes(safeProcessed)} / ${humanReadableBytes(safeTotal)} (${String.format(Locale.US, "%.1f", percent)}%)"
        } else {
            "$stage: ${humanReadableBytes(safeProcessed)}"
        }
    }

    private fun humanReadableBytes(bytes: Long): String {
        return backupProgressReporter.humanReadableBytes(bytes)
    }

    private inner class ByteProgressReporter(
        private val stage: String,
        private val onProgress: ((String) -> Unit)?,
        private val totalBytes: Long? = null
    ) {
        private val startedAtMs = System.currentTimeMillis()
        private var lastReportedBytes = 0L
        private var lastReportedAtMs = 0L

        fun report(processedBytes: Long) {
            val callback = onProgress ?: return
            val now = System.currentTimeMillis()
            val shouldReport = processedBytes == 0L ||
                (processedBytes - lastReportedBytes >= progressReportChunkBytes) ||
                (now - lastReportedAtMs >= 1200L)
            if (!shouldReport) return
            lastReportedBytes = processedBytes
            lastReportedAtMs = now
            callback(
                buildTransferProgressMessage(
                    stage = stage,
                    processedBytes = processedBytes,
                    totalBytes = totalBytes,
                    startedAtMs = startedAtMs,
                    nowMs = now
                )
            )
        }
    }

    private fun buildTransferProgressMessage(
        stage: String,
        processedBytes: Long,
        totalBytes: Long?,
        startedAtMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        return backupProgressReporter.buildTransferProgressMessage(
            stage = stage,
            processedBytes = processedBytes,
            totalBytes = totalBytes,
            startedAtMs = startedAtMs,
            nowMs = nowMs
        )
    }

    private fun readStreamWithLimit(input: InputStream, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) throw IllegalArgumentException("Invalid limit")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(streamCopyBufferSize)
        var total = 0
        var zeroReadCount = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                zeroReadCount++
                if (zeroReadCount >= 4096) {
                    throw IllegalStateException("Backup stream stalled")
                }
                continue
            }
            zeroReadCount = 0
            total += read
            if (total > maxBytes) {
                throw IllegalArgumentException("Backup payload too large")
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun readBytesWithLimit(input: InputStream, maxBytes: Int): ByteArray? {
        return runCatching { readStreamWithLimit(input, maxBytes) }.getOrNull()
    }

    private fun decodePayloadWithLog(
        input: InputStream,
        onProgress: ((String) -> Unit)?
    ): BackupArchive.BackupPayload {
        return try {
            BackupPayloadJsonCodec.decodeFromStream(input)
        } catch (e: Exception) {
            val detail = e.message?.trim().takeUnless { it.isNullOrBlank() } ?: e.javaClass.simpleName
            onProgress?.invoke("Backup metadata invalid: $detail")
            throw e
        }
    }

    private fun readFileBytesWithLimit(file: File, maxBytes: Int): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        if (file.length() > maxBytes) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    private fun copyStreamWithStallGuard(
        input: InputStream,
        output: OutputStream,
        onBytesCopied: ((Long) -> Unit)? = null
    ) {
        val buffer = ByteArray(streamCopyBufferSize)
        var zeroReadCount = 0
        var copiedBytes = 0L
        var lastReportedBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                zeroReadCount++
                if (zeroReadCount >= 4096) {
                    throw IllegalStateException("Backup stream stalled")
                }
                continue
            }
            zeroReadCount = 0
            output.write(buffer, 0, read)
            copiedBytes += read.toLong()
            if (
                onBytesCopied != null &&
                copiedBytes - lastReportedBytes >= progressReportChunkBytes
            ) {
                lastReportedBytes = copiedBytes
                onBytesCopied.invoke(copiedBytes)
            }
        }
        onBytesCopied?.invoke(copiedBytes)
    }

    private fun drainStreamWithStallGuard(input: InputStream) {
        val buffer = ByteArray(streamCopyBufferSize)
        var zeroReadCount = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                zeroReadCount++
                if (zeroReadCount >= 4096) {
                    throw IllegalStateException("Backup stream stalled")
                }
                continue
            }
            zeroReadCount = 0
        }
    }

    private fun Throwable.rootCause(): Throwable {
        var current: Throwable = this
        while (current.cause != null && current.cause !== current) {
            current = current.cause!!
        }
        return current
    }

    private fun isAuthFailure(error: Throwable): Boolean {
        if (error is AEADBadTagException) return true
        val message = error.message.orEmpty()
        return message.contains("tag", ignoreCase = true) ||
            message.contains("auth", ignoreCase = true) ||
            message.contains("mac check", ignoreCase = true)
    }

    private fun invalidPayloadResult(error: Throwable): RestoreResult {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isNotBlank()) {
            RestoreResult(false, "Invalid backup payload: $detail")
        } else {
            RestoreResult(false, "Invalid backup payload")
        }
    }

    private fun writeNormalStreamHeader(output: BufferedOutputStream, header: NormalStreamHeader) {
        val dos = DataOutputStream(output)
        dos.write(normalStreamMagic)
        dos.writeInt(header.version)
        dos.writeLong(header.createdAt)
        dos.writeInt(header.targetVaultId)
        writeSizedString(dos, header.backupId)
        writeSizedString(dos, header.targetVaultName)
        dos.writeInt(header.kdf.algorithm.ordinal)
        dos.writeInt(header.kdf.iterations)
        dos.writeInt(header.kdf.memoryKiB)
        dos.writeInt(header.kdf.parallelism)
        writeSizedBytes(dos, header.salt)
        writeSizedBytes(dos, header.nonce)
        dos.flush()
    }

    private fun readNormalStreamHeader(input: DataInputStream): NormalStreamHeader? {
        val magic = ByteArray(normalStreamMagic.size)
        input.readFully(magic)
        if (!magic.contentEquals(normalStreamMagic)) return null

        val version = input.readInt()
        if (
            version != normalStreamVersion &&
            version != normalStreamV2Version &&
            version != normalStreamLegacyVersion
        ) return null

        val createdAt = input.readLong()
        val targetVaultId = input.readInt()
        if (targetVaultId <= 0) return null

        val backupId = readSizedString(input, 1024) ?: return null
        val targetVaultName = readSizedString(input, 1024) ?: return null

        val algIdx = input.readInt()
        val algorithm = KdfAlgorithm.values().getOrElse(algIdx) { KdfAlgorithm.ARGON2ID }
        val iterations = input.readInt().coerceAtLeast(1)
        val memoryKiB = input.readInt().coerceAtLeast(8 * 1024)
        val parallelism = input.readInt().coerceIn(1, 8)
        val salt = readSizedBytes(input, 128) ?: return null
        val nonce = readSizedBytes(input, 32) ?: return null
        if (nonce.size != 12) return null

        return NormalStreamHeader(
            version = version,
            createdAt = createdAt,
            targetVaultId = targetVaultId,
            backupId = backupId,
            targetVaultName = targetVaultName,
            kdf = KdfParams(
                algorithm = algorithm,
                iterations = iterations,
                memoryKiB = memoryKiB,
                parallelism = parallelism
            ),
            salt = salt,
            nonce = nonce
        )
    }

    private fun writeFullNormalStreamHeader(output: BufferedOutputStream, header: FullNormalStreamHeader) {
        val dos = DataOutputStream(output)
        dos.write(fullNormalStreamMagic)
        dos.writeInt(header.version)
        dos.writeLong(header.createdAt)
        writeSizedString(dos, header.backupId)
        dos.writeBoolean(header.includesSettings)
        dos.writeInt(header.kdf.algorithm.ordinal)
        dos.writeInt(header.kdf.iterations)
        dos.writeInt(header.kdf.memoryKiB)
        dos.writeInt(header.kdf.parallelism)
        writeSizedBytes(dos, header.salt)
        writeSizedBytes(dos, header.nonce)
        dos.flush()
    }

    private fun readFullNormalStreamHeader(input: DataInputStream): FullNormalStreamHeader? {
        val magic = ByteArray(fullNormalStreamMagic.size)
        input.readFully(magic)
        if (!magic.contentEquals(fullNormalStreamMagic)) return null

        val version = input.readInt()
        if (
            version != fullNormalStreamVersion &&
            version != fullNormalStreamV2Version &&
            version != fullNormalStreamLegacyVersion
        ) return null

        val createdAt = input.readLong()
        val backupId = readSizedString(input, 1024) ?: return null
        val includesSettings = input.readBoolean()

        val algIdx = input.readInt()
        val algorithm = KdfAlgorithm.values().getOrElse(algIdx) { KdfAlgorithm.ARGON2ID }
        val iterations = input.readInt().coerceAtLeast(1)
        val memoryKiB = input.readInt().coerceAtLeast(8 * 1024)
        val parallelism = input.readInt().coerceIn(1, 8)
        val salt = readSizedBytes(input, 128) ?: return null
        val nonce = readSizedBytes(input, 32) ?: return null
        if (nonce.size != 12) return null

        return FullNormalStreamHeader(
            version = version,
            createdAt = createdAt,
            backupId = backupId,
            includesSettings = includesSettings,
            kdf = KdfParams(
                algorithm = algorithm,
                iterations = iterations,
                memoryKiB = memoryKiB,
                parallelism = parallelism
            ),
            salt = salt,
            nonce = nonce
        )
    }

    private fun writeExtremeStreamHeader(output: BufferedOutputStream, header: ExtremeStreamHeader) {
        val dos = DataOutputStream(output)
        dos.write(extremeStreamMagic)
        dos.writeInt(header.version)
        dos.writeLong(header.createdAt)
        dos.writeInt(header.scope.ordinal)
        dos.writeInt(header.targetVaultId ?: -1)
        writeSizedString(dos, header.backupId)
        dos.writeBoolean(header.includesSettings)
        dos.writeInt(header.kdf.algorithm.ordinal)
        dos.writeInt(header.kdf.iterations)
        dos.writeInt(header.kdf.memoryKiB)
        dos.writeInt(header.kdf.parallelism)
        writeSizedBytes(dos, header.salt)
        writeSizedBytes(dos, header.nonce)
        dos.flush()
    }

    private fun readExtremeStreamHeader(input: DataInputStream): ExtremeStreamHeader? {
        val magic = ByteArray(extremeStreamMagic.size)
        input.readFully(magic)
        if (!magic.contentEquals(extremeStreamMagic)) return null

        val version = input.readInt()
        if (
            version != extremeStreamVersion &&
            version != extremeStreamV2Version &&
            version != extremeStreamLegacyVersion
        ) return null

        val createdAt = input.readLong()
        val scopeIdx = input.readInt()
        val scope = BackupScope.values().getOrElse(scopeIdx) { BackupScope.SINGLE_VAULT }
        val rawTarget = input.readInt()
        val targetVaultId = if (rawTarget > 0) rawTarget else null
        if (scope == BackupScope.SINGLE_VAULT && targetVaultId == null) return null
        val backupId = readSizedString(input, 1024) ?: return null
        val includesSettings = input.readBoolean()

        val algIdx = input.readInt()
        val algorithm = KdfAlgorithm.values().getOrElse(algIdx) { KdfAlgorithm.ARGON2ID }
        val iterations = input.readInt().coerceAtLeast(1)
        val memoryKiB = input.readInt().coerceAtLeast(8 * 1024)
        val parallelism = input.readInt().coerceIn(1, 8)
        val salt = readSizedBytes(input, 128) ?: return null
        val nonce = readSizedBytes(input, 32) ?: return null
        if (nonce.size != 12) return null

        return ExtremeStreamHeader(
            version = version,
            createdAt = createdAt,
            scope = scope,
            targetVaultId = targetVaultId,
            backupId = backupId,
            includesSettings = includesSettings,
            kdf = KdfParams(
                algorithm = algorithm,
                iterations = iterations,
                memoryKiB = memoryKiB,
                parallelism = parallelism
            ),
            salt = salt,
            nonce = nonce
        )
    }

    private fun writeFullEncryptedPackageHeader(output: BufferedOutputStream, header: FullEncryptedPackageHeader) {
        val dos = DataOutputStream(output)
        dos.write(fullEncryptedPackageMagic)
        dos.writeInt(header.version)
        writeSizedString(dos, header.backupId)
        dos.writeInt(header.kdf.algorithm.ordinal)
        dos.writeInt(header.kdf.iterations)
        dos.writeInt(header.kdf.memoryKiB)
        dos.writeInt(header.kdf.parallelism)
        writeSizedBytes(dos, header.salt)
        writeSizedBytes(dos, header.nonce)
        dos.flush()
    }

    private fun readFullEncryptedPackageHeader(input: DataInputStream): FullEncryptedPackageHeader? {
        val magic = ByteArray(fullEncryptedPackageMagic.size)
        input.readFully(magic)
        if (!magic.contentEquals(fullEncryptedPackageMagic)) return null

        val version = input.readInt()
        if (version != fullEncryptedPackageVersion && version != fullEncryptedPackageLegacyVersion) return null

        val backupId = readSizedString(input, 1024) ?: return null
        val algIdx = input.readInt()
        val algorithm = KdfAlgorithm.values().getOrElse(algIdx) { KdfAlgorithm.ARGON2ID }
        val iterations = input.readInt().coerceAtLeast(1)
        val memoryKiB = input.readInt().coerceAtLeast(8 * 1024)
        val parallelism = input.readInt().coerceIn(1, 8)
        val salt = readSizedBytes(input, 128) ?: return null
        val nonce = readSizedBytes(input, 32) ?: return null
        if (nonce.size != 12) return null

        return FullEncryptedPackageHeader(
            version = version,
            backupId = backupId,
            kdf = KdfParams(
                algorithm = algorithm,
                iterations = iterations,
                memoryKiB = memoryKiB,
                parallelism = parallelism
            ),
            salt = salt,
            nonce = nonce
        )
    }

    private fun writeSizedBytes(dos: DataOutputStream, bytes: ByteArray) {
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readSizedBytes(dis: DataInputStream, maxLen: Int): ByteArray? {
        val len = dis.readInt()
        if (len <= 0 || len > maxLen) return null
        val out = ByteArray(len)
        dis.readFully(out)
        return out
    }

    private fun writeSizedString(dos: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readSizedString(dis: DataInputStream, maxLen: Int): String? {
        val len = dis.readInt()
        if (len <= 0 || len > maxLen) return null
        val bytes = ByteArray(len)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun isNormalStreamInput(input: BufferedInputStream): Boolean {
        return try {
            input.mark(normalStreamMagic.size + 8)
            val prefix = ByteArray(normalStreamMagic.size)
            val read = input.read(prefix)
            input.reset()
            read == normalStreamMagic.size && prefix.contentEquals(normalStreamMagic)
        } catch (_: Exception) {
            false
        }
    }

    private fun isFullNormalStreamInput(input: BufferedInputStream): Boolean {
        return try {
            input.mark(fullNormalStreamMagic.size + 8)
            val prefix = ByteArray(fullNormalStreamMagic.size)
            val read = input.read(prefix)
            input.reset()
            read == fullNormalStreamMagic.size && prefix.contentEquals(fullNormalStreamMagic)
        } catch (_: Exception) {
            false
        }
    }

    private fun isExtremeStreamInput(input: BufferedInputStream): Boolean {
        return try {
            input.mark(extremeStreamMagic.size + 8)
            val prefix = ByteArray(extremeStreamMagic.size)
            val read = input.read(prefix)
            input.reset()
            read == extremeStreamMagic.size && prefix.contentEquals(extremeStreamMagic)
        } catch (_: Exception) {
            false
        }
    }

    private fun isFullEncryptedPackageInput(input: BufferedInputStream): Boolean {
        return try {
            input.mark(fullEncryptedPackageMagic.size + 8)
            val prefix = ByteArray(fullEncryptedPackageMagic.size)
            val read = input.read(prefix)
            input.reset()
            read == fullEncryptedPackageMagic.size && prefix.contentEquals(fullEncryptedPackageMagic)
        } catch (_: Exception) {
            false
        }
    }

    private fun isZipStreamSignature(input: BufferedInputStream): Boolean {
        return try {
            input.mark(64)
            val signature = ByteArray(4)
            var offset = 0
            var zeroReadCount = 0
            while (offset < signature.size) {
                val read = input.read(signature, offset, signature.size - offset)
                if (read < 0) break
                if (read == 0) {
                    zeroReadCount++
                    if (zeroReadCount >= 4096) break
                    continue
                }
                zeroReadCount = 0
                offset += read
            }
            input.reset()
            offset == 4 &&
                signature[0] == 'P'.code.toByte() &&
                signature[1] == 'K'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }

    private fun isNormalStreamBytes(bytes: ByteArray): Boolean {
        if (bytes.size < normalStreamMagic.size) return false
        for (i in normalStreamMagic.indices) {
            if (bytes[i] != normalStreamMagic[i]) return false
        }
        return true
    }

    private fun isFullNormalStreamBytes(bytes: ByteArray): Boolean {
        if (bytes.size < fullNormalStreamMagic.size) return false
        for (i in fullNormalStreamMagic.indices) {
            if (bytes[i] != fullNormalStreamMagic[i]) return false
        }
        return true
    }

    private fun isExtremeStreamBytes(bytes: ByteArray): Boolean {
        if (bytes.size < extremeStreamMagic.size) return false
        for (i in extremeStreamMagic.indices) {
            if (bytes[i] != extremeStreamMagic[i]) return false
        }
        return true
    }

    private fun writeFileToDownloads(
        fileName: String,
        mimeType: String,
        file: File,
        replace: Boolean,
        subDirectory: String
    ): Uri? {
        return try {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + subDirectory.trim('/').trim() + "/"
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                if (replace) {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(fileName, relativePath),
                        null
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { input ->
                        input.copyTo(out, streamCopyBufferSize)
                    }
                    out.flush()
                } ?: return null
                val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, complete, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloadRoot, subDirectory)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                if (replace && outFile.exists()) outFile.delete()
                FileInputStream(file).use { input ->
                    FileOutputStream(outFile).use { out -> input.copyTo(out, streamCopyBufferSize) }
                }
                Uri.fromFile(outFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeFileToDownloadsWithProgress(
        fileName: String,
        mimeType: String,
        file: File,
        replace: Boolean,
        subDirectory: String,
        onProgress: ((String) -> Unit)? = null
    ): Uri? {
        val totalBytes = file.length().coerceAtLeast(0L)
        val reporter = ByteProgressReporter(
            stage = "Copying package",
            onProgress = onProgress,
            totalBytes = totalBytes
        )
        return try {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + subDirectory.trim('/').trim() + "/"
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                if (replace) {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(fileName, relativePath),
                        null
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(file).use { input ->
                            copyStreamWithProgress(input, out, reporter)
                        }
                        out.flush()
                    } ?: return null
                    val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    resolver.update(uri, complete, null, null)
                    uri
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloadRoot, subDirectory)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                if (replace && outFile.exists()) outFile.delete()
                FileInputStream(file).use { input ->
                    FileOutputStream(outFile).use { out ->
                        copyStreamWithProgress(input, out, reporter)
                    }
                }
                Uri.fromFile(outFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyStreamWithProgress(
        input: InputStream,
        output: OutputStream,
        reporter: ByteProgressReporter
    ) {
        val buffer = ByteArray(streamCopyBufferSize)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            copied += read
            reporter.report(copied)
        }
    }

    private fun writeToDownloads(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        replace: Boolean,
        subDirectory: String
    ): Uri? {
        return try {
            val resolver = context.contentResolver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + subDirectory.trim('/').trim() + "/"
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                if (replace) {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                        arrayOf(fileName, relativePath),
                        null
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val uri = Uri.withAppendedPath(collection, id.toString())
                            resolver.delete(uri, null, null)
                        }
                    }
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    out.flush()
                } ?: return null
                val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, complete, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val dir = File(downloadRoot, subDirectory)
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                if (replace && outFile.exists()) outFile.delete()
                outFile.writeBytes(bytes)
                Uri.fromFile(outFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun enforceRetention() {
        val files = listBackups().sortedByDescending { it.lastModified() }
        if (files.size <= 10) return
        files.drop(10).forEach { it.delete() }
    }

    private fun isBackupHealthy(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val buffered = BufferedInputStream(input, backupIoBufferSize)
                if (isNormalStreamInput(buffered)) {
                    val dataInput = DataInputStream(buffered)
                    val header = readNormalStreamHeader(dataInput)
                    header != null && backupSegmentedEnvelope.verifyEnvelope(
                        input = dataInput,
                        requireIntegrityTrailer = header.version >= 3
                    )
                } else if (isFullNormalStreamInput(buffered)) {
                    val dataInput = DataInputStream(buffered)
                    val header = readFullNormalStreamHeader(dataInput)
                    header != null && backupSegmentedEnvelope.verifyEnvelope(
                        input = dataInput,
                        requireIntegrityTrailer = header.version >= 3
                    )
                } else if (isExtremeStreamInput(buffered)) {
                    val dataInput = DataInputStream(buffered)
                    val header = readExtremeStreamHeader(dataInput)
                    header != null && backupSegmentedEnvelope.verifyEnvelope(
                        input = dataInput,
                        requireIntegrityTrailer = header.version >= 3
                    )
                } else {
                    val bytes = readBytesWithLimit(buffered, maxLegacyContainerBytes) ?: return false
                    val (content, storedHash) = BackupSerializer.splitHashArea(bytes)
                    val computed = BackupSerializer.computeSha256(content)
                    MessageDigest.isEqual(storedHash, computed)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getAppVersion(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun wipe(bytes: ByteArray) {
        java.util.Arrays.fill(bytes, 0)
    }

    private fun wipe(chars: CharArray) {
        java.util.Arrays.fill(chars, '\u0000')
    }

    private fun isBiometricAvailable(): Boolean {
        val manager = BiometricManager.from(context)
        return manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }
}
