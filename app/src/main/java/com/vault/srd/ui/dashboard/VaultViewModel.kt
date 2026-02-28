package com.vault.srd.ui.dashboard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItem
import com.vault.srd.data.VaultRepository
import com.vault.srd.data.GlobalSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class PendingImportFile(
    val uri: Uri,
    val name: String,
    val extension: String?,
    val isImage: Boolean
)

class VaultViewModel(val repository: VaultRepository) : ViewModel() {

    private val _sortOrder = MutableStateFlow(SortOrder.CREATED_LAST)
    val sortOrder = _sortOrder.asStateFlow()

    private val _deleteOriginalFiles = MutableStateFlow(repository.securityManager.shouldDeleteOriginalFiles())
    val deleteOriginalFiles = _deleteOriginalFiles.asStateFlow()

    private val activeLongOperations = AtomicInteger(0)
    private val vaultCreationInProgress = AtomicBoolean(false)
    private val pendingSearchOpenTarget = MutableStateFlow<Pair<Int, Int>?>(null)
    private val securitySessionViewModel = SecuritySessionViewModel(repository, viewModelScope)
    private val vaultItemViewModel = VaultItemViewModel(repository)
    private val folderViewModel = FolderViewModel(repository)

    val unlockedVaultIds = securitySessionViewModel.unlockedVaultIds
    val failedAttempts = securitySessionViewModel.failedAttempts
    val isLockedOut = securitySessionViewModel.isLockedOut
    val lockoutSecondsRemaining = securitySessionViewModel.lockoutSecondsRemaining
    val inactivityTimeoutSeconds = securitySessionViewModel.inactivityTimeoutSeconds

    private fun generateDecoyCredentials(): Pair<String, String> {
        val salt = repository.securityManager.generateSalt()
        val saltStr = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val randomPinMaterial = UUID.randomUUID().toString()
        val pinHash = repository.securityManager.hashPin(randomPinMaterial, salt)
        return pinHash to saltStr
    }

