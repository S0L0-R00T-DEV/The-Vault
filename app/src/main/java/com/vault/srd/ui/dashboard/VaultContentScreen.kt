package com.vault.srd.ui.dashboard

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.widget.TextView
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItem
import com.vault.srd.intruder.IntruderManager
import com.vault.srd.security.ClipboardClearWorker
import com.vault.srd.security.SecurityManager
import com.vault.srd.ui.security.BiometricAuth
import com.vault.srd.ui.common.rememberBitmapFromFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.noties.markwon.Markwon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultContentScreen(
    vault: Vault,
    viewModel: VaultViewModel,
    securityManager: SecurityManager,
    onBack: () -> Unit,
    onSwitchVault: (Vault) -> Unit = {}
) {
    val unlockedIdsState = viewModel.unlockedVaultIds.collectAsState()
    val isUnlocked = unlockedIdsState.value.contains(vault.id)
    
    val pinEntry = remember { mutableStateOf("") }
    val errorText = remember { mutableStateOf<String?>(null) }
    val showAddSheet = remember { mutableStateOf(false) }
    
    val lockoutState = viewModel.isLockedOut.collectAsState()
    val isLockedOut = lockoutState.value
    val lockoutTimeRemaining by viewModel.lockoutSecondsRemaining.collectAsState()
    
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val showBioError = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var isUnlocking by remember { mutableStateOf(false) }
    
    val selectedItems = remember { mutableStateListOf<VaultItem>() }
    val selectedFolderIds = remember { mutableStateListOf<Int>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingSelection by remember { mutableStateOf(false) }
    var deleteSelectionError by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportResultMessage by remember { mutableStateOf<String?>(null) }
    var showVaultDeleteDialog by remember { mutableStateOf(false) }
    
    var viewingImage by remember { mutableStateOf<VaultItem?>(null) }
    var viewingNote by remember { mutableStateOf<VaultItem?>(null) }
    var viewingPassword by remember { mutableStateOf<VaultItem?>(null) }
    var viewingFile by remember { mutableStateOf<VaultItem?>(null) }
    var viewingContact by remember { mutableStateOf<VaultItem?>(null) }
    var viewingFolder by remember { mutableStateOf<VaultFolder?>(null) }
    val intruderManager = remember {
        GlobalContext.get().get<IntruderManager>()
    }
    var pendingIntruderCapture by remember { mutableStateOf(false) }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingIntruderCapture) {
            intruderManager.takeSelfie(lifecycleOwner) { path ->
                android.util.Log.d("VaultContent", "Selfie saved: $path")
            }
        } else if (!granted) {
            errorText.value = "CAMERA PERMISSION DENIED"
            securityManager.setIntruderCaptureEnabled(false)
        }
        pendingIntruderCapture = false
    }

    fun tryBio(onSuccess: () -> Unit = {}) {
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS
        ) {
            val activity = context as? android.app.Activity
            if (activity == null) {
                showBioError.value = true
                return
            }
            BiometricAuth.authenticate(
                activity = activity,
                title = "AUTHENTICATE",
                subtitle = "Confirm identity",
                negativeButtonText = "USE PIN",
                onSuccess = onSuccess,
                onError = { showBioError.value = true }
            )
        } else {
            showBioError.value = true
        }
    }

    if (!isUnlocked) {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(vault.name.uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            if (!vault.description.isNullOrBlank()) {
                Text(vault.description, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            }
            if (isLockedOut) {
                Text("LOCKED OUT. WAIT ${lockoutTimeRemaining}S.", color = Color.Red, fontSize = 12.sp)
            }
            Spacer(Modifier.height(24.dp))
            TextField(
                enabled = !isLockedOut && !isUnlocking,
                value = pinEntry.value,
                onValueChange = { 
                    if (it.length <= 6) {
                        pinEntry.value = it
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    }
                },
                label = { Text("ENTER PIN") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(16.dp))

            // Optional randomized PIN pad
            val randomPinEnabled = remember { securityManager.isRandomPinPadEnabled() }
            if (randomPinEnabled && !isLockedOut && !isUnlocking) {
                val digits = remember { mutableStateListOf(*("0 1 2 3 4 5 6 7 8 9".split(" ").toTypedArray()) ) }
                LaunchedEffect(Unit) {
                    digits.shuffle()
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    for (row in 0 until 3) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            for (col in 0 until 3) {
                                val index = row * 3 + col + 1
                                val digit = digits[index % 10]
                                Button(
                                    onClick = {
                                        if (pinEntry.value.length < 6) {
                                            pinEntry.value += digit
                                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).padding(2.dp)
                                ) {
                                    Text(digit)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Spacer(Modifier.weight(1f))
                        val zeroDigit = digits[0]
                        Button(
                            onClick = {
                                if (pinEntry.value.length < 6) {
                                    pinEntry.value += zeroDigit
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                }
                            },
                            modifier = Modifier.weight(1f).padding(2.dp)
                        ) {
                            Text(zeroDigit)
                        }
                        Button(
                            onClick = { if (pinEntry.value.isNotEmpty()) pinEntry.value = pinEntry.value.dropLast(1) },
                            modifier = Modifier.weight(1f).padding(2.dp)
                        ) {
                            Text("DEL")
                        }
                    }
                }
            }

            if (errorText.value != null) Text(errorText.value!!, color = Color.Red, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !isLockedOut && !isUnlocking,
                    onClick = {
                        if (isUnlocking) return@Button
                        val pinSnapshot = pinEntry.value
                        isUnlocking = true
                        errorText.value = null
                        scope.launch {
                            try {
                                if (pinSnapshot.isBlank() || pinSnapshot.length < 4) {
                                    errorText.value = "ENTER PIN"
                                    return@launch
                                }

                                val isValid = withContext(Dispatchers.Default) {
                                    securityManager.verifyPin(pinSnapshot, vault.pinHash, vault.pinSalt)
                                }
                                if (isValid) {
                                    val upgradedHash = withContext(Dispatchers.Default) {
                                        securityManager.upgradedPinHashFromVerified(
                                            inputPin = pinSnapshot,
                                            storedHash = vault.pinHash,
                                            storedSalt = vault.pinSalt
                                        )
                                    }
                                    if (upgradedHash != null) {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                viewModel.repository.updateVault(vault.copy(pinHash = upgradedHash))
                                            }
                                        }
                                    }
                                    viewModel.unlockVault(vault.id)
                                    viewModel.resetFailedAttempts()
                                } else {
                                    val hasPanic = securityManager.hasPanicPin()
                                    val hasDecoy = securityManager.hasDecoyPin()
                                    val isPanic = if (hasPanic) {
                                        withContext(Dispatchers.Default) {
                                            securityManager.isPanicPin(pinSnapshot)
                                        }
                                    } else {
                                        false
                                    }
                                    if (isPanic) {
                                        viewModel.lockAllVaults()
                                        viewModel.resetFailedAttempts()
                                        pinEntry.value = ""
                                        errorText.value = "PANIC MODE TRIGGERED"
                                        return@launch
                                    }

                                    val isDecoy = if (hasDecoy) {
                                        withContext(Dispatchers.Default) {
                                            securityManager.verifyDecoyPin(pinSnapshot)
                                        }
                                    } else {
                                        false
                                    }
                                    if (isDecoy) {
                                        val decoyVault = withContext(Dispatchers.IO) {
                                            viewModel.repository.allVaults.first().find { it.isDecoy }
                                        }
                                        if (decoyVault != null) {
                                            onSwitchVault(decoyVault)
                                            viewModel.unlockVault(decoyVault.id)
                                            viewModel.resetFailedAttempts()
                                            pinEntry.value = ""
                                        } else {
                                            errorText.value = "DECOY SYSTEM ERROR"
                                        }
                                        return@launch
                                    }

                                    errorText.value = "INVALID PIN"
                                    viewModel.recordFailedAttempt(
                                        vaultId = vault.id,
                                        onTakeSelfie = {
                                            if (securityManager.isIntruderCaptureEnabled()) {
                                                val hasCameraPermission = ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.CAMERA
                                                ) == PackageManager.PERMISSION_GRANTED
                                                if (hasCameraPermission) {
                                                    intruderManager.takeSelfie(lifecycleOwner) { path ->
                                                        android.util.Log.d("VaultContent", "Selfie saved: $path")
                                                    }
                                                } else {
                                                    pendingIntruderCapture = true
                                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                                }
                                            }
                                        },
                                        onAutoWipe = {
                                            android.widget.Toast.makeText(
                                                context,
                                                "Vault wiped after failed attempts",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            errorText.value = "VAULT WIPED"
                                            pinEntry.value = ""
                                            onBack()
                                        }
                                    )
                                }
                            } finally {
                                isUnlocking = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                ) { Text(if (isUnlocking) "UNLOCKING..." else "UNLOCK") }
                val globalBiometricEnabled = securityManager.isGlobalBiometricAllowedForVault(vault.id)
                if (globalBiometricEnabled) {
                    IconButton(
                        enabled = !isUnlocking,
                        onClick = {
                            val activity = context as? android.app.Activity
                            if (activity == null) {
                                showBioError.value = true
                                return@IconButton
                            }
                            val challenge = securityManager.prepareGlobalVaultBiometricUnlock()
                            if (challenge == null) {
                                errorText.value = "Biometric unlock is not available. Re-enable fingerprint unlock."
                                return@IconButton
                            }
                            BiometricAuth.authenticateWithCipher(
                                activity = activity,
                                title = "AUTHENTICATE",
                                subtitle = "Confirm identity",
                                negativeButtonText = "USE PIN",
                                cipher = challenge.cipher,
                                onSuccess = { authenticatedCipher ->
                                    val ok = authenticatedCipher != null &&
                                        securityManager.verifyGlobalVaultBiometricUnlock(
                                            cipher = authenticatedCipher,
                                            payload = challenge.payload
                                        )
                                    if (!ok) {
                                        errorText.value = "Biometric key invalidated. Re-enable fingerprint unlock."
                                        return@authenticateWithCipher
                                    }
                                    viewModel.unlockVault(vault.id)
                                    viewModel.resetFailedAttempts()
                                },
                                onError = { showBioError.value = true }
                            )
                        },
                        modifier = Modifier.background(Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.Fingerprint, null, tint = Color.Black)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text("BACK", color = Color.Gray) }
            TextButton(onClick = { showVaultDeleteDialog = true }) { 
                Text("DELETE VAULT", color = Color.Red.copy(alpha = 0.7f), fontSize = 12.sp) 
            }
        }

        if (showVaultDeleteDialog) {
            var deletePhrase by remember { mutableStateOf("") }
            var deletePin by remember { mutableStateOf("") }
            var deleteError by remember { mutableStateOf<String?>(null) }

            AlertDialog(
                onDismissRequest = { showVaultDeleteDialog = false },
                title = { Text("SECURE VAULT DELETION", color = Color.Red, fontWeight = FontWeight.Black) },
                text = {
                    Column {
                        Text("This action is IRREVERSIBLE.", color = Color.Gray, fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        TextField(
                            value = deletePhrase,
                            onValueChange = { deletePhrase = it },
                            label = { Text("TYPE 'S R D' (WITH SPACES)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = deletePin,
                            onValueChange = { if (it.length <= 6) deletePin = it },
                            label = { Text("VAULT PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (deleteError != null) Text(deleteError!!, color = Color.Red, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        onClick = {
                            if (deletePhrase != "S R D") {
                                deleteError = "PHRASE MISMATCH"
                                return@Button
                            }
                            if (deletePin.isBlank()) {
                                deleteError = "ENTER VAULT PIN"
                                return@Button
                            }
                            if (securityManager.verifyPin(deletePin, vault.pinHash, vault.pinSalt)) {
                                viewModel.deleteVault(vault) { onBack() }
                            } else {
                                deleteError = "INVALID PIN"
                            }
                        }
                    ) { Text("DELETE") }
                },
                dismissButton = {
                    Row {
                        IconButton(onClick = { 
                            if (deletePhrase == "S R D") {
                                tryBio { viewModel.deleteVault(vault) { onBack() } }
                            } else {
                                deleteError = "PHRASE MISMATCH"
                            }
                        }) {
                            Icon(Icons.Default.Fingerprint, null)
                        }
                        TextButton(onClick = { showVaultDeleteDialog = false }) { Text("CANCEL") }
                    }
                }
            )
        }

        if (showBioError.value) {
            AlertDialog(onDismissRequest = { showBioError.value = false }, title = { Text("FINGERPRINT NOT CONFIGURED") }, text = { Text("Please add a fingerprint in system settings.") }, confirmButton = { TextButton(onClick = { showBioError.value = false }) { Text("OK") } })
        }
    } else {
        var selectedTagId by remember { mutableStateOf<String?>(null) }
        val itemFlow = remember(vault.id, selectedTagId) {
            if (selectedTagId.isNullOrBlank()) {
                viewModel.getItemsForVault(vault.id)
            } else {
                viewModel.getItemsForVaultByTag(vault.id, selectedTagId!!)
            }
        }
        val itemsState = itemFlow.collectAsState(initial = emptyList())
        val items = itemsState.value
        val folders by viewModel.getFoldersForVault(vault.id).collectAsState(initial = emptyList())
        val deleteOriginal by viewModel.deleteOriginalFiles.collectAsState()

        LaunchedEffect(vault.id, items) {
            val targetItemId = viewModel.consumePendingSearchOpenItemId(vault.id) ?: return@LaunchedEffect
            val target = items.firstOrNull { it.id == targetItemId } ?: return@LaunchedEffect
            when (target.type.uppercase(Locale.US)) {
                "IMAGE" -> viewingImage = target
                "NOTE" -> viewingNote = target
                "PASSWORD" -> viewingPassword = target
                "CONTACT" -> viewingContact = target
                else -> viewingFile = target
            }
        }

        val tabIndex = remember { mutableIntStateOf(0) }
        // Use readable labels but allow horizontal scrolling to avoid wrapping
        // Removed dedicated "FOLDERS" tab; folders are accessible within each content tab.
        val tabs = listOf("IMG", "NOTE", "PASS", "CONTACTS", "FILES")
        val currentType = when (tabs.getOrNull(tabIndex.intValue)) {
            "IMG" -> "IMAGE"
            "NOTE" -> "NOTE"
            "PASS" -> "PASSWORD"
            "CONTACTS" -> "CONTACT"
            "FILES" -> "FILE"
            else -> "NOTE"
        }
        val tags by viewModel.getTagsForVaultByType(vault.id, currentType).collectAsState(initial = emptyList())
        LaunchedEffect(currentType) {
            selectedTagId = null
        }
        val gray = Color(0xFF808080)

        Scaffold(
            topBar = {
                Column(modifier = Modifier.background(gray)) {
                    TopAppBar(
                        title = { Text(vault.name.uppercase(), color = Color.Black, fontWeight = FontWeight.Black) },
                        navigationIcon = { 
                            IconButton(onClick = {
                                if (selectedItems.isNotEmpty() || selectedFolderIds.isNotEmpty()) {
                                    selectedItems.clear()
                                    selectedFolderIds.clear()
                                } else {
                                    onBack()
                                }
                            }) {
                                Icon(
                                    if (selectedItems.isNotEmpty() || selectedFolderIds.isNotEmpty()) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                    null,
                                    tint = Color.Black
                                )
                            } 
                        },
                        actions = {
                            if (selectedItems.isNotEmpty()) {
                                IconButton(
                                    enabled = !isExporting,
                                    onClick = {
                                        if (isExporting) return@IconButton
                                        isExporting = true
                                        val toExport = selectedItems.toList()
                                        viewModel.viewModelScope.launch {
                                            val outcome = exportSelectedItemsToVault(context, toExport)
                                            exportResultMessage = outcome.message
                                            isExporting = false
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        null,
                                        tint = if (isExporting) Color.DarkGray else Color.Black
                                    )
                                }

                                // Group selected items into a folder for the active tab (IMG/NOTE/PASS/CONT/FILE)
                                IconButton(onClick = {
                                    val currentTab = tabs.getOrNull(tabIndex.intValue)
                                    val itemsToGroup = selectedItems.toList()
                                    if (itemsToGroup.isNotEmpty() && currentTab != null && currentTab != "FOLDERS") {
                                        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                                            .format(java.util.Date())
                                        val autoName = "GROUPED_$timestamp"

                                        val typeForFolder = when (currentTab) {
                                            "IMG" -> "IMAGE"
                                            "NOTE" -> "NOTE"
                                            "PASS" -> "PASSWORD"
                                            "CONTACTS" -> "CONTACT"
                                            "FILES" -> "FILE"
                                            else -> null
                                        }

                                        viewModel.createFolder(vault.id, autoName, "Grouped selection", typeForFolder) { newFolderId ->
                                            viewModel.viewModelScope.launch {
                                                itemsToGroup.forEach { item ->
                                                    viewModel.updateItem(item.copy(folderId = newFolderId.toInt()))
                                                }
                                                selectedItems.clear()
                                            }
                                        }
                                    }
                                }) {
                                    Icon(Icons.Default.Folder, null, tint = Color.Black)
                                }

                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(Icons.Default.Delete, null, tint = Color.Black)
                                }
                            } else if (selectedFolderIds.isNotEmpty()) {
                                IconButton(
                                    enabled = !isExporting,
                                    onClick = {
                                        if (isExporting) return@IconButton
                                        isExporting = true
                                        val selectedIds = selectedFolderIds.toSet()
                                        val foldersToExport = folders.filter { selectedIds.contains(it.id) }
                                        val itemsInFolders = items.filter { it.folderId != null && selectedIds.contains(it.folderId) }
                                        val currentTab = tabs.getOrNull(tabIndex.intValue).orEmpty()
                                        viewModel.viewModelScope.launch {
                                            val outcome = exportSelectedFoldersToVault(
                                                context = context,
                                                selectedFolders = foldersToExport,
                                                allItems = itemsInFolders,
                                                currentTab = currentTab
                                            )
                                            exportResultMessage = outcome.message
                                            isExporting = false
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Share,
                                        null,
                                        tint = if (isExporting) Color.DarkGray else Color.Black
                                    )
                                }
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(Icons.Default.Delete, null, tint = Color.Black)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )
                    ScrollableTabRow(
                        selectedTabIndex = tabIndex.intValue,
                        containerColor = Color.Transparent,
                        contentColor = Color.Black,
                        edgePadding = 0.dp
                    ) {
                        tabs.forEachIndexed { idx, title ->
                            Tab(
                                selected = tabIndex.intValue == idx,
                                onClick = {
                                    tabIndex.intValue = idx
                                    selectedItems.clear()
                                    selectedFolderIds.clear()
                                },
                                text = {
                                    Text(
                                        title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }
                    if (tags.isNotEmpty()) {
                        ScrollableTabRow(
                            selectedTabIndex = (tags.indexOfFirst { it.id == selectedTagId } + 1).coerceAtLeast(0),
                            containerColor = Color.Transparent,
                            contentColor = Color.Black,
                            edgePadding = 0.dp
                        ) {
                            Tab(
                                selected = selectedTagId == null,
                                onClick = { selectedTagId = null },
                                text = { Text("ALL TAGS", fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                            )
                            tags.forEach { tag ->
                                Tab(
                                    selected = selectedTagId == tag.id,
                                    onClick = {
                                        selectedTagId = tag.id
                                        selectedItems.clear()
                                        selectedFolderIds.clear()
                                    },
                                    text = { Text(tag.name.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showAddSheet.value = true }, containerColor = Color.Black, contentColor = Color.White) {
                    Icon(Icons.Default.Add, null)
                }
            },
            containerColor = gray
        ) { padding ->
            Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                val nonFolderItems = items.filter { it.folderId == null }

                when (tabs.getOrNull(tabIndex.intValue)) {
                    "IMG" -> {
                        val imageFolders = folders.filter { folder ->
                            items.any { it.folderId == folder.id && it.type == "IMAGE" }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (imageFolders.isNotEmpty()) {
                                FolderList(
                                    folders = imageFolders,
                                    viewModel = viewModel,
                                    selectedFolderIds = selectedFolderIds.toSet(),
                                    onFolderClick = { viewingFolder = it },
                                    onFolderLongClick = { folder ->
                                        if (selectedFolderIds.contains(folder.id)) {
                                            selectedFolderIds.remove(folder.id)
                                        } else {
                                            selectedFolderIds.add(folder.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
                            }
                            ImageGrid(
                                items = nonFolderItems.filter { it.type == "IMAGE" },
                                selected = selectedItems,
                                onClick = { viewingImage = it },
                                onLong = { if (selectedItems.contains(it)) selectedItems.remove(it) else selectedItems.add(it) }
                            )
                        }
                    }
                    "NOTE" -> {
                        val noteFolders = folders.filter { folder ->
                            items.any { it.folderId == folder.id && it.type == "NOTE" }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (noteFolders.isNotEmpty()) {
                                FolderList(
                                    folders = noteFolders,
                                    viewModel = viewModel,
                                    selectedFolderIds = selectedFolderIds.toSet(),
                                    onFolderClick = { viewingFolder = it },
                                    onFolderLongClick = { folder ->
                                        if (selectedFolderIds.contains(folder.id)) {
                                            selectedFolderIds.remove(folder.id)
                                        } else {
                                            selectedFolderIds.add(folder.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
                            }
                            NoteList(
                                items = nonFolderItems.filter { it.type == "NOTE" },
                                selected = selectedItems,
                                onClick = { viewingNote = it },
                                onLong = { if (selectedItems.contains(it)) selectedItems.remove(it) else selectedItems.add(it) }
                            )
                        }
                    }
                    "PASS" -> {
                        val passFolders = folders.filter { folder ->
                            items.any { it.folderId == folder.id && it.type == "PASSWORD" }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (passFolders.isNotEmpty()) {
                                FolderList(
                                    folders = passFolders,
                                    viewModel = viewModel,
                                    selectedFolderIds = selectedFolderIds.toSet(),
                                    onFolderClick = { viewingFolder = it },
                                    onFolderLongClick = { folder ->
                                        if (selectedFolderIds.contains(folder.id)) {
                                            selectedFolderIds.remove(folder.id)
                                        } else {
                                            selectedFolderIds.add(folder.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
                            }
                            PasswordList(
                                items = nonFolderItems.filter { it.type == "PASSWORD" },
                                selected = selectedItems,
                                onClick = { viewingPassword = it },
                                onLong = { if (selectedItems.contains(it)) selectedItems.remove(it) else selectedItems.add(it) }
                            )
                        }
                    }
                    "CONTACTS" -> {
                        val contactFolders = folders.filter { folder ->
                            items.any { it.folderId == folder.id && it.type == "CONTACT" }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (contactFolders.isNotEmpty()) {
                                FolderList(
                                    folders = contactFolders,
                                    viewModel = viewModel,
                                    selectedFolderIds = selectedFolderIds.toSet(),
                                    onFolderClick = { viewingFolder = it },
                                    onFolderLongClick = { folder ->
                                        if (selectedFolderIds.contains(folder.id)) {
                                            selectedFolderIds.remove(folder.id)
                                        } else {
                                            selectedFolderIds.add(folder.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
                            }
                            ContactList(
                                items = nonFolderItems.filter { it.type == "CONTACT" },
                                selected = selectedItems,
                                onClick = { viewingContact = it },
                                onLong = { if (selectedItems.contains(it)) selectedItems.remove(it) else selectedItems.add(it) }
                            )
                        }
                    }
                    "FILES" -> {
                        val fileFolders = folders.filter { folder ->
                            items.any { it.folderId == folder.id && it.type == "FILE" }
                        }
                        Column(Modifier.fillMaxSize()) {
                            if (fileFolders.isNotEmpty()) {
                                FolderList(
                                    folders = fileFolders,
                                    viewModel = viewModel,
                                    selectedFolderIds = selectedFolderIds.toSet(),
                                    onFolderClick = { viewingFolder = it },
                                    onFolderLongClick = { folder ->
                                        if (selectedFolderIds.contains(folder.id)) {
                                            selectedFolderIds.remove(folder.id)
                                        } else {
                                            selectedFolderIds.add(folder.id)
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                )
                                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
                            }
                            FileList(
                                items = nonFolderItems.filter { it.type == "FILE" },
                                selected = selectedItems,
                                onClick = { viewingFile = it },
                                onLong = { if (selectedItems.contains(it)) selectedItems.remove(it) else selectedItems.add(it) }
                            )
                        }
                    }
                }
            }
        }

        if (showAddSheet.value) {
            val folders by viewModel.getFoldersForVault(vault.id).collectAsState(initial = emptyList())
            val currentTab = tabs.getOrNull(tabIndex.intValue)
            val foldersForDialog = remember(currentTab, items, folders) {
                when (currentTab) {
                    "IMG" -> folders.filter { folder -> items.any { it.folderId == folder.id && it.type == "IMAGE" } }
                    "NOTE" -> folders.filter { folder -> items.any { it.folderId == folder.id && it.type == "NOTE" } }
                    "PASS" -> folders.filter { folder -> items.any { it.folderId == folder.id && it.type == "PASSWORD" } }
                    "CONTACTS" -> folders.filter { folder -> items.any { it.folderId == folder.id && it.type == "CONTACT" } }
                    "FILES" -> folders.filter { folder -> items.any { it.folderId == folder.id && it.type == "FILE" } }
                    else -> emptyList()
                }
            }
            AddItemDialog(
                folders = foldersForDialog,
                deleteOriginal = deleteOriginal,
                onDismiss = { showAddSheet.value = false },
                onAdd = { type, name, desc, content, user, cat, link, logoUri, ext, email, phone, fId, tagNames, callback ->
                    viewModel.addItem(vault.id, type, name, desc, content, user, cat, link, logoUri, ext, email, phone, fId, tagNames) { err ->
                        callback(err)
                        if (err == null) showAddSheet.value = false
                    }
                },
                onAddFromUri = { type, name, desc, uri, ext, isImage, fId, tagNames, onProgress, callback ->
                    viewModel.addItemFromUri(
                        vaultId = vault.id,
                        type = type,
                        name = name,
                        description = desc,
                        uri = uri,
                        extension = ext,
                        isImage = isImage,
                        folderId = fId,
                        deleteOriginal = deleteOriginal,
                        tagNames = tagNames,
                        onProgress = onProgress
                    ) { err ->
                        callback(err)
                        if (err == null) showAddSheet.value = false
                    }
                },
                onBatchAdd = { type, batchItems, desc, fId, tagNames, onProgress, callback ->
                    if (fId == null) {
                        // Let repository auto-name folder as F1, F2, ... per content type
                        viewModel.createFolder(vault.id, "", "Grouped selection", type) { newFolderId ->
                            viewModel.addItemsFromUris(
                                vaultId = vault.id,
                                type = type,
                                items = batchItems,
                                description = desc,
                                folderId = newFolderId.toInt(),
                                deleteOriginal = deleteOriginal,
                                tagNames = tagNames,
                                onProgress = onProgress
                            ) { err ->
                                callback(err)
                                if (err == null) showAddSheet.value = false
                            }
                        }
                    } else {
                        viewModel.addItemsFromUris(
                            vaultId = vault.id,
                            type = type,
                            items = batchItems,
                            description = desc,
                            folderId = fId,
                            deleteOriginal = deleteOriginal,
                            tagNames = tagNames,
                            onProgress = onProgress
                        ) { err ->
                            callback(err)
                            if (err == null) showAddSheet.value = false
                        }
                    }
                },
                onExternalActivityStart = { viewModel.beginExternalActivity() },
                onExternalActivityEnd = { viewModel.endExternalActivity() }
            )
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = {
                    val folderCount = selectedFolderIds.size
                    val itemCount = selectedItems.size
                    Text("DELETE ${itemCount + folderCount} SELECTED?", fontWeight = FontWeight.Bold)
                },
                text = { Text("Selected items and selected folders will be deleted.", fontSize = 12.sp) },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        enabled = !isDeletingSelection,
                        onClick = {
                            if (isDeletingSelection) return@Button
                            isDeletingSelection = true
                            deleteSelectionError = null
                            val folderIds = selectedFolderIds.toSet()
                            val selectedFolders = folders.filter { folderIds.contains(it.id) }
                            viewModel.deleteSelection(
                                selectedItems = selectedItems.toList(),
                                selectedFolders = selectedFolders,
                                allItems = items
                            ) { err ->
                                isDeletingSelection = false
                                if (err == null) {
                                    selectedItems.clear()
                                    selectedFolderIds.clear()
                                    showDeleteConfirm = false
                                } else {
                                    deleteSelectionError = err
                                }
                            }
                        }
                    ) { Text(if (isDeletingSelection) "DELETING..." else "DELETE") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("CANCEL") }
                }
            )
        }
        if (deleteSelectionError != null) {
            AlertDialog(
                onDismissRequest = { deleteSelectionError = null },
                title = { Text("DELETE FAILED") },
                text = { Text(deleteSelectionError ?: "Unknown error", fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { deleteSelectionError = null }) { Text("OK") } }
            )
        }
        if (exportResultMessage != null) {
            AlertDialog(
                onDismissRequest = { exportResultMessage = null },
                title = { Text("EXPORT") },
                text = { Text(exportResultMessage ?: "", fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { exportResultMessage = null }) { Text("OK") } }
            )
        }

        if (viewingImage != null) ImageViewer(viewingImage!!, viewModel) { viewingImage = null }
        if (viewingNote != null) NoteDetail(viewingNote!!, viewModel) { viewingNote = null }
        if (viewingPassword != null) PasswordDetail(viewingPassword!!, viewModel) { viewingPassword = null }
        if (viewingFile != null) FileViewer(viewingFile!!, viewModel) { viewingFile = null }
        if (viewingContact != null) ContactDetail(viewingContact!!, viewModel) { viewingContact = null }
        if (viewingFolder != null) FolderContentView(viewingFolder!!, viewModel, securityManager) { viewingFolder = null }
    }
}

@Composable
fun ImageViewer(item: VaultItem, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val noClipboardEnabled = viewModel.repository.securityManager.isNoClipboardModeEnabled()
    val securityManager = viewModel.repository.securityManager
    var edit by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf(item.name) }
    var d by remember { mutableStateOf(item.description ?: "") }
    var tagInput by remember { mutableStateOf("") }
    LaunchedEffect(edit) {
        if (edit) {
            tagInput = viewModel.getTagNamesForItem(item.id).joinToString(", ")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val bmp by rememberBitmapFromFile(item.filePath, reqWidth = 1600, reqHeight = 1600)
                bmp?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Spacer(Modifier.height(16.dp))
                if (edit) {
                    TextField(n, { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    TextField(d, { d = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    TextField(tagInput, { tagInput = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name.uppercase(), fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                        IconButton(
                            enabled = !noClipboardEnabled,
                            onClick = {
                                copyToClipboardWithPolicy(context, securityManager, "vault_image_name", item.name)
                            }
                        ) {
                            Icon(if (noClipboardEnabled) Icons.Default.Lock else Icons.Default.ContentCopy, null)
                        }
                    }
                    if (!item.description.isNullOrBlank()) Text(item.description!!, fontSize = 12.sp, color = Color.Gray)
                }
            }
        },
        confirmButton = {
            if (edit) {
                Button(onClick = {
                    viewModel.updateItem(item.copy(name = n, description = d))
                    viewModel.viewModelScope.launch {
                        viewModel.assignItemTagNames(item.vaultId, item.id, parseTagInput(tagInput))
                    }
                    edit = false
                }) { Text("SAVE") }
            } else {
                Button(enabled = !noClipboardEnabled, onClick = { edit = true }) { Text("EDIT") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
fun FileViewer(item: VaultItem, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val noClipboardEnabled = viewModel.repository.securityManager.isNoClipboardModeEnabled()
    val securityManager = viewModel.repository.securityManager
    var edit by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf(item.name) }
    var d by remember { mutableStateOf(item.description ?: "") }
    var tagInput by remember { mutableStateOf("") }
    LaunchedEffect(edit) {
        if (edit) {
            tagInput = viewModel.getTagNamesForItem(item.id).joinToString(", ")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.uppercase(), fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                IconButton(
                    enabled = !noClipboardEnabled,
                    onClick = {
                        copyToClipboardWithPolicy(context, securityManager, "vault_file_name", item.name)
                    }
                ) {
                    Icon(if (noClipboardEnabled) Icons.Default.Lock else Icons.Default.ContentCopy, null)
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                if (edit) {
                    TextField(n, { n = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    TextField(d, { d = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    TextField(tagInput, { tagInput = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                    return@Column
                }

                val ext = item.extension?.lowercase() ?: ""
                val file = item.filePath?.let { File(it) }
                
                if (file != null && file.exists()) {
                    val textFormats = remember { listOf("txt", "md", "json", "xml", "kt", "java", "log", "sh", "py", "js", "ts", "css", "html", "c", "cpp", "h") }
                    if (ext in textFormats) {
                        val previewState by produceState<TextPreviewState>(initialValue = TextPreviewState.Loading, key1 = file.absolutePath) {
                            value = withContext(Dispatchers.IO) {
                                if (!file.exists()) {
                                    TextPreviewState.Error("File not found or inaccessible.")
                                } else {
                                    runCatching { file.readText() }
                                        .fold(TextPreviewState::Ready) { TextPreviewState.Error("Error reading file content.") }
                                }
                            }
                        }
                        when (val state = previewState) {
                            TextPreviewState.Loading -> Text("Loading preview...", color = Color.Gray)
                            is TextPreviewState.Ready -> Text(state.text, fontSize = 14.sp, color = Color.Black)
                            is TextPreviewState.Error -> Text(state.message, color = Color.Red)
                        }
                    } else if (ext == "pdf") {
                        PdfViewer(file)
                    } else {
                        Text("Preview not available for .$ext files inside the vault.\nSupported: ${textFormats.joinToString(", ")} and .pdf", color = Color.Gray)
                    }
                } else {
                    Text("File not found or inaccessible.", color = Color.Red)
                }
                
                if (d.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text("DESCRIPTION:", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text(d, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (edit) {
                Button(onClick = {
                    viewModel.updateItem(item.copy(name = n, description = d))
                    viewModel.viewModelScope.launch {
                        viewModel.assignItemTagNames(item.vaultId, item.id, parseTagInput(tagInput))
                    }
                    edit = false
                }) { Text("SAVE") }
            } else {
                Button(enabled = !noClipboardEnabled, onClick = { edit = true }) { Text("EDIT") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
fun PdfViewer(file: File) {
    val renderState by produceState<PdfRenderState>(initialValue = PdfRenderState.Loading, key1 = file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { renderPdfPages(file) }
                .fold(
                    onSuccess = { pages ->
                        if (pages.isEmpty()) PdfRenderState.Error("Could not render PDF.")
                        else PdfRenderState.Ready(pages)
                    },
                    onFailure = { PdfRenderState.Error("Could not render PDF.") }
                )
        }
    }

    when (val state = renderState) {
        PdfRenderState.Loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Rendering PDF...", color = Color.Gray, fontSize = 12.sp)
            }
        }
        is PdfRenderState.Error -> Text(state.message, color = Color.Red)
        is PdfRenderState.Ready -> {
            Column {
                state.pages.forEach { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}

private sealed class PdfRenderState {
    data object Loading : PdfRenderState()
    data class Ready(val pages: List<Bitmap>) : PdfRenderState()
    data class Error(val message: String) : PdfRenderState()
}

private fun renderPdfPages(file: File): List<Bitmap> {
    val maxWidth = 1400
    val list = mutableListOf<Bitmap>()
    val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = PdfRenderer(fd)
    for (i in 0 until renderer.pageCount) {
        val page = renderer.openPage(i)
        val scale = if (page.width > maxWidth) maxWidth.toFloat() / page.width.toFloat() else 1f
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val matrix = Matrix().apply { postScale(scale, scale) }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        list.add(bitmap)
        page.close()
    }
    renderer.close()
    fd.close()
    return list
}

private sealed class TextPreviewState {
    data object Loading : TextPreviewState()
    data class Ready(val text: String) : TextPreviewState()
    data class Error(val message: String) : TextPreviewState()
}

@Composable
fun NoteDetail(item: VaultItem, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf(item.name) }
    var d by remember { mutableStateOf(item.description ?: "") }
    var c by remember { mutableStateOf(item.content ?: "") }
    var tagInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val noClipboardEnabled = viewModel.repository.securityManager.isNoClipboardModeEnabled()
    val securityManager = viewModel.repository.securityManager
    LaunchedEffect(edit) {
        if (edit) {
            tagInput = viewModel.getTagNamesForItem(item.id).joinToString(", ")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit) "EDIT NOTE" else "VIEW NOTE") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (edit) {
                    TextField(n, { n = it }, label = { Text("Name") })
                    TextField(d, { d = it }, label = { Text("Description") })
                    TextField(c, { c = it }, label = { Text("Content") }, modifier = Modifier.heightIn(min = 200.dp))
                    TextField(tagInput, { tagInput = it }, label = { Text("Tags (comma separated)") })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.uppercase(), fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { preview = !preview }) {
                            Text(if (preview) "PLAIN" else "PREVIEW", fontSize = 10.sp)
                        }
                        IconButton(
                            enabled = !noClipboardEnabled,
                            onClick = {
                                copyToClipboardWithPolicy(context, securityManager, "vault_note", "$n\n\n$c")
                            }
                        ) {
                            Icon(if (noClipboardEnabled) Icons.Default.Lock else Icons.Default.ContentCopy, null)
                        }
                    }
                    if (d.isNotBlank()) Text(d, fontSize = 12.sp)
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    if (preview) {
                        MarkdownPreview(text = c)
                    } else {
                        Text(c)
                    }
                }
            }
        },
        confirmButton = {
            if (edit) {
                Button(onClick = {
                    viewModel.updateItem(item.copy(name = n, content = c, description = d))
                    viewModel.viewModelScope.launch {
                        viewModel.assignItemTagNames(item.vaultId, item.id, parseTagInput(tagInput))
                    }
                    edit = false
                }) { Text("SAVE") }
            } else {
                Button(enabled = !noClipboardEnabled, onClick = { edit = true }) { Text("EDIT") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

@Composable
fun PasswordDetail(item: VaultItem, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var hidden by remember { mutableStateOf(true) }
    var n by remember { mutableStateOf(item.name) }
    var u by remember { mutableStateOf(item.username ?: "") }
    var p by remember { mutableStateOf(item.content ?: "") }
    var d by remember { mutableStateOf(item.description ?: "") }
    var cat by remember { mutableStateOf(item.passCategory ?: "OTHER") }
    var li by remember { mutableStateOf(item.link ?: "") }
    var tagInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val securityManager = viewModel.repository.securityManager
    val noClipboardEnabled = viewModel.repository.securityManager.isNoClipboardModeEnabled()
    LaunchedEffect(edit) {
        if (edit) {
            tagInput = viewModel.getTagNamesForItem(item.id).joinToString(", ")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit) "EDIT PASSWORD" else "VIEW PASSWORD") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (edit) {
                    TextField(n, { n = it }, label = { Text("Name") })
                    TextField(u, { u = it }, label = { Text("User") })
                    TextField(p, { p = it }, label = { Text("Pass") })
                    TextField(d, { d = it }, label = { Text("Desc") })
                    TextField(tagInput, { tagInput = it }, label = { Text("Tags (comma separated)") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(cat == "APP", { cat = "APP" }); Text("App")
                        RadioButton(cat == "WEBSITE", { cat = "WEBSITE" }); Text("Web")
                        RadioButton(cat == "OTHER", { cat = "OTHER" }); Text("Other")
                    }
                    if (cat == "WEBSITE") TextField(li, { li = it }, label = { Text("Link") })
                } else {
                    Text(n.uppercase(), fontWeight = FontWeight.Black, fontSize = 20.sp)
                    Text("User: $u", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Pass: ${if (hidden) "••••••••" else p}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { hidden = !hidden }) {
                            Icon(if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)
                        }
                        IconButton(
                            enabled = !noClipboardEnabled,
                            onClick = {
                                if (noClipboardEnabled) return@IconButton
                                copyToClipboardWithPolicy(context, securityManager, "vault_password", p)
                            }
                        ) {
                            Icon(
                                if (noClipboardEnabled) Icons.Default.Lock else Icons.Default.ContentCopy,
                                null
                            )
                        }
                    }
                    Text("Type: $cat", fontSize = 12.sp, color = Color.Gray)
                    if (cat == "WEBSITE" && li.isNotBlank()) Text("Link: $li", color = Color.Blue, fontSize = 12.sp)
                    if (d.isNotBlank()) Text(d, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            if (edit) {
                Button(onClick = {
                    viewModel.updateItem(item.copy(name = n, username = u, content = p, description = d, passCategory = cat, link = li))
                    viewModel.viewModelScope.launch {
                        viewModel.assignItemTagNames(item.vaultId, item.id, parseTagInput(tagInput))
                    }
                    edit = false
                }) { Text("SAVE") }
            } else {
                Button(enabled = !noClipboardEnabled, onClick = { edit = true }) { Text("EDIT") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGrid(items: List<VaultItem>, selected: List<VaultItem>, onClick: (VaultItem) -> Unit, onLong: (VaultItem) -> Unit) {
    LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().padding(4.dp)) {
        items(items, key = { it.id }) { item ->
            val b by rememberBitmapFromFile(item.filePath, reqWidth = 300, reqHeight = 300)
            val sel = selected.contains(item)
            Box(modifier = Modifier.aspectRatio(1f).padding(2.dp).background(if (sel) Color.White.copy(0.5f) else Color.Transparent).combinedClickable(onClick = { if (selected.isNotEmpty()) onLong(item) else onClick(item) }, onLongClick = { onLong(item) })) { b?.let { Image(bitmap = it.asImageBitmap(), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }; if (sel) Icon(Icons.Default.CheckCircle, null, tint = Color.Black, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteList(items: List<VaultItem>, selected: List<VaultItem>, onClick: (VaultItem) -> Unit, onLong: (VaultItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val sel = selected.contains(item)
            ListItem(headlineContent = { Text(item.name, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.Black) }, supportingContent = { if (!item.description.isNullOrBlank()) Text(item.description!!, fontSize = 12.sp) }, leadingContent = { Icon(Icons.AutoMirrored.Filled.Note, null, tint = if (sel) Color.White else Color.Black) }, modifier = Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) onLong(item) else onClick(item) }, onLongClick = { onLong(item) }), colors = ListItemDefaults.colors(containerColor = if (sel) Color.Black else Color.Transparent))
            HorizontalDivider(color = Color.Black.copy(0.1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PasswordList(items: List<VaultItem>, selected: List<VaultItem>, onClick: (VaultItem) -> Unit, onLong: (VaultItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val sel = selected.contains(item)
            ListItem(headlineContent = { Text(item.name, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.Black) }, supportingContent = { if (!item.username.isNullOrBlank()) Text(item.username!!) }, leadingContent = { Icon(Icons.Default.Password, null, tint = if (sel) Color.White else Color.Black) }, modifier = Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) onLong(item) else onClick(item) }, onLongClick = { onLong(item) }), colors = ListItemDefaults.colors(containerColor = if (sel) Color.Black else Color.Transparent))
            HorizontalDivider(color = Color.Black.copy(0.1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileList(items: List<VaultItem>, selected: List<VaultItem>, onClick: (VaultItem) -> Unit, onLong: (VaultItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val sel = selected.contains(item)
            ListItem(headlineContent = { Text(item.name, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.Black) }, supportingContent = { Text("${item.extension?.uppercase()} FILE") }, leadingContent = { Icon(Icons.Default.Description, null, tint = if (sel) Color.White else Color.Black) }, modifier = Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) onLong(item) else onClick(item) }, onLongClick = { onLong(item) }), colors = ListItemDefaults.colors(containerColor = if (sel) Color.Black else Color.Transparent))
            HorizontalDivider(color = Color.Black.copy(0.1f))
        }
    }
}

@Composable
fun AddItemDialog(
    folders: List<VaultFolder>,
    deleteOriginal: Boolean,
    onDismiss: () -> Unit, 
    onAdd: (String, String, String?, String?, String?, String?, String?, Uri?, String?, String?, String?, Int?, List<String>, (String?) -> Unit) -> Unit,
    onAddFromUri: (String, String, String?, Uri, String?, Boolean, Int?, List<String>, (Long) -> Unit, (String?) -> Unit) -> Unit,
    onBatchAdd: (String, List<PendingImportFile>, String?, Int?, List<String>, (Int, Int) -> Unit, (String?) -> Unit) -> Unit,
    onExternalActivityStart: () -> Unit,
    onExternalActivityEnd: () -> Unit
) {
    val t = remember { mutableStateOf("NOTE") }
    val n = remember { mutableStateOf("") }
    val d = remember { mutableStateOf("") }
    val co = remember { mutableStateOf("") }
    val u = remember { mutableStateOf("") }
    val cat = remember { mutableStateOf("APP") }
    val li = remember { mutableStateOf("") }
    val logoUri = remember { mutableStateOf<Uri?>(null) }
    val ex = remember { mutableStateOf<String?>(null) }
    val em = remember { mutableStateOf("") }
    val ph = remember { mutableStateOf("") }
    val tagInput = remember { mutableStateOf("") }
    val selectedFolderId = remember { mutableStateOf<Int?>(null) }
    var generatorLength by remember { mutableIntStateOf(20) }
    var generatorUpper by remember { mutableStateOf(true) }
    var generatorLower by remember { mutableStateOf(true) }
    var generatorDigits by remember { mutableStateOf(true) }
    var generatorSymbols by remember { mutableStateOf(true) }
    var excludeAmbiguous by remember { mutableStateOf(true) }
    var addError by remember { mutableStateOf<String?>(null) }
    var addStatus by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    
    // Multiple selection states
    val batchItems = remember { mutableStateListOf<PendingImportFile>() }
    var singleSelectedUri by remember { mutableStateOf<Uri?>(null) }
    
    val context = LocalContext.current
    val securityManager = remember { GlobalContext.get().get<SecurityManager>() }

    fun formatBytes(bytes: Long): String {
        return android.text.format.Formatter.formatShortFileSize(context, bytes)
    }
    
    val singleFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        try {
            uri?.let {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // best-effort
                }
                // Single-select should replace any previous multi-select state.
                batchItems.clear()
                singleSelectedUri = it
                addError = null
                var name: String? = null
                context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) name = cursor.getString(nameIndex)
                }
                ex.value = name?.substringAfterLast('.', "")?.lowercase()
                    ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(it))?.lowercase()
                if (n.value.isEmpty()) n.value = name ?: "Unnamed File"
            }
        } finally {
            onExternalActivityEnd()
        }
    }

    val multiFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        try {
            addError = null
            // Multi-select should replace single-select state and previous batch picks.
            singleSelectedUri = null
            ex.value = null
            batchItems.clear()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // best-effort
                }
                val mime = context.contentResolver.getType(uri)
                val isImage = mime != null && mime.startsWith("image/")
                var name: String? = null
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) name = cursor.getString(nameIndex)
                }
                val ext = name?.substringAfterLast('.', "")?.lowercase()
                    ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))?.lowercase()
                batchItems.add(
                    PendingImportFile(
                        uri = uri,
                        name = name ?: "File ${batchItems.size + 1}",
                        extension = ext,
                        isImage = isImage
                    )
                )
            }
        } finally {
            onExternalActivityEnd()
        }
    }

    val logoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        try {
            uri?.let {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        it,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // best-effort
                }
                logoUri.value = it
            }
        } finally {
            onExternalActivityEnd()
        }
    }

    fun launchSinglePicker(mime: String) {
        onExternalActivityStart()
        singleFileLauncher.launch(arrayOf(mime))
    }

    fun launchMultiPicker(mime: String) {
        onExternalActivityStart()
        multiFileLauncher.launch(arrayOf(mime))
    }

    fun launchLogoPicker() {
        onExternalActivityStart()
        logoLauncher.launch("image/*")
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ADD TO VAULT", fontWeight = FontWeight.Black) },
        containerColor = Color(0xFF202020),
        text = {
            LazyColumn {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        // Type chips (NOTE / PASS / CONTACT)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("NOTE", "PASS", "CONTACT").forEach { type ->
                                FilterChip(
                                    selected = t.value == type,
                                    onClick = { t.value = type },
                                    label = { Text(type, fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color(0xFF303030),
                                        selectedContainerColor = Color.White,
                                        labelColor = Color.White,
                                        selectedLabelColor = Color.Black
                                    )
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // IMAGE / FILE chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf("IMAGE", "FILE").forEach { type ->
                                FilterChip(
                                    selected = t.value == type,
                                    onClick = {
                                        t.value = type
                                        if (type == "FILE") {
                                            launchSinglePicker("*/*")
                                        }
                                    },
                                    label = { Text(type, fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = Color(0xFF303030),
                                        selectedContainerColor = Color.White,
                                        labelColor = Color.White,
                                        selectedLabelColor = Color.Black
                                    )
                                )
                            }
                        }

                        // Folder type creation via dialog removed; folders are created automatically for multi-select.
                        if (t.value == "IMAGE" || t.value == "FILE") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        launchSinglePicker(if (t.value == "IMAGE") "image/*" else "*/*")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (singleSelectedUri == null) "PICK ONE" else "REPICK")
                                }
                                Button(
                                    onClick = {
                                        launchMultiPicker(if (t.value == "IMAGE") "image/*" else "*/*")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("PICK MULTIPLE (${batchItems.size})")
                                }
                            }
                            if (batchItems.isNotEmpty()) {
                                val preview = batchItems.take(3).joinToString { it.name }
                                Text(
                                    "Selected ${batchItems.size} files${if (preview.isNotBlank()) ": $preview" else ""}${if (batchItems.size > 3) "..." else ""}",
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    color = Color.Gray
                                )
                            } else if (singleSelectedUri != null) {
                                Text(
                                    "Selected file: ${n.value.ifBlank { "Unnamed File" }}",
                                    fontSize = 10.sp,
                                    maxLines = 2,
                                    color = Color.Gray
                                )
                            }
                        }

                        if (batchItems.isEmpty()) {
                            TextField(
                                n.value,
                                { n.value = it; addError = null },
                                label = { Text("Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        TextField(
                            d.value,
                            { d.value = it },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            tagInput.value,
                            { tagInput.value = it },
                            label = { Text("Tags (comma separated)") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (t.value == "PASS") {
                            TextField(
                                u.value,
                                { u.value = it },
                                label = { Text("Username") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                co.value,
                                { co.value = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    autoCorrectEnabled = false
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            val strength = remember(co.value) { PasswordTools.evaluate(co.value) }
                            Text(
                                "Strength: ${strength.label.name.replace('_', ' ')}",
                                color = when (strength.label) {
                                    PasswordStrengthLabel.WEAK -> Color.Red
                                    PasswordStrengthLabel.FAIR -> Color(0xFFFFC107)
                                    PasswordStrengthLabel.STRONG -> Color(0xFF8BC34A)
                                    PasswordStrengthLabel.VERY_STRONG -> Color(0xFF4CAF50)
                                },
                                fontSize = 11.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("PASSWORD GENERATOR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text("Length: $generatorLength", color = Color.Gray, fontSize = 10.sp)
                            Slider(
                                value = generatorLength.toFloat(),
                                onValueChange = { generatorLength = it.toInt().coerceIn(8, 64) },
                                valueRange = 8f..64f
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = generatorUpper, onClick = { generatorUpper = !generatorUpper }, label = { Text("A-Z", fontSize = 9.sp) })
                                FilterChip(selected = generatorLower, onClick = { generatorLower = !generatorLower }, label = { Text("a-z", fontSize = 9.sp) })
                                FilterChip(selected = generatorDigits, onClick = { generatorDigits = !generatorDigits }, label = { Text("0-9", fontSize = 9.sp) })
                                FilterChip(selected = generatorSymbols, onClick = { generatorSymbols = !generatorSymbols }, label = { Text("#*", fontSize = 9.sp) })
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = excludeAmbiguous, onCheckedChange = { excludeAmbiguous = it })
                                Text("Exclude ambiguous chars", fontSize = 10.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        co.value = PasswordTools.generate(
                                            length = generatorLength,
                                            includeUppercase = generatorUpper,
                                            includeLowercase = generatorLower,
                                            includeDigits = generatorDigits,
                                            includeSymbols = generatorSymbols,
                                            excludeAmbiguous = excludeAmbiguous
                                        )
                                    }
                                ) {
                                    Text("GENERATE", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        if (co.value.isNotBlank()) {
                                            copyToClipboardWithPolicy(context, securityManager, "generated_password", co.value)
                                        }
                                    }
                                ) {
                                    Text("COPY", fontSize = 10.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(cat.value == "APP", { cat.value = "APP" }); Text("App")
                                RadioButton(cat.value == "WEBSITE", { cat.value = "WEBSITE" }); Text("Web")
                                RadioButton(cat.value == "OTHER", { cat.value = "OTHER" }); Text("Other")
                            }
                            if (cat.value == "APP") {
                                Button(onClick = { launchLogoPicker() }) {
                                    Text(if (logoUri.value == null) "SELECT LOGO" else "LOGO SELECTED")
                                }
                            } else if (cat.value == "WEBSITE") {
                                TextField(
                                    li.value,
                                    { li.value = it },
                                    label = { Text("Link") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else if (t.value == "CONTACT") {
                            TextField(
                                ph.value,
                                { ph.value = it },
                                label = { Text("Phone Number") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextField(
                                em.value,
                                { em.value = it },
                                label = { Text("Email") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (t.value == "NOTE") {
                            TextField(
                                co.value,
                                { co.value = it },
                                label = { Text("Content") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp)
                            )
                        }

                        // Folder Selection
                        Spacer(Modifier.height(16.dp))
                        if (addError != null) {
                            Text(addError!!, color = Color.Red, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (addStatus != null) {
                            Text(addStatus!!, color = Color.Gray, fontSize = 11.sp)
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            "ADD TO FOLDER:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                        ScrollableTabRow(
                            selectedTabIndex = folders.indexOfFirst { it.id == selectedFolderId.value } + 1,
                            edgePadding = 0.dp,
                            containerColor = Color.Transparent
                        ) {
                            Tab(
                                selected = selectedFolderId.value == null,
                                onClick = { selectedFolderId.value = null },
                                text = { Text("NONE", fontSize = 10.sp) }
                            )
                            folders.forEach { folder ->
                                Tab(
                                    selected = selectedFolderId.value == folder.id,
                                    onClick = { selectedFolderId.value = folder.id },
                                    text = { Text(folder.name.uppercase(), fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isAdding,
                onClick = {
                if (isAdding) return@Button
                isAdding = true
                addStatus = null
                val parsedTagNames = tagInput.value
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                if (batchItems.isNotEmpty()) {
                    onBatchAdd(
                        t.value,
                        batchItems.toList(),
                        d.value,
                        selectedFolderId.value,
                        parsedTagNames,
                        { index, total ->
                            addStatus = "Adding file $index of $total..."
                        }
                    ) { err ->
                        addError = err
                        if (err != null) {
                            isAdding = false
                        } else {
                            addStatus = null
                        }
                    }
                } else if ((t.value == "IMAGE" || t.value == "FILE") && singleSelectedUri != null) {
                    addStatus = "Preparing file..."
                    onAddFromUri(
                        t.value,
                        n.value.ifBlank { "Unnamed File" },
                        d.value,
                        singleSelectedUri!!,
                        ex.value,
                        t.value == "IMAGE",
                        selectedFolderId.value,
                        parsedTagNames,
                        { bytes ->
                            addStatus = "Adding file: ${formatBytes(bytes)}"
                        }
                    ) { err ->
                        addError = err
                        if (err != null) {
                            isAdding = false
                        } else {
                            addStatus = null
                        }
                    }
                } else {
                    if (t.value == "IMAGE" || t.value == "FILE") {
                        addError = "Pick a file first"
                        isAdding = false
                        return@Button
                    }
                    onAdd(
                        if (t.value == "PASS") "PASSWORD" else t.value,
                        n.value,
                        d.value,
                        co.value,
                        u.value,
                        cat.value,
                        li.value,
                        logoUri.value,
                        ex.value,
                        em.value,
                        ph.value,
                        selectedFolderId.value,
                        parsedTagNames
                    ) { err ->
                        addError = err
                        if (err != null) isAdding = false
                    }
                }
            }) {
                Text(if (isAdding) "ADDING..." else "ADD")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactList(items: List<VaultItem>, selected: List<VaultItem>, onClick: (VaultItem) -> Unit, onLong: (VaultItem) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val sel = selected.contains(item)
            ListItem(
                headlineContent = { Text(item.name, fontWeight = FontWeight.Bold, color = if (sel) Color.White else Color.Black) },
                supportingContent = { 
                    Column {
                        if (!item.phoneNumber.isNullOrBlank()) Text(item.phoneNumber!!, fontSize = 12.sp)
                        if (!item.email.isNullOrBlank()) Text(item.email!!, fontSize = 12.sp)
                    }
                },
                leadingContent = { Icon(Icons.Default.Person, null, tint = if (sel) Color.White else Color.Black) },
                modifier = Modifier.combinedClickable(onClick = { if (selected.isNotEmpty()) onLong(item) else onClick(item) }, onLongClick = { onLong(item) }),
                colors = ListItemDefaults.colors(containerColor = if (sel) Color.Black else Color.Transparent)
            )
            HorizontalDivider(color = Color.Black.copy(0.1f))
        }
    }
}

@Composable
fun ContactDetail(item: VaultItem, viewModel: VaultViewModel, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf(false) }
    var n by remember { mutableStateOf(item.name) }
    var d by remember { mutableStateOf(item.description ?: "") }
    var ph by remember { mutableStateOf(item.phoneNumber ?: "") }
    var em by remember { mutableStateOf(item.email ?: "") }
    var tagInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val securityManager = viewModel.repository.securityManager
    val noClipboardEnabled = viewModel.repository.securityManager.isNoClipboardModeEnabled()
    LaunchedEffect(edit) {
        if (edit) {
            tagInput = viewModel.getTagNamesForItem(item.id).joinToString(", ")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (edit) "EDIT CONTACT" else "VIEW CONTACT") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (edit) {
                    TextField(n, { n = it }, label = { Text("Name") })
                    TextField(ph, { ph = it }, label = { Text("Phone Number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    TextField(em, { em = it }, label = { Text("Email") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                    TextField(d, { d = it }, label = { Text("Description") })
                    TextField(tagInput, { tagInput = it }, label = { Text("Tags (comma separated)") })
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.uppercase(), fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        IconButton(
                            enabled = !noClipboardEnabled,
                            onClick = {
                                val text = buildString {
                                    appendLine(n)
                                    if (ph.isNotBlank()) appendLine("Phone: $ph")
                                if (em.isNotBlank()) appendLine("Email: $em")
                                if (d.isNotBlank()) appendLine(d)
                            }
                                copyToClipboardWithPolicy(context, securityManager, "vault_contact", text.trim())
                            }
                        ) {
                            Icon(if (noClipboardEnabled) Icons.Default.Lock else Icons.Default.ContentCopy, null)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (ph.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                            Spacer(Modifier.width(8.dp))
                            Text(ph, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (em.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, modifier = Modifier.size(16.dp), tint = Color.Gray)
                            Spacer(Modifier.width(8.dp))
                            Text(em)
                        }
                    }
                    if (d.isNotBlank()) {
                        HorizontalDivider(Modifier.padding(vertical = 16.dp))
                        Text("DESCRIPTION:", fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Text(d)
                    }
                }
            }
        },
        confirmButton = {
            if (edit) {
                Button(onClick = {
                    viewModel.updateItem(item.copy(name = n, phoneNumber = ph, email = em, description = d))
                    viewModel.viewModelScope.launch {
                        viewModel.assignItemTagNames(item.vaultId, item.id, parseTagInput(tagInput))
                    }
                    edit = false
                }) { Text("SAVE") }
            } else {
                Button(enabled = !noClipboardEnabled, onClick = { edit = true }) { Text("EDIT") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        }
    )
}

private fun parseTagInput(raw: String): List<String> {
    return raw.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun copyToClipboardWithPolicy(
    context: Context,
    securityManager: SecurityManager,
    label: String,
    value: String
) {
    val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    if (!securityManager.isClipboardAutoClearEnabled()) return
    val delaySeconds = securityManager.getClipboardClearDelaySeconds()
    if (delaySeconds <= 0) return
    val clearRequest = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
        .setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
        .setInputData(
            workDataOf(
                ClipboardClearWorker.KEY_EXPECTED_VALUE to value
            )
        )
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ClipboardClearWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        clearRequest
    )
}

@Composable
private fun MarkdownPreview(text: String) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
            }
        },
        update = { tv ->
            markwon.setMarkdown(tv, text)
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderList(
    folders: List<VaultFolder>,
    viewModel: VaultViewModel,
    selectedFolderIds: Set<Int> = emptySet(),
    onFolderClick: (VaultFolder) -> Unit,
    onFolderLongClick: (VaultFolder) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        folders.forEach { folder ->
            var count by remember { mutableIntStateOf(0) }
            var showDeleteDialog by remember { mutableStateOf(false) }
            val selected = selectedFolderIds.contains(folder.id)
            LaunchedEffect(folder.id) {
                count = viewModel.getItemCountInFolder(folder.id)
            }
            
            ListItem(
                headlineContent = {
                    Text(
                        folder.name.uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else Color.Black
                    )
                },
                supportingContent = { 
                    Column {
                        Text(
                            "$count ITEMS",
                            fontSize = 10.sp,
                            color = if (selected) Color.White.copy(alpha = 0.8f) else Color.DarkGray
                        )
                        if (!folder.description.isNullOrBlank()) {
                            Text(
                                folder.description,
                                fontSize = 11.sp,
                                color = if (selected) Color.White else Color.Black
                            )
                        }
                    }
                },
                leadingContent = {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        modifier = Modifier.size(32.dp),
                        tint = if (selected) Color.White else Color.Black
                    )
                },
                trailingContent = { 
                    if (selectedFolderIds.isEmpty()) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, null, tint = Color.Gray)
                        }
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selectedFolderIds.isNotEmpty()) {
                            onFolderLongClick(folder)
                        } else {
                            onFolderClick(folder)
                        }
                    },
                    onLongClick = { onFolderLongClick(folder) }
                ),
                colors = ListItemDefaults.colors(
                    containerColor = if (selected) Color.Black else Color.Transparent
                )
            )
            HorizontalDivider(color = Color.Black.copy(0.1f))

            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = { Text("DELETE FOLDER?", fontWeight = FontWeight.Bold) },
                    text = { Text("This will delete the folder and its items.", fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            onClick = {
                                viewModel.deleteFolder(folder)
                                showDeleteDialog = false
                            }
                        ) { Text("DELETE") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) { Text("CANCEL") }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderContentView(
    folder: VaultFolder,
    viewModel: VaultViewModel,
    securityManager: SecurityManager,
    onBack: () -> Unit
) {
    val items by viewModel.getItemsInFolder(folder.id).collectAsState(initial = emptyList())
    var viewingItem by remember { mutableStateOf<VaultItem?>(null) }

    var showInfo by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var folderName by remember(folder.id, folder.name) { mutableStateOf(folder.name) }
    var folderDescription by remember(folder.id, folder.description) { mutableStateOf(folder.description ?: "") }
    val itemCount = items.size
    val folderType = remember(items) {
        val types = items.map { it.type }.toSet()
        when {
            types.isEmpty() -> "EMPTY"
            types.size == 1 && types.contains("IMAGE") -> "IMAGE FOLDER"
            types.size == 1 && types.contains("FILE") -> "FILE FOLDER"
            types.size == 1 && types.contains("NOTE") -> "NOTE FOLDER"
            types.size == 1 && types.contains("PASSWORD") -> "PASSWORD FOLDER"
            types.size == 1 && types.contains("CONTACT") -> "CONTACT FOLDER"
            else -> "MIXED FOLDER"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName.uppercase(), fontWeight = FontWeight.Black) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Rename folder")
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Folder details")
                    }
                }
            )
        },
        containerColor = Color(0xFFF0F0F0)
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(items, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.name, fontWeight = FontWeight.Bold) },
                    supportingContent = { Text(item.type, fontSize = 10.sp) },
                    leadingContent = { 
                        val icon = when(item.type) {
                            "IMAGE" -> Icons.Default.Image
                            "NOTE" -> Icons.AutoMirrored.Filled.Note
                            "PASSWORD" -> Icons.Default.Password
                            "CONTACT" -> Icons.Default.Person
                            else -> Icons.Default.Description
                        }
                        Icon(icon, null)
                    },
                    modifier = Modifier.clickable { viewingItem = item }
                )
                HorizontalDivider(color = Color.Black.copy(alpha = 0.1f))
            }
        }
        
        viewingItem?.let { item ->
            when(item.type) {
                "IMAGE" -> ImageViewer(item, viewModel) { viewingItem = null }
                "NOTE" -> NoteDetail(item, viewModel) { viewingItem = null }
                "PASSWORD" -> PasswordDetail(item, viewModel) { viewingItem = null }
                "CONTACT" -> ContactDetail(item, viewModel) { viewingItem = null }
                else -> FileViewer(item, viewModel) { viewingItem = null }
            }
        }

        if (showInfo) {
            AlertDialog(
                onDismissRequest = { showInfo = false },
                title = { Text("FOLDER DETAILS", fontWeight = FontWeight.Black) },
                text = {
                    Column {
                        Text("Name: $folderName")
                        Text("Items: $itemCount")
                        Text("Type: $folderType")
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(folder.createdAt))
                        Text("Created: $date")
                        if (folderDescription.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text("Description:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(folderDescription, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showInfo = false }) { Text("CLOSE") }
                }
            )
        }

        if (showRenameDialog) {
            var renameValue by remember { mutableStateOf(folderName) }
            var descValue by remember { mutableStateOf(folderDescription) }
            var renameError by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("EDIT FOLDER") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextField(
                            value = renameValue,
                            onValueChange = {
                                renameValue = it
                                renameError = null
                            },
                            label = { Text("Folder name") },
                            singleLine = true
                        )
                        TextField(
                            value = descValue,
                            onValueChange = { descValue = it },
                            label = { Text("Description") }
                        )
                        if (renameError != null) {
                            Text(renameError ?: "", color = Color.Red, fontSize = 11.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        if (renameValue.trim().isBlank()) {
                            renameError = "Folder name is required."
                            return@Button
                        }
                        viewModel.renameFolder(
                            folder = folder,
                            newName = renameValue.trim(),
                            newDescription = descValue.trim().ifBlank { null }
                        ) { success ->
                            if (!success) {
                                renameError = "Could not rename folder."
                            } else {
                                folderName = renameValue.trim()
                                folderDescription = descValue.trim()
                                showRenameDialog = false
                            }
                        }
                    }) {
                        Text("SAVE")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("CANCEL")
                    }
                }
            )
        }
    }
}

private data class ExportOutcome(
    val success: Boolean,
    val message: String
)

private suspend fun exportSelectedItemsToVault(
    context: Context,
    selectedItems: List<VaultItem>
): ExportOutcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (selectedItems.isEmpty()) {
        return@withContext ExportOutcome(false, "No items selected.")
    }

    return@withContext try {
        val timestamp = System.currentTimeMillis()
        val copiedMediaCount = exportMediaItemsAsOriginal(
            context = context,
            items = selectedItems,
            subDirForImage = "Images",
            subDirForFile = "Files"
        )
        val textItems = selectedItems.filter { isTextExportType(it.type) }
        val textOutcome = exportTextItems(context, textItems, timestamp, null)

        val exportedAnything = copiedMediaCount > 0 || textOutcome.exportedCount > 0
        if (!exportedAnything) {
            ExportOutcome(false, "Nothing was exported.")
        } else {
            val message = buildString {
                append("Exported to Downloads/Vault")
                if (copiedMediaCount > 0) append(" ($copiedMediaCount files/images)")
                if (textOutcome.exportedCount > 0) append(" + ${textOutcome.exportedCount} text item(s)")
            }
            ExportOutcome(true, message)
        }
    } catch (e: Exception) {
        ExportOutcome(false, "Export failed: ${e.message ?: "unknown error"}")
    }
}

private suspend fun exportSelectedFoldersToVault(
    context: Context,
    selectedFolders: List<VaultFolder>,
    allItems: List<VaultItem>,
    currentTab: String
): ExportOutcome = withContext(kotlinx.coroutines.Dispatchers.IO) {
    if (selectedFolders.isEmpty()) {
        return@withContext ExportOutcome(false, "No folders selected.")
    }

    return@withContext try {
        var copiedMediaCount = 0
        var exportedTextCount = 0
        val timestamp = System.currentTimeMillis()

        selectedFolders.forEach { folder ->
            val folderItems = allItems.filter { it.folderId == folder.id }
            if (folderItems.isEmpty()) return@forEach

            val folderName = sanitizeFolderExportName(folder.name)
            val mediaPrefix = when (currentTab) {
                "IMG" -> "Image"
                "FILES" -> "File"
                else -> null
            }
            val mediaContainer = when (currentTab) {
                "IMG" -> "Images"
                "FILES" -> "Files"
                else -> null
            }

            if (mediaPrefix != null && mediaContainer != null) {
                val exportFolderName = "${mediaPrefix}_${folderName}"
                copiedMediaCount += exportMediaItemsAsOriginal(
                    context = context,
                    items = folderItems,
                    subDirForImage = "$mediaContainer/$exportFolderName",
                    subDirForFile = "$mediaContainer/$exportFolderName"
                )
            } else {
                copiedMediaCount += exportMediaItemsAsOriginal(
                    context = context,
                    items = folderItems,
                    subDirForImage = "Images/Image_$folderName",
                    subDirForFile = "Files/File_$folderName"
                )
            }

            val textItems = folderItems.filter { isTextExportType(it.type) }
            if (textItems.isNotEmpty()) {
                val textFolder = "Texts/${sanitizeFileName(folder.name)}"
                exportedTextCount += exportTextItems(
                    context = context,
                    textItems = textItems,
                    timestamp = timestamp,
                    subDirectory = textFolder
                ).exportedCount
            }
        }

        if (copiedMediaCount == 0 && exportedTextCount == 0) {
            ExportOutcome(false, "No exportable items found in selected folders.")
        } else {
            val message = buildString {
                append("Exported selected folders to Downloads/Vault")
                if (copiedMediaCount > 0) append(" ($copiedMediaCount files/images)")
                if (exportedTextCount > 0) append(" + $exportedTextCount text item(s)")
            }
            ExportOutcome(true, message)
        }
    } catch (e: Exception) {
        ExportOutcome(false, "Export failed: ${e.message ?: "unknown error"}")
    }
}

private data class TextExportOutcome(
    val exportedCount: Int
)

private fun isTextExportType(type: String): Boolean {
    return type == "NOTE" || type == "PASSWORD" || type == "CONTACT"
}

private fun sanitizeFolderExportName(raw: String): String {
    val cleaned = sanitizeFileName(raw)
    return if (cleaned.startsWith("F", ignoreCase = false)) cleaned else "F$cleaned"
}

private fun exportMediaItemsAsOriginal(
    context: Context,
    items: List<VaultItem>,
    subDirForImage: String,
    subDirForFile: String
): Int {
    val usedNames = mutableSetOf<String>()
    var copiedCount = 0
    items.forEachIndexed { index, item ->
        if ((item.type != "IMAGE" && item.type != "FILE") || item.filePath.isNullOrBlank()) return@forEachIndexed
        val source = File(item.filePath)
        if (!source.exists()) return@forEachIndexed

        val ext = item.extension?.ifBlank { null } ?: source.extension.ifBlank { "bin" }
        val baseName = sanitizeFileName(item.name.ifBlank { "item_${index + 1}" })
        val fileName = uniqueZipName("$baseName.$ext", usedNames)
        val subDir = if (item.type == "IMAGE") subDirForImage else subDirForFile
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(ext.lowercase(Locale.getDefault()))
            ?: "application/octet-stream"

        val targetUri = createVaultExportUri(context, fileName, mime, subDir)
        if (targetUri == null) return@forEachIndexed
        context.contentResolver.openOutputStream(targetUri)?.use { out ->
            FileInputStream(source).use { input -> input.copyTo(out, 64 * 1024) }
        } ?: return@forEachIndexed
        finalizeVaultExportUri(context, targetUri)
        copiedCount++
    }
    return copiedCount
}

private fun exportTextItems(
    context: Context,
    textItems: List<VaultItem>,
    timestamp: Long,
    subDirectory: String?
): TextExportOutcome {
    if (textItems.isEmpty()) return TextExportOutcome(exportedCount = 0)

    if (textItems.size == 1) {
        val item = textItems.first()
        val txtName = sanitizeFileName(item.name.ifBlank { "vault_text_$timestamp" }) + ".txt"
        val uri = createVaultExportUri(context, txtName, "text/plain", subDirectory) ?: return TextExportOutcome(0)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(buildTextExport(item).toByteArray(Charsets.UTF_8))
        } ?: return TextExportOutcome(0)
        finalizeVaultExportUri(context, uri)
        return TextExportOutcome(exportedCount = 1)
    }

    val zipName = "vault_text_export_${timestamp}.zip"
    val uri = createVaultExportUri(context, zipName, "application/zip", subDirectory) ?: return TextExportOutcome(0)
    val usedNames = mutableSetOf<String>()
    context.contentResolver.openOutputStream(uri)?.use { out ->
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            textItems.forEachIndexed { index, item ->
                val folder = when (item.type) {
                    "NOTE" -> "notes"
                    "PASSWORD" -> "passwords"
                    "CONTACT" -> "contacts"
                    else -> "items"
                }
                val baseName = sanitizeFileName(item.name.ifBlank { "item_${index + 1}" })
                val entryName = uniqueZipName("$folder/$baseName.txt", usedNames)
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(buildTextExport(item).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    } ?: return TextExportOutcome(0)
    finalizeVaultExportUri(context, uri)
    return TextExportOutcome(exportedCount = textItems.size)
}

private fun createVaultExportUri(
    context: Context,
    fileName: String,
    mimeType: String,
    subDirectory: String? = null
): Uri? {
    val resolver = context.contentResolver
    val relativePath = buildString {
        append(Environment.DIRECTORY_DOWNLOADS)
        append("/Vault")
        if (!subDirectory.isNullOrBlank()) {
            append("/")
            append(subDirectory.trim('/'))
        }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } else {
        val downloadRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = if (subDirectory.isNullOrBlank()) {
            File(downloadRoot, "Vault")
        } else {
            File(downloadRoot, "Vault/${subDirectory.trim('/')}")
        }
        if (!dir.exists()) dir.mkdirs()
        Uri.fromFile(File(dir, fileName))
    }
}

private fun finalizeVaultExportUri(context: Context, uri: Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }
}

private fun sanitizeFileName(input: String): String {
    return input.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "vault_item" }
}

private fun uniqueZipName(name: String, used: MutableSet<String>): String {
    if (used.add(name)) return name
    val dot = name.lastIndexOf('.')
    val base = if (dot >= 0) name.substring(0, dot) else name
    val ext = if (dot >= 0) name.substring(dot) else ""
    var i = 1
    while (true) {
        val candidate = "${base}_$i$ext"
        if (used.add(candidate)) return candidate
        i++
    }
}

private fun buildTextExport(item: VaultItem): String {
    return buildString {
        appendLine("Type: ${item.type}")
        appendLine("Name: ${item.name}")
        if (!item.description.isNullOrBlank()) appendLine("Description: ${item.description}")
        if (!item.content.isNullOrBlank()) appendLine("Content: ${item.content}")
        if (!item.username.isNullOrBlank()) appendLine("Username: ${item.username}")
        if (!item.passCategory.isNullOrBlank()) appendLine("Category: ${item.passCategory}")
        if (!item.link.isNullOrBlank()) appendLine("Link: ${item.link}")
        if (!item.email.isNullOrBlank()) appendLine("Email: ${item.email}")
        if (!item.phoneNumber.isNullOrBlank()) appendLine("Phone: ${item.phoneNumber}")
        appendLine("CreatedAt: ${item.createdAt}")
    }
}
