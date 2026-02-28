package com.vault.srd.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vaults")
    fun getAllVaults(): Flow<List<Vault>>

    @Query("SELECT * FROM vaults")
    suspend fun getAllVaultsOnce(): List<Vault>

    @Query("SELECT COUNT(*) FROM vaults WHERE isDecoy = 0")
    suspend fun getUserVaultCount(): Int

    @Query("SELECT COUNT(*) FROM vaults WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun countVaultsByName(name: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVault(vault: Vault)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVaultAndReturnId(vault: Vault): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVaults(vaults: List<Vault>)

    @Update
    suspend fun updateVault(vault: Vault)

    @Query("SELECT * FROM vault_items WHERE vaultId = :vaultId")
    fun getItemsForVault(vaultId: Int): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE vaultId = :vaultId")
    suspend fun getItemsForVaultOnce(vaultId: Int): List<VaultItem>

    @Query("SELECT * FROM vault_items")
    suspend fun getAllItemsOnce(): List<VaultItem>

    @Query("SELECT COUNT(*) FROM vault_items WHERE vaultId = :vaultId AND name = :name")
    suspend fun checkDuplicate(vaultId: Int, name: String): Int

    @Insert
    suspend fun insertItem(item: VaultItem): Long

    @Insert
    suspend fun insertItems(items: List<VaultItem>)

    @Query("SELECT * FROM vaults WHERE id = :id LIMIT 1")
    suspend fun getVaultById(id: Int): Vault?

    @Update
    suspend fun updateItem(item: VaultItem)

    @Delete
    suspend fun deleteItem(item: VaultItem)

    @Delete
    suspend fun deleteVault(vault: Vault)

    @Query("DELETE FROM vault_items WHERE vaultId = :vaultId")
    suspend fun deleteItemsByVaultId(vaultId: Int)

    @Query("DELETE FROM folders WHERE vaultId = :vaultId")
    suspend fun deleteFoldersByVaultId(vaultId: Int)

    @Query("DELETE FROM vault_item_tags WHERE itemId = :itemId")
    suspend fun deleteItemTagsByItemId(itemId: Int)

    @Query("DELETE FROM vault_item_tags WHERE tagId IN (SELECT id FROM vault_tags WHERE vaultId = :vaultId)")
    suspend fun deleteItemTagsByVaultId(vaultId: Int)

    @Query("DELETE FROM vault_tags WHERE vaultId = :vaultId")
    suspend fun deleteTagsByVaultId(vaultId: Int)

    @Query("DELETE FROM vault_items")
    suspend fun clearVaultItems()

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Query("DELETE FROM vaults")
    suspend fun clearVaults()

    @Query("DELETE FROM vault_item_tags")
    suspend fun clearItemTagRefsByAll()

    @Query("DELETE FROM vault_tags")
    suspend fun clearTags()

    // Folder Queries
    @Query("SELECT * FROM folders WHERE vaultId = :vaultId")
    fun getFoldersForVault(vaultId: Int): Flow<List<VaultFolder>>

    @Query("SELECT * FROM folders")
    suspend fun getAllFoldersOnce(): List<VaultFolder>

    @Insert
    suspend fun insertFolder(folder: VaultFolder): Long

    @Insert
    suspend fun insertFolders(folders: List<VaultFolder>)

    @Update
    suspend fun updateFolder(folder: VaultFolder)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: VaultTag): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTags(tags: List<VaultTag>)

    @Delete
    suspend fun deleteTag(tag: VaultTag)

    @Query("SELECT * FROM vault_tags WHERE vaultId = :vaultId ORDER BY name COLLATE NOCASE ASC")
    fun getTagsForVault(vaultId: Int): Flow<List<VaultTag>>

    @Query("""
        SELECT DISTINCT t.* FROM vault_tags t
        INNER JOIN vault_item_tags r ON t.id = r.tagId
        INNER JOIN vault_items i ON i.id = r.itemId
        WHERE i.vaultId = :vaultId AND i.type = :type
        ORDER BY t.name COLLATE NOCASE ASC
    """)
    fun getTagsForVaultByType(vaultId: Int, type: String): Flow<List<VaultTag>>

    @Query("SELECT * FROM vault_tags WHERE vaultId = :vaultId ORDER BY name COLLATE NOCASE ASC")
    suspend fun getTagsForVaultOnce(vaultId: Int): List<VaultTag>

    @Query("SELECT * FROM vault_tags")
    suspend fun getAllTagsOnce(): List<VaultTag>

    @Query("SELECT * FROM vault_tags WHERE id = :tagId LIMIT 1")
    suspend fun getTagById(tagId: String): VaultTag?

    @Query("SELECT * FROM vault_tags WHERE vaultId = :vaultId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getTagByName(vaultId: Int, name: String): VaultTag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemTagRefs(refs: List<VaultItemTagCrossRef>)

    @Query("DELETE FROM vault_item_tags WHERE itemId = :itemId")
    suspend fun clearItemTagRefs(itemId: Int)

    @Query("SELECT tagId FROM vault_item_tags WHERE itemId = :itemId")
    suspend fun getTagIdsForItem(itemId: Int): List<String>

    @Query("SELECT * FROM vault_item_tags")
    suspend fun getAllItemTagRefsOnce(): List<VaultItemTagCrossRef>

    @Query("""
        SELECT t.* FROM vault_tags t
        INNER JOIN vault_item_tags r ON t.id = r.tagId
        WHERE r.itemId = :itemId
        ORDER BY t.name COLLATE NOCASE ASC
    """)
    suspend fun getTagsForItem(itemId: Int): List<VaultTag>

    @Query("""
        SELECT i.* FROM vault_items i
        INNER JOIN vault_item_tags r ON i.id = r.itemId
        WHERE i.vaultId = :vaultId AND r.tagId = :tagId
        ORDER BY i.createdAt DESC
    """)
    fun getItemsForVaultByTag(vaultId: Int, tagId: String): Flow<List<VaultItem>>

    @Delete
    suspend fun deleteFolder(folder: VaultFolder)

    @Query("SELECT COUNT(*) FROM vault_items WHERE folderId = :folderId")
    suspend fun getItemCountInFolder(folderId: Int): Int

    @Query("SELECT * FROM vault_items WHERE folderId = :folderId")
    fun getItemsInFolder(folderId: Int): Flow<List<VaultItem>>

    // Synchronous folder list for auto-naming (F1, F2, ...)
    @Query("SELECT * FROM folders WHERE vaultId = :vaultId")
    suspend fun getFoldersForVaultOnce(vaultId: Int): List<VaultFolder>

    // Folders that currently contain at least one item of a given type (per-tab folders)
    @Query("SELECT DISTINCT f.* FROM folders f INNER JOIN vault_items i ON f.id = i.folderId WHERE f.vaultId = :vaultId AND i.type = :type")
    suspend fun getFoldersForVaultAndTypeOnce(vaultId: Int, type: String): List<VaultFolder>
}
