package com.vault.srd.backup.core

import androidx.room.withTransaction
import com.vault.srd.backup.model.BackupArchive
import com.vault.srd.backup.model.BackupScope
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultDao
import com.vault.srd.data.VaultDatabase
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItem
import com.vault.srd.data.VaultItemTagCrossRef
import com.vault.srd.data.VaultTag
import com.vault.srd.security.SecurityManager

class BackupRestoreEngine(
    private val dao: VaultDao,
    private val database: VaultDatabase,
    private val securityManager: SecurityManager
) {
    data class RestoreEngineResult(
        val success: Boolean,
        val error: String? = null
    )

    suspend fun applyRestoredPayload(
        payload: BackupArchive.BackupPayload,
        attachmentPaths: Map<String, String>,
        scope: BackupScope,
        targetVaultId: Int?,
        includesSettings: Boolean,
        onProgress: ((String) -> Unit)? = null,
        restoreSettingsSnapshot: (List<BackupArchive.BackupSetting>) -> Unit,
        restoreIntruderCaptureSnapshot: (List<BackupArchive.BackupSetting>, Map<String, String>) -> Unit
    ): RestoreEngineResult {
        val restoredVaults = payload.vaults.map { vault ->
            val path = vault.logoPath
            if (path != null && path.startsWith("ref:")) {
                val ref = path.removePrefix("ref:")
                val newPath = attachmentPaths[ref]
                if (newPath != null) vault.copy(logoPath = newPath) else vault.copy(logoPath = null)
            } else {
                vault
            }
        }

        if (restoredVaults.isEmpty()) {
            return RestoreEngineResult(false, "Invalid backup payload")
        }

        return try {
            onProgress?.invoke("Writing data to database...")
            database.withTransaction {
                if (scope == BackupScope.SINGLE_VAULT) {
                    val sourceVault = restoredVaults.first()
                    val sourceVaultId = sourceVault.id
                    val candidateIds = buildList {
                        targetVaultId?.takeIf { it > 0 }?.let { add(it) }
                        sourceVaultId.takeIf { it > 0 && it != targetVaultId }?.let { add(it) }
                    }
                    var matchedExisting: Pair<Int, Vault>? = null
                    for (candidateId in candidateIds) {
                        val existing = dao.getVaultById(candidateId) ?: continue
                        if (
                            existing.pinHash == sourceVault.pinHash ||
                                existing.name.equals(sourceVault.name, ignoreCase = true)
                        ) {
                            matchedExisting = candidateId to existing
                            break
                        }
                    }

                    val resolvedVaultId = if (matchedExisting != null) {
                        val (replaceId, existing) = matchedExisting
                        dao.deleteItemTagsByVaultId(replaceId)
                        dao.deleteTagsByVaultId(replaceId)
                        dao.deleteItemsByVaultId(replaceId)
                        dao.deleteFoldersByVaultId(replaceId)
                        dao.deleteVault(existing)
                        dao.insertVault(sourceVault.copy(id = replaceId))
                        replaceId
                    } else {
                        dao.insertVaultAndReturnId(sourceVault.copy(id = 0)).toInt()
                    }

                    val folderIdMap = mutableMapOf<Int, Int>()
                    payload.folders
                        .filter { it.vaultId == sourceVaultId }
                        .forEach { folder ->
                            val newFolderId = dao.insertFolder(
                                VaultFolder(
                                    id = 0,
                                    vaultId = resolvedVaultId,
                                    name = folder.name,
                                    description = folder.description,
                                    createdAt = folder.createdAt
                                )
                            ).toInt()
                            if (folder.id > 0 && newFolderId > 0) {
                                folderIdMap[folder.id] = newFolderId
                            }
                        }

                    val itemIdMap = mutableMapOf<Int, Int>()
                    payload.items
                        .asSequence()
                        .filter { it.vaultId == sourceVaultId }
                        .chunked(64)
                        .forEachIndexed { index, chunk ->
                            chunk.forEach { item ->
                                val mapped = mapBackupItemToVaultItem(
                                    item = item,
                                    vaultId = resolvedVaultId,
                                    folderId = item.folderId?.let { folderIdMap[it] },
                                    attachmentPaths = attachmentPaths,
                                    itemId = 0
                                )
                                val newItemId = dao.insertItem(mapped).toInt()
                                if (item.id > 0 && newItemId > 0) {
                                    itemIdMap[item.id] = newItemId
                                }
                            }
                            if (index % 8 == 0) {
                                onProgress?.invoke("Restoring vault records... chunk ${index + 1}")
                            }
                        }

                    val tagIdMap = mutableMapOf<String, String>()
                    payload.tags
                        .asSequence()
                        .filter { it.vaultId == sourceVaultId }
                        .forEach { tag ->
                            val mappedTag = VaultTag(
                                id = tag.id,
                                name = tag.name,
                                colorHex = tag.colorHex,
                                vaultId = resolvedVaultId,
                                createdAt = tag.createdAt
                            )
                            dao.insertTag(mappedTag)
                            val persistedTag = dao.getTagById(mappedTag.id)
                                ?: dao.getTagByName(resolvedVaultId, mappedTag.name)
                            if (persistedTag != null) {
                                tagIdMap[tag.id] = persistedTag.id
                            }
                        }

                    val refs = payload.itemTags.mapNotNull { ref ->
                        val newItemId = itemIdMap[ref.itemId] ?: return@mapNotNull null
                        val newTagId = tagIdMap[ref.tagId] ?: return@mapNotNull null
                        VaultItemTagCrossRef(itemId = newItemId, tagId = newTagId)
                    }.distinctBy { "${it.itemId}|${it.tagId}" }
                    if (refs.isNotEmpty()) {
                        dao.insertItemTagRefs(refs)
                    }
                } else {
                    dao.clearItemTagRefsByAll()
                    dao.clearTags()
                    dao.clearVaultItems()
                    dao.clearFolders()
                    dao.clearVaults()
                    onProgress?.invoke("Restoring vaults...")
                    dao.insertVaults(restoredVaults)
                    val validVaultIds = restoredVaults.map { it.id }.toHashSet()
                    val safeFolders = payload.folders.filter { folder ->
                        validVaultIds.contains(folder.vaultId)
                    }
                    if (safeFolders.isNotEmpty()) dao.insertFolders(safeFolders)
                    val validFolderIds = safeFolders.map { it.id }.toHashSet()
                    payload.items
                        .asSequence()
                        .filter { item -> validVaultIds.contains(item.vaultId) }
                        .chunked(64)
                        .forEachIndexed { index, chunk ->
                            val mapped = chunk.map { item ->
                                mapBackupItemToVaultItem(
                                    item = item,
                                    vaultId = item.vaultId,
                                    folderId = item.folderId?.takeIf { validFolderIds.contains(it) },
                                    attachmentPaths = attachmentPaths,
                                    itemId = item.id
                                )
                            }
                            if (mapped.isNotEmpty()) dao.insertItems(mapped)
                            if (index % 8 == 0) {
                                onProgress?.invoke("Restoring app records... chunk ${index + 1}")
                            }
                        }

                    val restoredTags = payload.tags
                        .filter { validVaultIds.contains(it.vaultId) }
                        .map { tag ->
                            VaultTag(
                                id = tag.id,
                                name = tag.name,
                                colorHex = tag.colorHex,
                                vaultId = tag.vaultId,
                                createdAt = tag.createdAt
                            )
                        }
                    if (restoredTags.isNotEmpty()) {
                        dao.insertTags(restoredTags)
                    }

                    val validItemIds = payload.items.map { it.id }.toHashSet()
                    val validTagIds = restoredTags.map { it.id }.toHashSet()
                    val refs = payload.itemTags
                        .asSequence()
                        .filter { validItemIds.contains(it.itemId) && validTagIds.contains(it.tagId) }
                        .map { VaultItemTagCrossRef(itemId = it.itemId, tagId = it.tagId) }
                        .distinctBy { "${it.itemId}|${it.tagId}" }
                        .toList()
                    if (refs.isNotEmpty()) {
                        dao.insertItemTagRefs(refs)
                    }
                }
            }

            if (scope == BackupScope.ENTIRE_APP && includesSettings) {
                onProgress?.invoke("Restoring app settings...")
                restoreSettingsSnapshot(payload.settings)
                onProgress?.invoke("Restoring intruder capture data...")
                restoreIntruderCaptureSnapshot(payload.settings, attachmentPaths)
            }

            onProgress?.invoke("Restore complete.")
            RestoreEngineResult(true)
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            RestoreEngineResult(false, "Application data could not be restored: $reason")
        }
    }

    private fun mapBackupItemToVaultItem(
        item: BackupArchive.BackupItem,
        vaultId: Int,
        folderId: Int?,
        attachmentPaths: Map<String, String>,
        itemId: Int
    ): VaultItem {
        val filePath = item.fileRef?.let { attachmentPaths[it] }
        val logoPath = item.logoRef?.let { attachmentPaths[it] }
        return VaultItem(
            id = itemId,
            vaultId = vaultId,
            type = item.type,
            name = item.name,
            description = item.description,
            content = normalizeFieldForRestore(vaultId, item.content),
            username = normalizeFieldForRestore(vaultId, item.username),
            passCategory = item.passCategory,
            link = item.link,
            logoPath = logoPath,
            filePath = filePath,
            extension = item.extension,
            email = normalizeFieldForRestore(vaultId, item.email),
            phoneNumber = normalizeFieldForRestore(vaultId, item.phoneNumber),
            folderId = folderId,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }

    private fun normalizeFieldForRestore(vaultId: Int, value: String?): String? {
        if (value == null) return null
        if (value.length > MAX_ENCRYPTED_FIELD_LENGTH_FOR_BACKUP_DECRYPT) {
            return securityManager.encryptForVault(vaultId, value)
        }
        return try {
            securityManager.decryptForVault(vaultId, value)
            value
        } catch (_: Exception) {
            try {
                securityManager.decryptForVault(vaultId, value)
                securityManager.encryptForVault(vaultId, value)
            } catch (_: Exception) {
                securityManager.encryptForVault(vaultId, value)
            }
        }
    }

    companion object {
        private const val MAX_ENCRYPTED_FIELD_LENGTH_FOR_BACKUP_DECRYPT = 8 * 1024 * 1024
    }
}
