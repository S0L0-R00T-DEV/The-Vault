package com.vault.srd.ui.dashboard

import android.net.Uri
import com.vault.srd.data.GlobalSearchResult
import com.vault.srd.data.VaultItem
import com.vault.srd.data.VaultRepository
import java.io.File

class VaultItemViewModel(
    private val repository: VaultRepository
) {
    suspend fun addItem(
        vaultId: Int,
        type: String,
        name: String,
        description: String?,
        content: String?,
        username: String?,
        passCategory: String?,
        link: String?,
        logoUri: Uri?,
        extension: String?,
        email: String?,
        phoneNumber: String?,
        folderId: Int?,
        tagNames: List<String>
    ): String? {
        if (repository.isDuplicate(vaultId, name)) {
            return "Name already exists"
        }
        val logoPath = logoUri?.let { repository.saveFileFromUri(it, stripImageMetadata = true) }
        if (logoUri != null && logoPath == null) {
            return "Could not import selected logo."
        }
        val tagIds = repository.ensureTagIdsForNames(vaultId, tagNames)
        repository.addItem(
            vaultId = vaultId,
            type = type,
            name = name,
            description = description,
            content = content,
            username = username,
            passCategory = passCategory,
            link = link,
            logoPath = logoPath,
            extension = extension,
            email = email,
            phoneNumber = phoneNumber,
            folderId = folderId,
            tagIds = tagIds
        )
        return null
    }

    suspend fun addItemsFromUris(
        vaultId: Int,
        type: String,
        items: List<PendingImportFile>,
        description: String?,
        folderId: Int?,
        deleteOriginal: Boolean,
        tagNames: List<String>,
        onProgress: ((Int, Int) -> Unit)? = null
    ): String? {
        val tagIds = repository.ensureTagIdsForNames(vaultId, tagNames)
        var imported = 0
        var failed = 0
        val total = items.size.coerceAtLeast(1)
        items.forEachIndexed { index, pending ->
            onProgress?.invoke(index + 1, total)
            val filePath = repository.saveFileFromUri(
                uri = pending.uri,
                stripImageMetadata = pending.isImage
            )
            if (filePath == null) {
                failed++
                return@forEachIndexed
            }

            try {
                repository.addItem(
                    vaultId = vaultId,
                    type = type,
                    name = pending.name,
                    description = description,
                    content = null,
                    filePath = filePath,
                    extension = pending.extension,
                    folderId = folderId,
                    tagIds = tagIds
                )
                imported++
                if (deleteOriginal) {
                    repository.deleteOriginalUri(pending.uri)
                }
            } catch (_: Exception) {
                runCatching { File(filePath).delete() }
                failed++
            }
        }
        return if (imported == 0) "No files were imported" else null
    }

    suspend fun addItemFromUri(
        vaultId: Int,
        type: String,
        name: String,
        description: String?,
        uri: Uri,
        extension: String?,
        isImage: Boolean,
        folderId: Int?,
        deleteOriginal: Boolean,
        tagNames: List<String>,
        onProgress: ((Long) -> Unit)? = null
    ): String? {
        if (repository.isDuplicate(vaultId, name)) {
            return "Name already exists"
        }
        val tagIds = repository.ensureTagIdsForNames(vaultId, tagNames)
        val filePath = if (onProgress != null) {
            repository.saveFileFromUriWithProgress(
                uri = uri,
                stripImageMetadata = isImage,
                onBytesCopied = onProgress
            )
        } else {
            repository.saveFileFromUri(
                uri = uri,
                stripImageMetadata = isImage
            )
        } ?: return "Could not import this file."
        return try {
            repository.addItem(
                vaultId = vaultId,
                type = type,
                name = name,
                description = description,
                content = null,
                filePath = filePath,
                extension = extension,
                folderId = folderId,
                tagIds = tagIds
            )
            if (deleteOriginal) {
                repository.deleteOriginalUri(uri)
            }
            null
        } catch (e: Exception) {
            runCatching { File(filePath).delete() }
            e.message ?: "Could not add selected file."
        }
    }

    suspend fun updateItem(newItem: VaultItem) {
        repository.updateItem(newItem)
    }

    suspend fun deleteItem(item: VaultItem) {
        repository.deleteItem(item)
    }

    suspend fun deleteSelection(
        selectedItems: List<VaultItem>,
        selectedFolders: List<com.vault.srd.data.VaultFolder>,
        allItems: List<VaultItem>
    ): String? {
        return try {
            val selectedFolderIds = selectedFolders.map { it.id }.toSet()
            val fromFolders = if (selectedFolderIds.isEmpty()) {
                emptyList()
            } else {
                allItems.filter { it.folderId != null && selectedFolderIds.contains(it.folderId) }
            }
            val uniqueItems = (selectedItems + fromFolders).distinctBy { it.id }

            uniqueItems.forEach { repository.deleteItem(it) }
            selectedFolders.forEach { repository.deleteFolder(it) }
            null
        } catch (e: Exception) {
            e.message ?: "Delete failed"
        }
    }

    suspend fun searchUnlockedItems(query: String, unlockedVaultIds: Set<Int>): List<GlobalSearchResult> {
        return repository.searchUnlockedItems(query, unlockedVaultIds)
    }
}