    // Hidden Decoy Logic: Filter out vaults where isDecoy == true
    val vaults: StateFlow<List<Vault>> = combine(repository.allVaults, _sortOrder) { list, order ->
        val filteredList = list.filter { !it.isDecoy }
        when (order) {
            SortOrder.NAME_ASC -> filteredList.sortedBy { it.name }
            SortOrder.NAME_DESC -> filteredList.sortedByDescending { it.name }
            SortOrder.CREATED_FIRST -> filteredList.sortedBy { it.createdAt }
            SortOrder.CREATED_LAST -> filteredList.sortedByDescending { it.createdAt }
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Ensure a hidden decoy vault exists
        viewModelScope.launch {
            repository.allVaults.first().let { all ->
                val decoyVault = all.firstOrNull { it.isDecoy }
                if (decoyVault == null) {
                    val (pinHash, pinSalt) = generateDecoyCredentials()
                    repository.createVault(
                        name = "SYSTEM_RESERVED",
                        pinHash = pinHash,
                        pinSalt = pinSalt,
                        color = null,
                        logo = null,
                        desc = "Decoy Storage Area",
                        isDecoy = true
                    )
                } else if (
                    decoyVault.pinHash == "DECOY_PLACEHOLDER" ||
                    decoyVault.pinSalt == "DECOY_PLACEHOLDER"
                ) {
                    val (pinHash, pinSalt) = generateDecoyCredentials()
                    repository.updateVault(
                        decoyVault.copy(
                            pinHash = pinHash,
                            pinSalt = pinSalt
                        )
                    )
                }
            }
        }
    }

    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun setInactivityTimeout(seconds: Int) { securitySessionViewModel.setInactivityTimeout(seconds) }

    fun setDeleteOriginalFiles(enabled: Boolean) {
        _deleteOriginalFiles.value = enabled
        repository.securityManager.setDeleteOriginalFiles(enabled)
    }
    
    fun unlockVault(vaultId: Int) { 
        securitySessionViewModel.unlockVault(vaultId)
        resetInactivityTimer()
    }
    
    fun lockAllVaults() {
        securitySessionViewModel.lockAllVaults()
        repository.securityManager.clearAllVaultStrictBiometricSessions()
    }

    fun resetInactivityTimer() {
        securitySessionViewModel.resetInactivityTimer {
            activeLongOperations.get()
        }
    }

    fun isVaultUnlocked(vaultId: Int): Boolean = securitySessionViewModel.isVaultUnlocked(vaultId)

    fun recordFailedAttempt(
        vaultId: Int?,
        onTakeSelfie: () -> Unit,
        onAutoWipe: () -> Unit = {}
    ) {
        securitySessionViewModel.recordFailedAttempt(vaultId, onTakeSelfie, onAutoWipe)
    }

    fun resetFailedAttempts() {
        securitySessionViewModel.resetFailedAttempts()
    }

    fun createVault(name: String, pin: String, color: String?, logo: String?, desc: String?, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!vaultCreationInProgress.compareAndSet(false, true)) {
                withContext(Dispatchers.Main) { onResult(false) }
                return@launch
            }
            try {
                val salt = repository.securityManager.generateSalt()
                val saltStr = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
                val pinHash = repository.securityManager.hashPin(pin, salt)

                val success = try {
                    repository.createVault(name, pinHash, saltStr, color, logo, desc, false)
                } catch (e: Exception) { false }
                withContext(Dispatchers.Main) { onResult(success) }
            } finally {
                vaultCreationInProgress.set(false)
            }
        }
    }

    fun addItem(
        vaultId: Int, 
        type: String, 
        name: String, 
        description: String?, 
        content: String?, 
        username: String? = null,
        passCategory: String? = null,
        link: String? = null,
        logoUri: Uri? = null,
        ext: String? = null,
        email: String? = null,
        phoneNumber: String? = null,
        folderId: Int? = null,
        tagNames: List<String> = emptyList(),
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val error = vaultItemViewModel.addItem(
                vaultId = vaultId,
                type = type,
                name = name,
                description = description,
                content = content,
                username = username,
                passCategory = passCategory,
                link = link,
                logoUri = logoUri,
                extension = ext,
                email = email,
                phoneNumber = phoneNumber,
                folderId = folderId,
                tagNames = tagNames
            )
            withContext(Dispatchers.Main) { onResult(error) }
        }
    }

    fun updateItem(newItem: VaultItem) {
        viewModelScope.launch(Dispatchers.IO) { vaultItemViewModel.updateItem(newItem) }
    }

    fun deleteVault(vault: Vault, onComplete: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            beginLongOperation()
            try {
                repository.deleteVault(vault)
                withContext(Dispatchers.Main) { onComplete() }
            } finally {
                endLongOperation()
            }
        }
    }

    fun deleteItem(item: VaultItem) {
        viewModelScope.launch(Dispatchers.IO) {
            beginLongOperation()
            try {
                vaultItemViewModel.deleteItem(item)
            } finally {
                endLongOperation()
            }
        }
    }

    // Folder Management
    fun getFoldersForVault(vaultId: Int): Flow<List<VaultFolder>> = folderViewModel.getFoldersForVault(vaultId)

    fun createFolder(vaultId: Int, name: String, desc: String?, type: String? = null, onResult: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = folderViewModel.createFolder(vaultId, name, desc, type)
            withContext(Dispatchers.Main) { onResult(id) }
        }
    }

    fun deleteFolder(folder: VaultFolder) {
        viewModelScope.launch(Dispatchers.IO) { folderViewModel.deleteFolder(folder) }
    }

    fun renameFolder(folder: VaultFolder, newName: String, newDescription: String?, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = runCatching {
                folderViewModel.renameFolder(folder, newName, newDescription)
            }.isSuccess
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    suspend fun getItemCountInFolder(folderId: Int): Int = folderViewModel.getItemCountInFolder(folderId)

    fun getItemsInFolder(folderId: Int): Flow<List<VaultItem>> = folderViewModel.getItemsInFolder(folderId)

    fun addItemsFromUris(
        vaultId: Int,
        type: String,
        items: List<PendingImportFile>,
        description: String?,
        folderId: Int? = null,
        deleteOriginal: Boolean = false,
        tagNames: List<String> = emptyList(),
        onProgress: ((Int, Int) -> Unit)? = null,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            beginLongOperation()
            try {
                val error = vaultItemViewModel.addItemsFromUris(
                    vaultId = vaultId,
                    type = type,
                    items = items,
                    description = description,
                    folderId = folderId,
                    deleteOriginal = deleteOriginal,
                    tagNames = tagNames,
                    onProgress = onProgress
                )
                withContext(Dispatchers.Main) { onResult(error) }
            } finally {
                endLongOperation()
            }
        }
    }

    fun addItemFromUri(
        vaultId: Int,
        type: String,
        name: String,
        description: String?,
        uri: Uri,
        extension: String?,
        isImage: Boolean,
        folderId: Int? = null,
        deleteOriginal: Boolean = false,
        tagNames: List<String> = emptyList(),
        onProgress: ((Long) -> Unit)? = null,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            beginLongOperation()
            try {
                val error = vaultItemViewModel.addItemFromUri(
                    vaultId = vaultId,
                    type = type,
                    name = name,
                    description = description,
                    uri = uri,
                    extension = extension,
                    isImage = isImage,
                    folderId = folderId,
                    deleteOriginal = deleteOriginal,
                    tagNames = tagNames,
                    onProgress = onProgress
                )
                withContext(Dispatchers.Main) { onResult(error) }
            } finally {
                endLongOperation()
            }
        }
    }

    fun deleteSelection(
        selectedItems: List<VaultItem>,
        selectedFolders: List<VaultFolder>,
        allItems: List<VaultItem>,
        onComplete: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            beginLongOperation()
            try {
                val error = vaultItemViewModel.deleteSelection(
                    selectedItems = selectedItems,
                    selectedFolders = selectedFolders,
                    allItems = allItems
                )
                withContext(Dispatchers.Main) { onComplete(error) }
            } finally {
                endLongOperation()
            }
        }
    }

    private fun beginLongOperation() {
        activeLongOperations.incrementAndGet()
        repository.securityManager.recordUserInteraction()
        resetInactivityTimer()
    }

    private fun endLongOperation() {
        val remaining = activeLongOperations.updateAndGet { current ->
            if (current <= 0) 0 else current - 1
        }
        if (remaining == 0) {
            resetInactivityTimer()
        }
    }

    fun beginExternalActivity() {
        beginLongOperation()
    }

    fun endExternalActivity() {
        endLongOperation()
    }

    fun hasActiveOperations(): Boolean = activeLongOperations.get() > 0

    suspend fun searchUnlockedItems(query: String): List<GlobalSearchResult> {
        return vaultItemViewModel.searchUnlockedItems(query, unlockedVaultIds.value)
    }

    fun setPendingSearchOpenTarget(vaultId: Int, itemId: Int) {
        if (vaultId <= 0 || itemId <= 0) return
        pendingSearchOpenTarget.value = vaultId to itemId
    }

    fun consumePendingSearchOpenItemId(vaultId: Int): Int? {
        val target = pendingSearchOpenTarget.value ?: return null
        if (target.first != vaultId) return null
        pendingSearchOpenTarget.value = null
        return target.second
    }

    fun getItemsForVaultByTag(vaultId: Int, tagId: String): Flow<List<VaultItem>> {
        return repository.getItemsForVaultByTag(vaultId, tagId)
    }

    fun getTagsForVault(vaultId: Int): Flow<List<com.vault.srd.data.VaultTag>> {
        return repository.getTagsForVault(vaultId)
    }

    fun getTagsForVaultByType(vaultId: Int, type: String): Flow<List<com.vault.srd.data.VaultTag>> {
        return repository.getTagsForVaultByType(vaultId, type)
    }

    suspend fun getTagIdsForItem(itemId: Int): List<String> {
        return repository.getTagIdsForItem(itemId)
    }

    suspend fun getTagNamesForItem(itemId: Int): List<String> {
        return repository.getTagsForItem(itemId).map { it.name }
    }

    suspend fun assignItemTagNames(vaultId: Int, itemId: Int, tagNames: List<String>) {
        val tagIds = repository.ensureTagIdsForNames(vaultId, tagNames)
        repository.replaceItemTagIds(itemId, tagIds)
    }

    /**
     * Global Decoy PIN Management
     */
    fun setDecoyPin(pin: String) = repository.securityManager.setDecoyPin(pin)
    fun isDecoyPin(pin: String): Boolean = repository.securityManager.verifyDecoyPin(pin)
    fun setDecoyVaultId(id: Int) = repository.securityManager.setDecoyVaultId(id)
    fun getDecoyVaultId(): Int = repository.securityManager.getDecoyVaultId()
    fun hasDecoyPin(): Boolean = repository.securityManager.hasDecoyPin()

    fun getVaultById(id: Int): Vault? {
        return vaults.value.find { it.id == id }
    }

    fun getHiddenDecoyVault(): Flow<Vault?> = repository.allVaults.map { list ->
        list.find { it.isDecoy }
    }

    fun getItemsForVault(vaultId: Int): Flow<List<VaultItem>> = repository.getItemsForVault(vaultId)
}

enum class SortOrder { NAME_ASC, NAME_DESC, CREATED_FIRST, CREATED_LAST }
