package com.vault.srd.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.vault.srd.security.SecurityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class GlobalSearchResult(
    val item: VaultItem,
    val vaultName: String,
    val folderName: String?
)

class VaultRepository(
    private val context: Context,
    private val vaultDao: VaultDao,
    val securityManager: SecurityManager
) {
    companion object {
        const val MAX_USER_VAULTS = 10
        private const val IO_BUFFER_SIZE = 1024 * 1024
    }

    val allVaults: Flow<List<Vault>> = vaultDao.getAllVaults()

    suspend fun createVault(name: String, pinHash: String, pinSalt: String, color: String?, logo: String?, desc: String?, isDecoy: Boolean = false): Boolean {
        return try {
            val normalizedName = name.trim()
            if (normalizedName.isBlank()) return false
            if (!isDecoy && vaultDao.getUserVaultCount() >= MAX_USER_VAULTS) {
                return false
            }
            if (!isDecoy && vaultDao.countVaultsByName(normalizedName) > 0) {
                return false
            }
            vaultDao.insertVault(Vault(name = normalizedName, pinHash = pinHash, pinSalt = pinSalt, colorHex = color, logoPath = logo, description = desc, isDecoy = isDecoy))
            true
        } catch (e: Exception) { false }
    }

    suspend fun deleteVault(vault: Vault) {
        // Best-effort cleanup of file artifacts before removing DB rows.
        val vaultItems = vaultDao.getItemsForVaultOnce(vault.id)
        vaultItems.forEach { item ->
            item.filePath?.let { secureDelete(File(it)) }
            item.logoPath?.let { secureDelete(File(it)) }
        }
        vaultDao.deleteItemTagsByVaultId(vault.id)
        vaultDao.deleteTagsByVaultId(vault.id)
        vaultDao.deleteItemsByVaultId(vault.id)
        vaultDao.deleteFoldersByVaultId(vault.id)
        vaultDao.deleteVault(vault)
    }

    suspend fun getVaultById(id: Int): Vault? = vaultDao.getVaultById(id)

    suspend fun updateVault(vault: Vault) {
        vaultDao.updateVault(vault)
    }

    suspend fun wipeVaultContents(vaultId: Int) {
        val vaultItems = vaultDao.getItemsForVaultOnce(vaultId)
        vaultItems.forEach { item ->
            item.filePath?.let { secureDelete(File(it)) }
            item.logoPath?.let { secureDelete(File(it)) }
        }
        vaultDao.deleteItemTagsByVaultId(vaultId)
        vaultDao.deleteTagsByVaultId(vaultId)
        vaultDao.deleteItemsByVaultId(vaultId)
        vaultDao.deleteFoldersByVaultId(vaultId)
    }

    // Folder Logic
    fun getFoldersForVault(vaultId: Int): Flow<List<VaultFolder>> = vaultDao.getFoldersForVault(vaultId)
    
    suspend fun createFolder(vaultId: Int, name: String, desc: String?, type: String? = null): Long {
        val finalName = if (name.isNotBlank()) {
            name
        } else {
            generateAutoFolderName(vaultId, type)
        }
        return vaultDao.insertFolder(VaultFolder(vaultId = vaultId, name = finalName, description = desc))
    }

    private suspend fun generateAutoFolderName(vaultId: Int, type: String?): String {
        val existing = if (type == null) {
            vaultDao.getFoldersForVaultOnce(vaultId)
        } else {
            vaultDao.getFoldersForVaultAndTypeOnce(vaultId, type)
        }
        val maxIndex = existing.mapNotNull { folder ->
            val n = folder.name
            if (n.startsWith("F")) {
                n.drop(1).toIntOrNull()
            } else null
        }.maxOrNull() ?: 0
        val nextIndex = maxIndex + 1
        return "F$nextIndex"
    }

    suspend fun deleteFolder(folder: VaultFolder) {
        vaultDao.deleteFolder(folder)
    }

    suspend fun updateFolder(folder: VaultFolder) {
        val normalizedName = folder.name.trim()
        if (normalizedName.isBlank()) return
        vaultDao.updateFolder(folder.copy(name = normalizedName))
    }

    suspend fun getItemCountInFolder(folderId: Int): Int = vaultDao.getItemCountInFolder(folderId)

    fun getItemsInFolder(folderId: Int): Flow<List<VaultItem>> = vaultDao.getItemsInFolder(folderId).map { items ->
        items.map(::decryptItemFields)
    }

    fun getItemsForVault(vaultId: Int): Flow<List<VaultItem>> = vaultDao.getItemsForVault(vaultId).map { items ->
        items.map(::decryptItemFields)
    }

    fun getItemsForVaultByTag(vaultId: Int, tagId: String): Flow<List<VaultItem>> =
        vaultDao.getItemsForVaultByTag(vaultId, tagId).map { items ->
            items.map(::decryptItemFields)
        }

    suspend fun isDuplicate(vaultId: Int, name: String): Boolean {
        return vaultDao.checkDuplicate(vaultId, name) > 0
    }

    suspend fun addItem(
        vaultId: Int, 
        type: String, 
        name: String, 
        description: String?, 
        content: String?, 
        username: String? = null,
        passCategory: String? = null,
        link: String? = null,
        logoPath: String? = null,
        filePath: String? = null,
        extension: String? = null,
        email: String? = null,
        phoneNumber: String? = null,
        folderId: Int? = null,
        tagIds: List<String> = emptyList()
    ) {
        val encryptedContent = if (content != null) securityManager.encryptForVault(vaultId, content) else null
        val encryptedUsername = if (username != null) securityManager.encryptForVault(vaultId, username) else null
        val encryptedEmail = if (email != null) securityManager.encryptForVault(vaultId, email) else null
        val encryptedPhone = if (phoneNumber != null) securityManager.encryptForVault(vaultId, phoneNumber) else null
        
        val itemId = vaultDao.insertItem(VaultItem(
            vaultId = vaultId, 
            type = type, 
            name = name, 
            description = description,
            content = encryptedContent,
            username = encryptedUsername,
            passCategory = passCategory,
            link = link,
            logoPath = logoPath,
            filePath = filePath,
            extension = extension,
            email = encryptedEmail,
            phoneNumber = encryptedPhone,
            folderId = folderId
        )).toInt()
        if (itemId > 0 && tagIds.isNotEmpty()) {
            replaceItemTagIds(itemId, tagIds)
        }
    }

    suspend fun updateItem(newItem: VaultItem) {
        val encryptedContent = newItem.content?.let { securityManager.encryptForVault(newItem.vaultId, it) }
        val encryptedUsername = newItem.username?.let { securityManager.encryptForVault(newItem.vaultId, it) }
        val encryptedEmail = newItem.email?.let { securityManager.encryptForVault(newItem.vaultId, it) }
        val encryptedPhone = newItem.phoneNumber?.let { securityManager.encryptForVault(newItem.vaultId, it) }
        
        vaultDao.updateItem(newItem.copy(
            content = encryptedContent,
            username = encryptedUsername,
            email = encryptedEmail,
            phoneNumber = encryptedPhone,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun deleteItem(item: VaultItem) {
        item.filePath?.let { secureDelete(File(it)) }
        item.logoPath?.let { secureDelete(File(it)) }
        vaultDao.deleteItemTagsByItemId(item.id)
        vaultDao.deleteItem(item)
    }

    fun getTagsForVault(vaultId: Int): Flow<List<VaultTag>> = vaultDao.getTagsForVault(vaultId)

    fun getTagsForVaultByType(vaultId: Int, type: String): Flow<List<VaultTag>> {
        return vaultDao.getTagsForVaultByType(vaultId, type)
    }

    suspend fun getTagsForVaultOnce(vaultId: Int): List<VaultTag> = vaultDao.getTagsForVaultOnce(vaultId)

    suspend fun getTagIdsForItem(itemId: Int): List<String> = vaultDao.getTagIdsForItem(itemId)

    suspend fun getTagsForItem(itemId: Int): List<VaultTag> = vaultDao.getTagsForItem(itemId)

    suspend fun ensureTagIdsForNames(vaultId: Int, names: List<String>): List<String> {
        val normalized = names
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        if (normalized.isEmpty()) return emptyList()

        val existingByName = vaultDao.getTagsForVaultOnce(vaultId)
            .associateBy { it.name.lowercase() }
            .toMutableMap()
        val out = mutableListOf<String>()

        normalized.forEach { rawName ->
            val key = rawName.lowercase()
            val existing = existingByName[key]
            if (existing != null) {
                out += existing.id
                return@forEach
            }

            val tag = VaultTag(
                name = rawName,
                colorHex = randomTagColor(),
                vaultId = vaultId
            )
            vaultDao.insertTag(tag)
            val persisted = vaultDao.getTagById(tag.id)
                ?: vaultDao.getTagByName(vaultId, rawName)
                ?: tag
            existingByName[key] = persisted
            out += persisted.id
        }
        return out
    }

    suspend fun replaceItemTagIds(itemId: Int, tagIds: List<String>) {
        vaultDao.clearItemTagRefs(itemId)
        val normalized = tagIds
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalized.isEmpty()) return
        vaultDao.insertItemTagRefs(
            normalized.map { tagId ->
                VaultItemTagCrossRef(itemId = itemId, tagId = tagId)
            }
        )
    }

    suspend fun searchUnlockedItems(
        query: String,
        unlockedVaultIds: Set<Int>
    ): List<GlobalSearchResult> {
        val needle = query.trim().lowercase()
        if (needle.isBlank() || unlockedVaultIds.isEmpty()) return emptyList()

        val vaultsById = vaultDao.getAllVaultsOnce().associateBy { it.id }
        val foldersById = vaultDao.getAllFoldersOnce().associateBy { it.id }
        return vaultDao.getAllItemsOnce()
            .asSequence()
            .filter { unlockedVaultIds.contains(it.vaultId) }
            .map { item ->
                val decryptedUsername = item.username?.let {
                    runCatching { securityManager.decryptForVault(item.vaultId, it) }.getOrNull() ?: it
                }
                item to decryptedUsername
            }
            .filter { (item, username) ->
                item.name.lowercase().contains(needle) ||
                    (username?.lowercase()?.contains(needle) == true)
            }
            .map { (item, _) ->
                GlobalSearchResult(
                    item = item,
                    vaultName = vaultsById[item.vaultId]?.name ?: "Unknown Vault",
                    folderName = item.folderId?.let { foldersById[it]?.name }
                )
            }
            .toList()
    }

    suspend fun getDecryptedItemsOnce(vaultIds: Set<Int>? = null): List<VaultItem> {
        return vaultDao.getAllItemsOnce()
            .asSequence()
            .filter { vaultIds == null || vaultIds.contains(it.vaultId) }
            .map(::decryptItemFields)
            .toList()
    }

    private fun secureDelete(file: File) {
        if (file.exists()) {
            try {
                val length = file.length()
                if (length > 0) {
                    val zeros = ByteArray(256 * 1024) { 0 }
                    val raf = java.io.RandomAccessFile(file, "rws")
                    try {
                        var p: Long = 0
                        while (p < length) {
                            raf.seek(p)
                            val len = minOf(zeros.size.toLong(), length - p).toInt()
                            raf.write(zeros, 0, len)
                            p += len
                        }
                        raf.fd.sync()
                    } finally {
                        raf.close()
                    }
                }
                if (!file.delete()) {
                    val tombstone = File(file.parentFile, "${file.name}.del_${System.currentTimeMillis()}")
                    if (file.renameTo(tombstone)) {
                        tombstone.delete()
                    } else {
                        file.delete()
                    }
                }
            } catch (e: Exception) {
                // If secure delete fails, try normal delete
                file.delete()
            }
        }
    }

    private fun decryptItemFields(item: VaultItem): VaultItem {
        val decryptedContent = item.content?.let {
            try { securityManager.decryptForVault(item.vaultId, it) } catch (_: Exception) { it }
        }
        val decryptedUsername = item.username?.let {
            try { securityManager.decryptForVault(item.vaultId, it) } catch (_: Exception) { it }
        }
        val decryptedEmail = item.email?.let {
            try { securityManager.decryptForVault(item.vaultId, it) } catch (_: Exception) { it }
        }
        val decryptedPhone = item.phoneNumber?.let {
            try { securityManager.decryptForVault(item.vaultId, it) } catch (_: Exception) { it }
        }
        return item.copy(
            content = decryptedContent,
            username = decryptedUsername,
            email = decryptedEmail,
            phoneNumber = decryptedPhone
        )
    }

    private fun randomTagColor(): String {
        val palette = listOf(
            "#FF6B6B",
            "#4ECDC4",
            "#45B7D1",
            "#96CEB4",
            "#FFA726",
            "#BA68C8",
            "#64B5F6",
            "#F06292"
        )
        return palette.random()
    }

    fun saveFileFromUri(uri: Uri, stripImageMetadata: Boolean = false): String? {
        return saveFileFromUriInternal(uri, stripImageMetadata, null)
    }

    fun saveFileFromUriWithProgress(
        uri: Uri,
        stripImageMetadata: Boolean = false,
        onBytesCopied: ((Long) -> Unit)?
    ): String? {
        return saveFileFromUriInternal(uri, stripImageMetadata, onBytesCopied)
    }

    private fun saveFileFromUriInternal(
        uri: Uri,
        stripImageMetadata: Boolean,
        onBytesCopied: ((Long) -> Unit)?
    ): String? {
        return try {
            val fileName = UUID.randomUUID().toString()
            val file = File(context.filesDir, fileName)
            if (stripImageMetadata) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                val sampleOptions = BitmapFactory.Options().apply {
                    inSampleSize = calculateInSampleSize(options, 1600, 1600)
                }
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, sampleOptions)
                }
                if (bitmap != null) {
                    FileOutputStream(file).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
                        output.flush()
                    }
                    bitmap.recycle()
                    onBytesCopied?.invoke(file.length())
                } else {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output ->
                            copyStream(input, output, onBytesCopied)
                            output.fd.sync()
                        }
                    } ?: return null
                }
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output ->
                        copyStream(input, output, onBytesCopied)
                        output.fd.sync()
                    }
                } ?: return null
            }
            file.absolutePath
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream, onBytesCopied: ((Long) -> Unit)? = null) {
        val buffer = ByteArray(IO_BUFFER_SIZE)
        var total = 0L
        var lastReported = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read.toLong()
            if (onBytesCopied != null && total - lastReported >= 8L * 1024L * 1024L) {
                lastReported = total
                onBytesCopied.invoke(total)
            }
        }
        onBytesCopied?.invoke(total)
    }

    fun deleteOriginalUri(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
                // best-effort only
            }
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
