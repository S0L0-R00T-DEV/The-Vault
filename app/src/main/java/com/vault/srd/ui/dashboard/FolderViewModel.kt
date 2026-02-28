package com.vault.srd.ui.dashboard

import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItem
import com.vault.srd.data.VaultRepository
import kotlinx.coroutines.flow.Flow

class FolderViewModel(
    private val repository: VaultRepository
) {
    fun getFoldersForVault(vaultId: Int): Flow<List<VaultFolder>> = repository.getFoldersForVault(vaultId)

    suspend fun createFolder(vaultId: Int, name: String, desc: String?, type: String?): Long {
        return repository.createFolder(vaultId, name, desc, type)
    }

    suspend fun deleteFolder(folder: VaultFolder) {
        repository.deleteFolder(folder)
    }

    suspend fun renameFolder(folder: VaultFolder, newName: String, newDescription: String?) {
        repository.updateFolder(
            folder.copy(
                name = newName,
                description = newDescription
            )
        )
    }

    suspend fun getItemCountInFolder(folderId: Int): Int = repository.getItemCountInFolder(folderId)

    fun getItemsInFolder(folderId: Int): Flow<List<VaultItem>> = repository.getItemsInFolder(folderId)
}
