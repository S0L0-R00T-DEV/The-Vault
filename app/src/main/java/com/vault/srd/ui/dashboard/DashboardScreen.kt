package com.vault.srd.ui.dashboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.OpenableColumns
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Masks
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.vault.srd.ui.security.BiometricAuth
import com.vault.srd.backup.core.BackupManager
import com.vault.srd.backup.model.BackupMode
import com.vault.srd.backup.model.BackupScope
import com.vault.srd.security.ClipboardClearWorker
import com.vault.srd.backup.ManualExtremeBackupWorker
import com.vault.srd.backup.ManualFullBackupWorker
import com.vault.srd.backup.ManualFullRestoreBackupWorker
import com.vault.srd.backup.ManualNormalBackupWorker
import com.vault.srd.backup.ManualRestoreBackupWorker
import com.vault.srd.backup.core.RecoveryPhrase
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultItem
import com.vault.srd.ui.intruder.IntruderLogScreen
import com.vault.srd.ui.auth.TosSectionsCard
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: VaultViewModel,
    onCreateVault: () -> Unit,
    onVaultClick: (Vault) -> Unit
) {
    val vaults by viewModel.vaults.collectAsState()
    var showSortMenu by remember { mutableStateOf(false) }
    var showSettingsScreen by remember { mutableStateOf(false) }
    var showAboutScreen by remember { mutableStateOf(false) }
    var showTosScreen by remember { mutableStateOf(false) }
    var showHowToUseScreen by remember { mutableStateOf(false) }
    var showPrivacyScreen by remember { mutableStateOf(false) }
    var showIntruderScreen by remember { mutableStateOf(false) }
    var showWarnings by remember { mutableStateOf(false) }
    var editVaultTarget by remember { mutableStateOf<Vault?>(null) }
    val context = LocalContext.current
    val biometricManager = remember(context) { BiometricManager.from(context) }
    val scope = rememberCoroutineScope()

    val securityManager = viewModel.repository.securityManager
    val securityStatus by produceState(
        initialValue = Triple(false, false, true),
        key1 = securityManager
    ) {
        value = withContext(Dispatchers.IO) {
            Triple(
                securityManager.isRooted(),
                securityManager.isDebuggerAttached(),
                securityManager.verifyIntegrity()
            )
        }
    }
    val isRooted = securityStatus.first
    val isDebugger = securityStatus.second
    val isIntegrityOk = securityStatus.third

    Scaffold(
        topBar = {
            if (showSettingsScreen) {
                TopAppBar(
                    title = { Text("SETTINGS", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showSettingsScreen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else if (showAboutScreen) {
                TopAppBar(
                    title = { Text("ABOUT", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showAboutScreen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else if (showTosScreen) {
                TopAppBar(
                    title = { Text("TERMS OF SERVICE", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showTosScreen = false; showAboutScreen = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else if (showHowToUseScreen) {
                TopAppBar(
                    title = { Text("HOW TO USE", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showHowToUseScreen = false; showAboutScreen = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else if (showPrivacyScreen) {
                TopAppBar(
                    title = { Text("PRIVACY POLICY", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showPrivacyScreen = false; showAboutScreen = true }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else if (showIntruderScreen) {
                TopAppBar(
                    title = { Text("INTRUDER CAPTURES", color = Color.White, fontWeight = FontWeight.Black) },
                    navigationIcon = {
                        IconButton(onClick = { showIntruderScreen = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
                )
            } else {
                TopAppBar(
                    title = { Text("THE VAULT", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 4.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                    actions = {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = Color.White)
                        }
                        IconButton(onClick = { showWarnings = true }) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = "Warnings", tint = Color.White)
                        }
                        IconButton(onClick = { showAboutScreen = true }) {
                            Icon(Icons.Default.Info, contentDescription = "About", tint = Color.White)
                        }
                        IconButton(onClick = { showSettingsScreen = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(text = { Text("Name A-Z") }, onClick = { viewModel.setSortOrder(SortOrder.NAME_ASC); showSortMenu = false })
                            DropdownMenuItem(text = { Text("Name Z-A") }, onClick = { viewModel.setSortOrder(SortOrder.NAME_DESC); showSortMenu = false })
                            DropdownMenuItem(text = { Text("Newest First") }, onClick = { viewModel.setSortOrder(SortOrder.CREATED_LAST); showSortMenu = false })
                            DropdownMenuItem(text = { Text("Oldest First") }, onClick = { viewModel.setSortOrder(SortOrder.CREATED_FIRST); showSortMenu = false })
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!showSettingsScreen && !showIntruderScreen && !showAboutScreen && !showTosScreen && !showHowToUseScreen && !showPrivacyScreen) {
                val intruderEnabled = securityManager.isIntruderCaptureEnabled()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (intruderEnabled) {
                        FloatingActionButton(
                            onClick = {
                        if (biometricManager.canAuthenticate(
                                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                    BiometricManager.Authenticators.BIOMETRIC_WEAK
                            )
                            != BiometricManager.BIOMETRIC_SUCCESS
                        ) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Fingerprint required to access intruder gallery",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@FloatingActionButton
                                }
                                val activity = context as? android.app.Activity
                                if (activity == null) {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Biometric authentication unavailable",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@FloatingActionButton
                                }
                                BiometricAuth.authenticate(
                                    activity = activity,
                                    title = "Intruder Gallery",
                                    subtitle = "Authenticate to open intruder captures",
                                    onSuccess = { showIntruderScreen = true },
                                    onError = { message ->
                                        android.widget.Toast.makeText(
                                            context,
                                            message,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            },
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Masks, contentDescription = "Intruder Captures")
                        }
                    } else {
                        Spacer(modifier = Modifier.size(56.dp))
                    }
                    FloatingActionButton(
                        onClick = onCreateVault,
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Create Vault")
                    }
                }
            }
        },
        containerColor = Color.Black
    ) { padding ->
        if (showWarnings) {
            SecurityWarningsDialog(
                isRooted = isRooted,
                isDebugger = isDebugger,
                isIntegrityOk = isIntegrityOk,
                onDismiss = { showWarnings = false }
            )
        }
        if (showSettingsScreen) {
            SettingsScreen(
                viewModel = viewModel,
                securityManager = viewModel.repository.securityManager
            )
        } else if (showAboutScreen) {
            Box(modifier = Modifier.padding(padding)) {
                AboutScreen(
                    onOpenTos = {
                        showAboutScreen = false
                        showHowToUseScreen = false
                        showPrivacyScreen = false
                        showTosScreen = true
                    },
                    onOpenHowToUse = {
                        showAboutScreen = false
                        showTosScreen = false
                        showPrivacyScreen = false
                        showHowToUseScreen = true
                    },
                    onOpenPrivacy = {
                        showAboutScreen = false
                        showTosScreen = false
                        showHowToUseScreen = false
                        showPrivacyScreen = true
                    }
                )
            }
        } else if (showTosScreen) {
            Box(modifier = Modifier.padding(padding)) { TosReadOnlyScreen() }
        } else if (showHowToUseScreen) {
            Box(modifier = Modifier.padding(padding)) { HowToUseScreen() }
        } else if (showPrivacyScreen) {
            Box(modifier = Modifier.padding(padding)) { PrivacyPolicyScreen() }
        } else if (showIntruderScreen) {
            IntruderLogScreen(
                onBack = { showIntruderScreen = false }
            )
        } else if (vaults.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "No vaults - lock icon",
                    tint = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "NO VAULTS CREATED",
                    color = Color.White.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.semantics { contentDescription = "No vaults created yet" }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Tap the + button to create a secure vault",
                    color = Color.White.copy(alpha = 0.3f),
                    fontSize = 14.sp,
                    modifier = Modifier.semantics { contentDescription = "Instructions to create vault" }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vaults, key = { it.id }) { vault ->
                        VaultListItem(
                            vault = vault,
                            onClick = onVaultClick,
                            onLongClick = { editVaultTarget = it }
                        )
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }
                }
            }
        }

        if (editVaultTarget != null) {
            EditVaultDialog(
                vault = editVaultTarget!!,
                onDismiss = { editVaultTarget = null },
                onSave = { updated ->
                    editVaultTarget = null
                    scope.launch {
                        runCatching { viewModel.repository.updateVault(updated) }
                    }
                }
            )
        }
    }
}

private const val GITHUB_REPO_URL_PLACEHOLDER = "https://github.com/S0L0-R00T-DEV/The-Vault"

@Composable
private fun AboutScreen(
    onOpenTos: () -> Unit,
    onOpenHowToUse: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("THE VAULT", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
        Text("Version 1.0", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "The Vault is a secure offline vault for notes, passwords, contacts, images, and files. " +
                "Everything is stored locally on your device with encrypted content fields.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(14.dp))
        Text("Core Features", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("• Offline-first local storage", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Encrypted sensitive content", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Intruder capture and security controls", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Backup/restore options (normal/extreme/full)", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Text("Privacy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("The Vault is designed as a zero-knowledge app. Your data remains on your device.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        Button(
            onClick = onOpenTos,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text("T O S")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenHowToUse,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text("HOW TO USE")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenPrivacy,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) {
            Text("PRIVACY POLICY")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (GITHUB_REPO_URL_PLACEHOLDER.isBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        "GitHub link not configured yet.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL_PLACEHOLDER))
                    runCatching { context.startActivity(intent) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("GITHUB")
        }
    }
}

@Composable
private fun TosReadOnlyScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("TERMS OF SERVICE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text("Effective Date: January 1, 2026", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        TosSectionsCard()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Important: The Vault is zero-knowledge. The developer cannot access your data or recover lost credentials.",
            color = Color(0xFFFFC107),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun HowToUseScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("HOW TO USE", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Text("Getting Started", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Tap + to create a vault. Set a 6-digit PIN, optional description, and an importance color.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• The vault limit is shown on the create screen.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Long-press any vault to edit its color and description.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Unlocking & Access", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Enter the correct PIN to unlock. Unlock should be instant.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Decoy PIN opens a hidden decoy vault (configured in Settings).", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Fingerprint Unlock (Settings) can open All Vaults or selected vaults.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Randomized PIN Pad shuffles digits on unlock for shoulder-surf protection.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Inactivity Lock automatically re-locks after the time you set.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Vault Content", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Inside a vault, tap + to add Notes, Passwords, Contacts, Images, or Files.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Use tabs to switch content type. Tags are scoped per tab only.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Long-press items to multi-select and then delete, export, or group.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Folders & Grouping", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Multi-select items and tap the folder icon to group into a folder.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Tap a folder to open it; long-press to select and delete/export folders.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Rename folders from the folder details screen.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Files & Images", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Use PICK ONE for a single file, or PICK MULTIPLE for batch import.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Large files show progress. Keep the app open for best stability.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Storage Handling: enable 'Delete original files' to remove source files after import.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Export", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Select items or folders and tap the Share icon to export to Downloads/Vault.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Text exports are saved as text files or ZIPs; media exports are organized by folder.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Security Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Screenshot Protection is always enabled to block screenshots and screen recording.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Stealth Mode disguises the app icon; choose a disguise in Settings.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• App Icon Background lets you choose black/white/gray (when Stealth Mode is off).", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• No Clipboard Mode blocks copying sensitive values.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Clipboard Auto-Clear removes copied data after 10/30/60 seconds.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• PIN Hash Strength defaults to STRONG (200k). Options: MIN 50k, BALANCED 100k, STRONG 200k, EXTRA STRONG 700k. Tap APPLY and authenticate to change; updates take effect after next successful unlock.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Auto-Wipe (Extreme) wipes vault content after repeated failed attempts.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Intruder Capture", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Enable in Settings (requires camera permission).", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• After 3 failed unlock attempts, a selfie is captured per attempt.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Access the intruder gallery from the dashboard mask icon.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Backups", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Read and accept the device-identity warning before first backup.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Copy the Device Recovery Token for disaster recovery.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• The token helps verify an Extreme backup belongs to this device.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Normal backup: single-vault encrypted .vltbck file.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Extreme backup: device-locked, restore only on the same device with fingerprint.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Full backup (no zip): creates backup.vltbck + key.vltk in Downloads/Vault/Backups/Full Backup/Full Backup N.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Full Extreme: encrypted zip locked to this device with fingerprint.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Restore requires correct keys/phrase and (if extreme) fingerprint auth.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Backup History shows recent backups; Vault Health flags weak/duplicate/old items.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Notifications", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Allow notifications so backup/restore progress stays visible in background.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))

        Text("Tips", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Store backup files and key files in separate safe locations.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
}

@Composable
private fun PrivacyPolicyScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("PRIVACY POLICY", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        Text("Effective Date: January 1, 2026", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Text("Overview", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(
            "The Vault is an offline-first security app. Vault content is stored locally on your device. We do not collect or transmit your vault data to any server.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("Information We Collect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• The app does not collect personal data or analytics.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• We do not use advertising trackers or third-party analytics SDKs.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("On-Device Processing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Sensitive fields are encrypted on-device.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• We do not have access to your PINs, recovery phrases, or backups.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Data Storage & Retention", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Vault data stays on your device until you delete it or uninstall the app.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Backups are stored wherever you choose to save them (e.g., Downloads).", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Permissions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• Camera: used only for intruder capture when enabled by you.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Storage access: used for importing/exporting files and backups.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Notifications: used to show backup/restore progress in background.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• Biometrics: handled by the OS; the app only receives an authentication result.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Data Sharing", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• We do not share your vault content with third parties.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• App stores or platform providers may collect basic device/download data under their own policies.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• External links (such as GitHub) are governed by their respective privacy policies.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Children's Privacy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• This app is not intended for children under 13.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Text("• We do not knowingly collect personal information from children.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Security", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• No method of storage is 100% secure. Use strong PINs and safe backup handling.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Changes to This Policy", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• We may update this policy for future releases. The effective date will change when it does.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        Text("Contact", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text("• For questions or requests, refer to the project repository.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
}

@Composable
fun SecurityWarningsDialog(
    isRooted: Boolean,
    isDebugger: Boolean,
    isIntegrityOk: Boolean,
    onDismiss: () -> Unit
) {
    val message = when {
        isRooted -> "This device is ROOTED. A rooted or compromised device can bypass OS security. If your device is infected or modified, any data loss or exposure is your responsibility."
        isDebugger -> "A debugger is attached. Debuggers can inspect and modify app behavior; use only on trusted devices."
        !isIntegrityOk -> "App integrity check failed. The app may have been tampered with. Use with caution."
        else -> "No root detected. Device integrity looks OK and the app can provide full protection."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SECURITY WARNINGS", color = Color.White, fontWeight = FontWeight.Black) },
        text = {
            Column {
                Text(message, color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                if (isRooted || isDebugger || !isIntegrityOk) {
                    Text(
                        "THE VAULT cannot guarantee full protection on rooted or compromised devices.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("I UNDERSTAND", color = Color.White) }
        },
        containerColor = Color.Black
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: VaultViewModel,
    securityManager: com.vault.srd.security.SecurityManager
) {
    SettingsContent(
        viewModel = viewModel,
        securityManager = securityManager,
        modifier = Modifier.fillMaxSize()
    )
}

private enum class VaultHealthCategory {
    DUPLICATE_PASSWORDS,
    WEAK_PASSWORDS,
    OLD_ITEMS,
    MISSING_BACKUP
}

private data class VaultHealthSummary(
    val duplicatePasswordItems: List<VaultItem>,
    val weakPasswordItems: List<VaultItem>,
    val oldItems: List<VaultItem>,
    val vaultsWithoutBackup: List<Vault>
)

private fun evaluateVaultHealth(
    items: List<VaultItem>,
    vaultsWithoutBackup: List<Vault>
): VaultHealthSummary {
    val passwordItems = items.filter { it.type.equals("PASSWORD", ignoreCase = true) }
        .filter { !it.content.isNullOrBlank() }
    val duplicatePasswordItems = passwordItems
        .groupBy { it.content.orEmpty() }
        .values
        .filter { it.size > 1 }
        .flatten()
        .distinctBy { it.id }
    val weakPasswordItems = passwordItems.filter { item ->
        val score = PasswordTools.evaluate(item.content.orEmpty()).label
        score == PasswordStrengthLabel.WEAK || score == PasswordStrengthLabel.FAIR
    }
    val cutoffMs = System.currentTimeMillis() - (365L * 24L * 60L * 60L * 1000L)
    val oldItems = items.filter { item ->
        val lastTouched = if (item.updatedAt > 0L) item.updatedAt else item.createdAt
        lastTouched in 1 until cutoffMs
    }
    return VaultHealthSummary(
        duplicatePasswordItems = duplicatePasswordItems,
        weakPasswordItems = weakPasswordItems,
        oldItems = oldItems,
        vaultsWithoutBackup = vaultsWithoutBackup
    )
}

@Composable
private fun SettingsContent(
    viewModel: VaultViewModel,
    securityManager: com.vault.srd.security.SecurityManager,
    modifier: Modifier = Modifier
) {
    val timeout by viewModel.inactivityTimeoutSeconds.collectAsState()
    val vaults by viewModel.vaults.collectAsState()
    val decoyVault by viewModel.getHiddenDecoyVault().collectAsState(initial = null)
    val deleteOriginal by viewModel.deleteOriginalFiles.collectAsState()
    var intruderCaptureEnabled by remember { mutableStateOf(securityManager.isIntruderCaptureEnabled()) }
    val securityStatus by produceState(
        initialValue = Triple(false, false, true),
        key1 = securityManager
    ) {
        value = withContext(Dispatchers.IO) {
            Triple(
                securityManager.isRooted(),
                securityManager.isDebuggerAttached(),
                securityManager.verifyIntegrity()
            )
        }
    }
    val securityManagerRoot = securityStatus.first
    val securityManagerDebugger = securityStatus.second
    val securityManagerIntegrityOk = securityStatus.third
    var isStealth by remember { mutableStateOf(securityManager.isStealthModeEnabled()) }
    var selectedAlias by remember { mutableStateOf(securityManager.getActiveAlias() ?: "AssistantAlias") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backupManager = remember {
        GlobalContext.get().get<BackupManager>()
    }
    val workManager = remember(context) { WorkManager.getInstance(context) }
    var intruderPermissionError by remember { mutableStateOf<String?>(null) }
    var noClipboardEnabled by remember { mutableStateOf(securityManager.isNoClipboardModeEnabled()) }
    var clipboardAutoClearEnabled by remember { mutableStateOf(securityManager.isClipboardAutoClearEnabled()) }
    var clipboardClearDelay by remember { mutableIntStateOf(securityManager.getClipboardClearDelaySeconds()) }
    var noClipboardAuthError by remember { mutableStateOf<String?>(null) }
    var pinHashIterations by remember { mutableIntStateOf(securityManager.getPinHashIterations()) }
    var pendingPinHashIterations by remember { mutableIntStateOf(pinHashIterations) }
    var pinHashAuthError by remember { mutableStateOf<String?>(null) }
    var selectedBackupOption by remember { mutableStateOf("NORMAL") }
    var backupIdentityWarningAcknowledged by remember {
        mutableStateOf(securityManager.hasShownBackupIdentityWarning())
    }
    var showBackupHistoryDialog by remember { mutableStateOf(false) }
    var backupHistoryEntries by remember {
        mutableStateOf<List<BackupManager.BackupHistoryEntry>>(emptyList())
    }
    var backupHistoryLoading by remember { mutableStateOf(false) }
    var backupHistoryError by remember { mutableStateOf<String?>(null) }
    var selectedBackupHistoryEntry by remember { mutableStateOf<BackupManager.BackupHistoryEntry?>(null) }
    var showBackupHistoryEntryDetails by remember { mutableStateOf(false) }
    var showVaultHealthDialog by remember { mutableStateOf(false) }
    var vaultHealthLoading by remember { mutableStateOf(false) }
    var vaultHealthError by remember { mutableStateOf<String?>(null) }
    var showVaultHealthDetailsDialog by remember { mutableStateOf(false) }
    var selectedHealthCategory by remember { mutableStateOf<VaultHealthCategory?>(null) }
    var vaultHealth by remember { mutableStateOf<VaultHealthSummary?>(null) }
    var selectedNormalVaultId by remember { mutableStateOf<Int?>(null) }
    var selectedExtremeVaultId by remember { mutableStateOf<Int?>(null) }
    var showNormalVaultPicker by remember { mutableStateOf(false) }
    var showExtremeVaultPicker by remember { mutableStateOf(false) }
    var normalVaultPin by remember { mutableStateOf("") }
    var normalMasterKey by remember { mutableStateOf("") }
    var fullMasterKey by remember { mutableStateOf("") }
    var fullGeneratedKey by remember { mutableStateOf("") }
    var fullRecoveryPhrase by remember { mutableStateOf("") }
    var showFullMasterKey by remember { mutableStateOf(false) }
    var showFullGeneratedKey by remember { mutableStateOf(false) }
    var showFullRecoveryPhrase by remember { mutableStateOf(false) }
    var fullExtremeZip by remember { mutableStateOf(false) }
    var showNormalVaultPin by remember { mutableStateOf(false) }
    var showNormalMasterKey by remember { mutableStateOf(false) }
    var backupImportUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBackupDescriptor by remember { mutableStateOf<BackupManager.BackupDescriptor?>(null) }
    var selectedBackupFileName by remember { mutableStateOf<String?>(null) }
    var fullBackupImportUri by remember { mutableStateOf<Uri?>(null) }
    var fullBackupKeyImportUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFullBackupDescriptor by remember { mutableStateOf<BackupManager.FullBackupPackageDescriptor?>(null) }
    var selectedFullBackupFileName by remember { mutableStateOf<String?>(null) }
    var selectedFullBackupKeyFileName by remember { mutableStateOf<String?>(null) }
    var showFullBackupNoticeDialog by remember { mutableStateOf(false) }
    var restoreVaultPin by remember { mutableStateOf("") }
    var restoreMasterKey by remember { mutableStateOf("") }
    var showRestoreVaultPin by remember { mutableStateOf(false) }
    var showRestoreMasterKey by remember { mutableStateOf(false) }
    var backupActionBusy by remember { mutableStateOf(false) }
    var restoreActionBusy by remember { mutableStateOf(false) }
    var backupImportStatus by remember { mutableStateOf<String?>(null) }
    var backupStatusIsError by remember { mutableStateOf(false) }
    var backupMonitorJob by remember { mutableStateOf<Job?>(null) }
    var restoreMonitorJob by remember { mutableStateOf<Job?>(null) }
    var backupStartedAtMs by remember { mutableStateOf<Long?>(null) }
    var restoreStartedAtMs by remember { mutableStateOf<Long?>(null) }
    var notificationsPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        intruderCaptureEnabled = granted
        securityManager.setIntruderCaptureEnabled(granted)
        if (!granted) {
            intruderPermissionError = "Camera permission denied. Intruder capture remains disabled."
        } else {
            intruderPermissionError = null
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsPermissionGranted = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        backupStatusIsError = !granted
        backupImportStatus = if (granted) {
            "Notification permission granted. Backup/restore progress will stay visible in background."
        } else {
            "Notification permission denied. Background progress notification may be hidden."
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            backupImportStatus = "No backup file selected."
            backupStatusIsError = true
            selectedBackupFileName = null
            backupImportUri = null
            selectedBackupDescriptor = null
            return@rememberLauncherForActivityResult
        }
        val fileName = resolveDocumentName(context, uri) ?: uri.lastPathSegment ?: "selected_backup.vltbck"
        selectedBackupFileName = fileName
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!fileName.lowercase().endsWith(".vltbck")) {
            backupImportUri = null
            selectedBackupDescriptor = null
            backupImportStatus = "Invalid file type. Select a .vltbck backup file."
            backupStatusIsError = true
            return@rememberLauncherForActivityResult
        }
        backupImportStatus = "Checking backup file..."
        backupStatusIsError = false
        scope.launch {
            val descriptor = backupManager.readBackupDescriptor(uri)
            if (descriptor == null) {
                backupImportUri = null
                selectedBackupDescriptor = null
                backupImportStatus = "Invalid or unreadable backup file."
                backupStatusIsError = true
                return@launch
            }
            selectedBackupDescriptor = descriptor
            backupImportUri = uri
            backupImportStatus = when {
                descriptor.mode == BackupMode.EXTREME ->
                    "Extreme backup loaded. Restore requires fingerprint on the same device."
                descriptor.mode == BackupMode.NORMAL && descriptor.scope == BackupScope.SINGLE_VAULT ->
                    "Normal backup loaded. Enter vault PIN and master backup key to restore."
                else ->
                    "This backup type is currently not supported in this screen."
            }
            backupStatusIsError =
                descriptor.mode == BackupMode.NORMAL && descriptor.scope != BackupScope.SINGLE_VAULT
        }
    }
    val importFullBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            backupImportStatus = "No full backup package selected."
            backupStatusIsError = true
            selectedFullBackupFileName = null
            fullBackupImportUri = null
            selectedFullBackupDescriptor = null
            return@rememberLauncherForActivityResult
        }
        val fileName = resolveDocumentName(context, uri) ?: uri.lastPathSegment ?: "full_backup.zip"
        selectedFullBackupFileName = fileName
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!fileName.lowercase().endsWith(".zip")) {
            fullBackupImportUri = null
            selectedFullBackupDescriptor = null
            backupImportStatus = "Invalid file type. Select a .zip full backup package."
            backupStatusIsError = true
            return@rememberLauncherForActivityResult
        }
        backupImportStatus = "Checking full backup package..."
        backupStatusIsError = false
        scope.launch {
            val descriptor = backupManager.readFullBackupPackageDescriptor(uri)
            if (descriptor == null) {
                fullBackupImportUri = null
                selectedFullBackupDescriptor = null
                backupImportStatus = "Invalid or unreadable full backup package."
                backupStatusIsError = true
                return@launch
            }
            selectedFullBackupDescriptor = descriptor
            fullBackupImportUri = uri
            fullBackupKeyImportUri = null
            selectedFullBackupKeyFileName = null
            backupImportStatus = if (descriptor.extremeZip) {
                "Extreme full backup package loaded. Restore requires fingerprint on this device."
            } else {
                "Full backup package loaded. Tap restore to recover the entire app."
            }
            backupStatusIsError = false
        }
    }

    val importFullBackupFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            backupImportStatus = "No full backup file selected."
            backupStatusIsError = true
            selectedFullBackupFileName = null
            fullBackupImportUri = null
            selectedFullBackupDescriptor = null
            return@rememberLauncherForActivityResult
        }
        val fileName = resolveDocumentName(context, uri) ?: uri.lastPathSegment ?: "backup.vltbck"
        selectedFullBackupFileName = fileName
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!fileName.lowercase().endsWith(".vltbck")) {
            fullBackupImportUri = null
            selectedFullBackupDescriptor = null
            backupImportStatus = "Invalid file type. Select a .vltbck full backup file."
            backupStatusIsError = true
            return@rememberLauncherForActivityResult
        }
        backupImportStatus = "Checking full backup file..."
        backupStatusIsError = false
        scope.launch {
            val descriptor = backupManager.readBackupDescriptor(uri)
            if (descriptor == null || descriptor.scope != BackupScope.ENTIRE_APP) {
                fullBackupImportUri = null
                backupImportStatus = "Invalid or unreadable full backup file."
                backupStatusIsError = true
                return@launch
            }
            selectedFullBackupDescriptor = null
            fullBackupImportUri = uri
            backupImportStatus = "Full backup file loaded. Select the matching key.vltk file."
            backupStatusIsError = false
        }
    }

    val importFullBackupKeyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            backupImportStatus = "No key file selected."
            backupStatusIsError = true
            selectedFullBackupKeyFileName = null
            fullBackupKeyImportUri = null
            return@rememberLauncherForActivityResult
        }
        val fileName = resolveDocumentName(context, uri) ?: uri.lastPathSegment ?: "key.vltk"
        selectedFullBackupKeyFileName = fileName
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (!fileName.lowercase().endsWith(".vltk")) {
            fullBackupKeyImportUri = null
            backupImportStatus = "Invalid key file. Select a .vltk key file."
            backupStatusIsError = true
            return@rememberLauncherForActivityResult
        }
        fullBackupKeyImportUri = uri
        backupImportStatus = "Key file loaded. Tap restore to recover the entire app."
        backupStatusIsError = false
    }
    val biometricManager = remember(context) { BiometricManager.from(context) }
    fun withBiometricAuth(onSuccess: () -> Unit, onError: () -> Unit = {}) {
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            noClipboardAuthError = "Fingerprint not configured on this device."
            onError()
            return
        }
        val activity = context as? android.app.Activity
        if (activity == null) {
            noClipboardAuthError = "Biometric authentication unavailable."
            onError()
            return
        }
        BiometricAuth.authenticate(
            activity = activity,
            title = "Confirm Security Change",
            subtitle = "Use fingerprint to change No Clipboard mode",
            onSuccess = {
                noClipboardAuthError = null
                onSuccess()
            },
            onError = {
                noClipboardAuthError = it
                onError()
            }
        )
    }
    fun withPinHashFingerprintAuth(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onError("Fingerprint not configured on this device.")
            return
        }
        val activity = context as? android.app.Activity
        if (activity == null) {
            onError("Biometric authentication unavailable.")
            return
        }
        BiometricAuth.authenticate(
            activity = activity,
            title = "Apply PIN Hash Strength",
            subtitle = "Authenticate to update hash protection",
            onSuccess = onSuccess,
            onError = onError
        )
    }
    fun withBackupFingerprintAuth(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (biometricManager.canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            onError("Fingerprint not configured on this device.")
            return
        }
        val activity = context as? android.app.Activity
        if (activity == null) {
            onError("Biometric authentication unavailable.")
            return
        }
        BiometricAuth.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = onSuccess,
            onError = onError
        )
    }

    LaunchedEffect(Unit) {
        notificationsPermissionGranted = hasNotificationPermission(context)
        val hasCamera = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCamera && intruderCaptureEnabled) {
            intruderCaptureEnabled = false
            securityManager.setIntruderCaptureEnabled(false)
        }
    }
    LaunchedEffect(pinHashIterations) {
        pendingPinHashIterations = pinHashIterations
    }

    // Auto-link the hidden vault ID
    LaunchedEffect(decoyVault) {
        decoyVault?.let { securityManager.setDecoyVaultId(it.id) }
    }
    LaunchedEffect(vaults) {
        fun resolveSelectedId(current: Int?): Int? = when {
            vaults.isEmpty() -> null
            current != null && vaults.any { it.id == current } -> current
            else -> vaults.first().id
        }
        selectedNormalVaultId = when {
            vaults.isEmpty() -> null
            else -> resolveSelectedId(selectedNormalVaultId)
        }
        selectedExtremeVaultId = resolveSelectedId(selectedExtremeVaultId)
    }
    fun elapsedSuffix(startedAtMs: Long?): String {
        if (startedAtMs == null) return ""
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        return " | Elapsed ${formatOperationDuration(elapsed)}"
    }
    fun completionSuffix(startedAtMs: Long?): String {
        if (startedAtMs == null) return ""
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        return " | Time taken ${formatOperationDuration(elapsed)}"
    }
    fun failureSuffix(startedAtMs: Long?): String {
        if (startedAtMs == null) return ""
        val elapsed = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        return " | After ${formatOperationDuration(elapsed)}"
    }
    fun requireNotificationPermissionForBackgroundWork(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notificationsPermissionGranted = true
            return true
        }
        val granted = hasNotificationPermission(context)
        notificationsPermissionGranted = granted
        if (granted) return true
        backupStatusIsError = true
        backupImportStatus = "Allow notification permission to see create/restore logs in background."
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return false
    }
    fun monitorManualBackup(
        workId: java.util.UUID,
        startedAtMs: Long = System.currentTimeMillis()
    ) {
        backupStartedAtMs = startedAtMs
        backupMonitorJob?.cancel()
        backupMonitorJob = scope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
                }
                if (info == null) {
                    backupActionBusy = false
                    backupImportStatus = "Backup status unavailable."
                    backupStatusIsError = true
                    backupStartedAtMs = null
                    break
                }
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        backupActionBusy = true
                        backupImportStatus = "Backup queued. It continues even if app is minimized.${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                    WorkInfo.State.RUNNING -> {
                        backupActionBusy = true
                        val progressMessage = info.progress.getString(ManualNormalBackupWorker.KEY_PROGRESS_MESSAGE)
                            ?: info.progress.getString(ManualExtremeBackupWorker.KEY_PROGRESS_MESSAGE)
                            ?: info.progress.getString(ManualFullBackupWorker.KEY_PROGRESS_MESSAGE)
                            ?: "Creating backup. Large backups may take time; keep this screen open for best stability."
                        backupImportStatus = "$progressMessage${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        backupActionBusy = false
                        val outputFile = info.outputData.getString(ManualNormalBackupWorker.KEY_RESULT_FILE_NAME)
                            ?: info.outputData.getString(ManualExtremeBackupWorker.KEY_RESULT_FILE_NAME)
                            ?: info.outputData.getString(ManualFullBackupWorker.KEY_RESULT_FILE_NAME)
                        backupImportStatus = if (outputFile.isNullOrBlank()) {
                            "Backup created successfully.${completionSuffix(startedAtMs)}"
                        } else {
                            "Backup created: $outputFile${completionSuffix(startedAtMs)}"
                        }
                        backupStatusIsError = false
                        backupStartedAtMs = null
                        android.widget.Toast.makeText(context, "Backup created", android.widget.Toast.LENGTH_SHORT).show()
                        break
                    }
                    WorkInfo.State.FAILED -> {
                        backupActionBusy = false
                        val reason = info.outputData.getString(ManualNormalBackupWorker.KEY_ERROR_MESSAGE)
                            ?: info.outputData.getString(ManualExtremeBackupWorker.KEY_ERROR_MESSAGE)
                            ?: info.outputData.getString(ManualFullBackupWorker.KEY_ERROR_MESSAGE)
                            ?: "Backup creation failed."
                        backupImportStatus = "$reason${failureSuffix(startedAtMs)}"
                        backupStatusIsError = true
                        backupStartedAtMs = null
                        break
                    }
                    WorkInfo.State.CANCELLED -> {
                        backupActionBusy = false
                        backupImportStatus = "Backup was cancelled.${failureSuffix(startedAtMs)}"
                        backupStatusIsError = true
                        backupStartedAtMs = null
                        break
                    }
                    WorkInfo.State.BLOCKED -> {
                        backupActionBusy = true
                        backupImportStatus = "Backup waiting for system resources...${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                }
                delay(1000)
            }
        }
    }
    fun monitorRestoreWork(
        workId: java.util.UUID,
        startedAtMs: Long = System.currentTimeMillis()
    ) {
        restoreStartedAtMs = startedAtMs
        restoreMonitorJob?.cancel()
        restoreMonitorJob = scope.launch {
            while (isActive) {
                val info = withContext(Dispatchers.IO) {
                    runCatching { workManager.getWorkInfoById(workId).get() }.getOrNull()
                }
                if (info == null) {
                    restoreActionBusy = false
                    backupImportStatus = "Restore status unavailable."
                    backupStatusIsError = true
                    restoreStartedAtMs = null
                    break
                }
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> {
                        restoreActionBusy = true
                        backupImportStatus = "Restore queued. It continues even if app is minimized.${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                    WorkInfo.State.RUNNING -> {
                        restoreActionBusy = true
                        val progressMessage = info.progress.getString(ManualRestoreBackupWorker.KEY_PROGRESS_MESSAGE)
                            ?: info.progress.getString(ManualFullRestoreBackupWorker.KEY_PROGRESS_MESSAGE)
                            ?: "Restoring backup. Large backups may take time..."
                        backupImportStatus = "$progressMessage${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        restoreActionBusy = false
                        backupImportStatus = "Backup restored successfully.${completionSuffix(startedAtMs)}"
                        backupStatusIsError = false
                        restoreStartedAtMs = null
                        android.widget.Toast.makeText(context, "Backup restored", android.widget.Toast.LENGTH_SHORT).show()
                        break
                    }
                    WorkInfo.State.FAILED -> {
                        restoreActionBusy = false
                        val reason = info.outputData.getString(ManualRestoreBackupWorker.KEY_ERROR_MESSAGE)
                            ?: info.outputData.getString(ManualFullRestoreBackupWorker.KEY_ERROR_MESSAGE)
                            ?: "Backup restore failed."
                        backupImportStatus = "$reason${failureSuffix(startedAtMs)}"
                        backupStatusIsError = true
                        restoreStartedAtMs = null
                        break
                    }
                    WorkInfo.State.CANCELLED -> {
                        restoreActionBusy = false
                        backupImportStatus = "Restore was cancelled.${failureSuffix(startedAtMs)}"
                        backupStatusIsError = true
                        restoreStartedAtMs = null
                        break
                    }
                    WorkInfo.State.BLOCKED -> {
                        restoreActionBusy = true
                        backupImportStatus = "Restore waiting for system resources...${elapsedSuffix(startedAtMs)}"
                        backupStatusIsError = false
                    }
                }
                delay(1000)
            }
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Root status
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("DEVICE SECURITY", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val rootStatus = when {
                securityManagerRoot -> "Rooted – security risk"
                securityManagerDebugger -> "Debugger attached"
                !securityManagerIntegrityOk -> "Integrity check failed"
                else -> "OK – no issues detected"
            }
            Text("Root status: $rootStatus", color = Color.Gray, fontSize = 11.sp)
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Stealth Mode Section
        Column {
            ListItem(
                headlineContent = { Text("STEALTH MODE (DISGUISE)", color = Color.White, fontSize = 14.sp) },
                supportingContent = { Text("Disguise the app in your launcher.", color = Color.Gray, fontSize = 11.sp) },
                trailingContent = {
                    Switch(
                        checked = isStealth,
                        onCheckedChange = {
                            isStealth = it
                            securityManager.setStealthMode(it, selectedAlias)
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            if (isStealth) {
                Text(
                    "SELECT DISGUISE ICON:",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val aliases = listOf("AssistantAlias", "CloudAlias", "PodcastsAlias", "GoogleAlias")
                    val labels = listOf("Assistant", "Cloud", "Podcasts", "Google")
                    aliases.forEachIndexed { index, alias ->
                        val isSelected = selectedAlias == alias
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .background(
                                    if (isSelected) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedAlias = alias
                                    securityManager.setStealthMode(true, alias)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                labels[index].take(1),
                                color = if (isSelected) Color.White else Color.Gray,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // App icon background (adaptive icon)
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            var iconBgMode by remember { mutableStateOf(securityManager.getIconBackgroundMode()) }
            Text("APP ICON BACKGROUND", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Choose launcher icon background: black, white, or gray.", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("BLACK", "WHITE", "GRAY").forEach { mode ->
                    OutlinedButton(
                        onClick = {
                            iconBgMode = mode
                            securityManager.setIconBackgroundMode(mode)
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (iconBgMode == mode) Color.White else Color.Transparent,
                            contentColor = if (iconBgMode == mode) Color.Black else Color.White
                        )
                    ) {
                        Text(mode)
                    }
                }
            }
            if (isStealth) {
                Spacer(Modifier.height(6.dp))
                Text("Icon background applies when Stealth Mode is off.", color = Color.Gray, fontSize = 10.sp)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Decoy PIN Section
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("DECOY PIN", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Entering this PIN opens a hidden decoy vault.", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))

            var decoyPinInput by remember { mutableStateOf(securityManager.getDecoyPinRaw() ?: "") }
            var showDecoyPin by remember { mutableStateOf(false) }
            var isSaved by remember { mutableStateOf(securityManager.hasDecoyPin()) }
            var decoyPinError by remember { mutableStateOf<String?>(null) }
            var isSavingDecoyPin by remember { mutableStateOf(false) }

            TextField(
                value = decoyPinInput,
                onValueChange = {
                    if (it.length <= 6) {
                        decoyPinInput = it
                        isSaved = false
                        decoyPinError = null
                    }
                },
                label = { Text("6-DIGIT DECOY PIN") },
                visualTransformation = if (showDecoyPin) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showDecoyPin = !showDecoyPin }) {
                        Icon(
                            imageVector = if (showDecoyPin) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null,
                            tint = Color.Gray
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.1f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                )
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (isSavingDecoyPin || decoyPinInput.length != 6) return@Button
                    isSavingDecoyPin = true
                    decoyPinError = null
                    val pinSnapshot = decoyPinInput
                    scope.launch(Dispatchers.IO) {
                        val matchesAnyVaultPin = vaults
                            .filter { !it.isDecoy }
                            .any { vault -> securityManager.verifyPin(pinSnapshot, vault.pinHash, vault.pinSalt) }
                        val error = if (matchesAnyVaultPin) {
                            "Decoy PIN must be unique. It cannot match any normal vault PIN."
                        } else {
                            runCatching { securityManager.setDecoyPin(pinSnapshot) }
                                .exceptionOrNull()
                                ?.let { "Unable to save decoy PIN. Try again." }
                        }
                        withContext(Dispatchers.Main) {
                            isSavingDecoyPin = false
                            if (error != null) {
                                decoyPinError = error
                                isSaved = false
                            } else {
                                isSaved = true
                                decoyPinError = null
                            }
                        }
                    }
                },
                enabled = decoyPinInput.length == 6 && !isSaved && !isSavingDecoyPin,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSaved || isSavingDecoyPin) Color.Gray else Color.White,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    when {
                        isSavingDecoyPin -> "SAVING..."
                        isSaved -> "PIN SAVED"
                        else -> "CONFIRM DECOY PIN"
                    }
                )
            }
            if (decoyPinError != null) {
                Spacer(Modifier.height(6.dp))
                Text(decoyPinError!!, color = Color.Red, fontSize = 11.sp)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Inactivity Timeout
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            var timeoutInput by remember(timeout) { mutableStateOf(timeout.toString()) }
            val parsed = timeoutInput.toIntOrNull()
            val valid = parsed != null && parsed in 1..120
            Text("INACTIVITY LOCK", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Enter timeout from 1 to 120 seconds.", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = timeoutInput,
                onValueChange = { new ->
                    if (new.all { it.isDigit() } && new.length <= 3) {
                        timeoutInput = new
                        val candidate = new.toIntOrNull()
                        if (candidate != null && candidate in 1..120) {
                            viewModel.setInactivityTimeout(candidate)
                        }
                    }
                },
                label = { Text("Seconds (1-120)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.1f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                )
            )
            if (!valid && timeoutInput.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Please enter a value between 1 and 120.", color = Color.Red, fontSize = 11.sp)
            }
        }

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("PIN HASH STRENGTH", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Lower = faster unlock. Higher = stronger protection.", color = Color.Gray, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            val options = securityManager.getPinHashIterationOptions()
            val rows = options.chunked(2)
            fun labelFor(iters: Int): String = when (iters) {
                com.vault.srd.security.SecurityManager.PIN_ITERATIONS_MIN -> "MIN"
                com.vault.srd.security.SecurityManager.PIN_ITERATIONS_BALANCED -> "BALANCED"
                com.vault.srd.security.SecurityManager.PIN_ITERATIONS_STRONG -> "STRONG"
                else -> "EXTRA STRONG"
            }
            rows.forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEach { iters ->
                        val isSelected = pendingPinHashIterations == iters
                        OutlinedButton(
                            onClick = { pendingPinHashIterations = iters },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) Color.White else Color.Transparent,
                                contentColor = if (isSelected) Color.Black else Color.White
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(labelFor(iters), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("${iters / 1000}k", fontSize = 10.sp)
                            }
                        }
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
                if (rowIndex != rows.lastIndex) Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            val currentLabel = labelFor(pinHashIterations)
            val currentValue = "${pinHashIterations / 1000}k"
            Text("Current: $currentLabel ($currentValue)", color = Color.Gray, fontSize = 11.sp)
            if (pendingPinHashIterations != pinHashIterations) {
                val pendingLabel = labelFor(pendingPinHashIterations)
                val pendingValue = "${pendingPinHashIterations / 1000}k"
                Text("Selected: $pendingLabel ($pendingValue)", color = Color.White, fontSize = 11.sp)
            }
            Text(
                "Applies after next successful vault unlock.",
                color = Color.Gray,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    withPinHashFingerprintAuth(
                        onSuccess = {
                            pinHashAuthError = null
                            pinHashIterations = pendingPinHashIterations
                            securityManager.setPinHashIterations(pendingPinHashIterations)
                            android.widget.Toast.makeText(
                                context,
                                "PIN hash strength updated",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onError = { error ->
                            pinHashAuthError = error
                        }
                    )
                },
                enabled = pendingPinHashIterations != pinHashIterations,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text("APPLY", fontWeight = FontWeight.Bold)
            }
            pinHashAuthError?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = Color.Red, fontSize = 11.sp)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Delete originals setting
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("STORAGE HANDLING", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Delete original files after import",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (deleteOriginal) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = deleteOriginal,
                    onCheckedChange = { viewModel.setDeleteOriginalFiles(it) }
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Intruder capture setting
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("INTRUDER CAPTURE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Take a selfie after multiple failed PIN attempts.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (intruderCaptureEnabled) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = intruderCaptureEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                intruderCaptureEnabled = true
                                securityManager.setIntruderCaptureEnabled(true)
                                intruderPermissionError = null
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        } else {
                            intruderCaptureEnabled = false
                            securityManager.setIntruderCaptureEnabled(false)
                            intruderPermissionError = null
                        }
                    }
                )
            }
            if (intruderPermissionError != null) {
                Spacer(Modifier.height(4.dp))
                Text(intruderPermissionError!!, color = Color.Red, fontSize = 11.sp)
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // No-clipboard mode
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("NO CLIPBOARD MODE", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Block copy actions from the vault. Paste into vault inputs still works.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (noClipboardEnabled) "Enabled (Fingerprint Protected)" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = noClipboardEnabled,
                    onCheckedChange = { requested ->
                        withBiometricAuth(
                            onSuccess = {
                                noClipboardEnabled = requested
                                securityManager.setNoClipboardModeEnabled(requested)
                            },
                            onError = {
                                noClipboardEnabled = securityManager.isNoClipboardModeEnabled()
                            }
                        )
                    }
                )
            }
            if (noClipboardAuthError != null) {
                Spacer(Modifier.height(4.dp))
                Text(noClipboardAuthError!!, color = Color.Red, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("CLIPBOARD AUTO-CLEAR", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Automatically clear copied content after a delay.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (clipboardAutoClearEnabled) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = clipboardAutoClearEnabled,
                    onCheckedChange = { enabled ->
                        clipboardAutoClearEnabled = enabled
                        securityManager.setClipboardAutoClearEnabled(enabled)
                        if (enabled && clipboardClearDelay <= 0) {
                            val fallback = securityManager.getClipboardDelayOptionsSeconds()
                                .firstOrNull { it > 0 } ?: 30
                            clipboardClearDelay = fallback
                            securityManager.setClipboardClearDelaySeconds(fallback)
                        }
                    }
                )
            }
            if (clipboardAutoClearEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val options = securityManager.getClipboardDelayOptionsSeconds().filter { it > 0 }
                    options.forEach { option ->
                        val label = "${option}s"
                        OutlinedButton(
                            onClick = {
                                clipboardClearDelay = option
                                securityManager.setClipboardClearDelaySeconds(option)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (clipboardClearDelay == option) Color.White else Color.Transparent,
                                contentColor = if (clipboardClearDelay == option) Color.Black else Color.White
                            )
                        ) {
                            Text(label)
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Randomized PIN pad
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            var randomPinEnabled by remember { mutableStateOf(securityManager.isRandomPinPadEnabled()) }
            Text("RANDOMIZED PIN PAD", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Shuffle PIN digits on unlock to protect against shoulder surfing.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (randomPinEnabled) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = randomPinEnabled,
                    onCheckedChange = { enabled ->
                        randomPinEnabled = enabled
                        securityManager.setRandomPinPadEnabled(enabled)
                    }
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Global fingerprint unlock
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            var globalBiometricEnabled by remember {
                mutableStateOf(securityManager.isGlobalVaultBiometricEnabled())
            }
            var biometricAllVaults by remember {
                mutableStateOf(securityManager.isGlobalBiometricAllVaults())
            }
            var selectedBiometricVaultIds by remember {
                mutableStateOf(securityManager.getGlobalBiometricVaultIds())
            }
            var globalBiometricError by remember { mutableStateOf<String?>(null) }
            var isEnablingGlobalBiometric by remember { mutableStateOf(false) }
            Text("FINGERPRINT UNLOCK", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Use fingerprint to unlock any vault without entering the PIN.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (globalBiometricEnabled) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = globalBiometricEnabled,
                    enabled = !isEnablingGlobalBiometric,
                    onCheckedChange = { requested ->
                        if (requested) {
                            if (biometricManager.canAuthenticate(
                                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                        BiometricManager.Authenticators.BIOMETRIC_WEAK
                                )
                                != BiometricManager.BIOMETRIC_SUCCESS
                            ) {
                                globalBiometricError = "Fingerprint not configured on this device."
                                return@Switch
                            }
                            val activity = context as? android.app.Activity
                            if (activity == null) {
                                globalBiometricError = "Biometric authentication unavailable."
                                return@Switch
                            }
                            val cipher = securityManager.prepareGlobalVaultBiometricEnrollmentCipher()
                            if (cipher == null) {
                                globalBiometricError = "Unable to prepare biometric key."
                                return@Switch
                            }
                            isEnablingGlobalBiometric = true
                            BiometricAuth.authenticateWithCipher(
                                activity = activity,
                                title = "Enable Fingerprint Unlock",
                                subtitle = "Authenticate to enable global unlock",
                                negativeButtonText = "Cancel",
                                cipher = cipher,
                                onSuccess = { authenticatedCipher ->
                                    val ok = authenticatedCipher != null &&
                                        securityManager.finalizeGlobalVaultBiometricEnrollment(authenticatedCipher)
                                    isEnablingGlobalBiometric = false
                                    if (ok) {
                                        globalBiometricEnabled = true
                                        if (!securityManager.isGlobalBiometricAllVaults()) {
                                            biometricAllVaults = false
                                        } else {
                                            biometricAllVaults = true
                                        }
                                        globalBiometricError = null
                                    } else {
                                        globalBiometricEnabled = false
                                        globalBiometricError = "Biometric binding failed. Try again."
                                    }
                                },
                                onError = { message ->
                                    isEnablingGlobalBiometric = false
                                    globalBiometricEnabled = false
                                    globalBiometricError = message
                                }
                            )
                        } else {
                            securityManager.clearGlobalVaultBiometricBinding()
                            globalBiometricEnabled = false
                            biometricAllVaults = true
                            selectedBiometricVaultIds = emptySet()
                            globalBiometricError = null
                        }
                    }
                )
            }
            if (globalBiometricError != null) {
                Spacer(Modifier.height(4.dp))
                Text(globalBiometricError!!, color = Color.Red, fontSize = 11.sp)
            }
            if (globalBiometricEnabled) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = biometricAllVaults,
                            onClick = {
                                biometricAllVaults = true
                                securityManager.setGlobalBiometricAllVaults(true)
                            }
                        )
                        Text("ALL VAULTS", color = Color.White, fontSize = 11.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = !biometricAllVaults,
                            onClick = {
                                biometricAllVaults = false
                                securityManager.setGlobalBiometricAllVaults(false)
                            }
                        )
                        Text("SELECT VAULTS", color = Color.White, fontSize = 11.sp)
                    }
                }
                if (!biometricAllVaults) {
                    val selectableVaults = vaults.filter { !it.isDecoy }
                    LaunchedEffect(selectableVaults) {
                        val ids = selectableVaults.map { it.id }.toSet()
                        val filtered = selectedBiometricVaultIds.intersect(ids)
                        if (filtered != selectedBiometricVaultIds) {
                            selectedBiometricVaultIds = filtered
                            securityManager.setGlobalBiometricVaultIds(filtered)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val ids = selectableVaults.map { it.id }.toSet()
                                selectedBiometricVaultIds = ids
                                securityManager.setGlobalBiometricVaultIds(ids)
                            }
                        ) { Text("SELECT ALL", fontSize = 11.sp) }
                        TextButton(
                            onClick = {
                                selectedBiometricVaultIds = emptySet()
                                securityManager.setGlobalBiometricVaultIds(emptySet())
                            }
                        ) { Text("CLEAR", fontSize = 11.sp) }
                    }
                    selectableVaults.forEach { vault ->
                        val checked = selectedBiometricVaultIds.contains(vault.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(vault.name, color = Color.White, fontSize = 12.sp)
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { enabled ->
                                    val updated = if (enabled) {
                                        selectedBiometricVaultIds + vault.id
                                    } else {
                                        selectedBiometricVaultIds - vault.id
                                    }
                                    selectedBiometricVaultIds = updated
                                    securityManager.setGlobalBiometricVaultIds(updated)
                                }
                            )
                        }
                    }
                    if (selectableVaults.isNotEmpty() && selectedBiometricVaultIds.isEmpty()) {
                        Text("Select at least one vault to use fingerprint unlock.", color = Color.Red, fontSize = 10.sp)
                    }
                }
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        // Auto-wipe extreme mode
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            var autoWipeEnabled by remember { mutableStateOf(securityManager.isAutoWipeEnabled()) }
            var threshold by remember { mutableIntStateOf(securityManager.getAutoWipeThreshold()) }

            Text("AUTO-WIPE MODE (EXTREME)", color = Color.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "If failed PIN attempts exceed a limit on any vault, that vault content is wiped.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (autoWipeEnabled) "Enabled" else "Disabled",
                    color = Color.White,
                    fontSize = 12.sp
                )
                Switch(
                    checked = autoWipeEnabled,
                    onCheckedChange = { enabled ->
                        autoWipeEnabled = enabled
                        securityManager.setAutoWipeEnabled(enabled)
                        securityManager.clearAutoWipeFailedAttempts()
                    }
                )
            }

            if (autoWipeEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("Threshold: ${threshold} failed attempts", color = Color.White, fontSize = 12.sp)
                val thresholds = listOf(5, 10, 15, 20)
                var thresholdIndex by remember { mutableIntStateOf(thresholds.indexOf(threshold).coerceAtLeast(0)) }
                Slider(
                    value = thresholdIndex.toFloat(),
                    onValueChange = { raw ->
                        val idx = raw.toInt().coerceIn(0, thresholds.lastIndex)
                        thresholdIndex = idx
                        threshold = thresholds[idx]
                        securityManager.setAutoWipeThreshold(threshold)
                    },
                    valueRange = 0f..thresholds.lastIndex.toFloat(),
                    steps = thresholds.size - 2
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

        @Composable
        fun BackupSectionContent() {
            // Backup section
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            val selectedNormalVault = vaults.firstOrNull { it.id == selectedNormalVaultId }
            val selectedExtremeVault = vaults.firstOrNull { it.id == selectedExtremeVaultId }
            Text("BACKUP", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                "Select backup type.",
                color = Color.Gray,
                fontSize = 11.sp
            )
            if (!backupIdentityWarningAcknowledged) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Warning: Backups are tied to this device identity. A factory reset can make old backups unrestorable on this device.",
                    color = Color(0xFFFFB74D),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        securityManager.markBackupIdentityWarningShown()
                        backupIdentityWarningAcknowledged = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("I UNDERSTAND")
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    backupImportStatus = "Fingerprint authentication required..."
                    backupStatusIsError = false
                    withBackupFingerprintAuth(
                        title = "Copy Device Recovery Token",
                        subtitle = "Authenticate to copy the recovery token",
                        onSuccess = {
                            val token = securityManager.getDeviceFingerprintRecoveryToken()
                            if (token.isBlank()) {
                                backupImportStatus = "Could not generate recovery token on this device."
                                backupStatusIsError = true
                            } else {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("device_fingerprint_recovery_token", token))
                                val clearRequest = OneTimeWorkRequestBuilder<ClipboardClearWorker>()
                                    .setInitialDelay(30, TimeUnit.SECONDS)
                                    .setInputData(
                                        workDataOf(
                                            ClipboardClearWorker.KEY_EXPECTED_VALUE to token
                                        )
                                    )
                                    .build()
                                workManager.enqueueUniqueWork(
                                    ClipboardClearWorker.UNIQUE_WORK_NAME,
                                    ExistingWorkPolicy.REPLACE,
                                    clearRequest
                                )
                                backupImportStatus = "Device recovery token copied to clipboard."
                                backupStatusIsError = false
                            }
                        },
                        onError = { message ->
                            backupImportStatus = message
                            backupStatusIsError = true
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !backupActionBusy && !restoreActionBusy
            ) {
                Text("COPY DEVICE RECOVERY TOKEN")
            }
            Spacer(Modifier.height(8.dp))
            val notificationsReady = notificationsPermissionGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            Text(
                if (notificationsReady) {
                    "Background progress notifications: enabled."
                } else {
                    "Background progress notifications: disabled. Enable for create/restore logs."
                },
                color = if (notificationsReady) Color.White.copy(alpha = 0.75f) else Color(0xFFFFB74D),
                fontSize = 11.sp
            )
            if (!notificationsReady) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        backupStatusIsError = false
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("ALLOW BACKGROUND NOTIFICATIONS")
                }
            }
            Spacer(Modifier.height(8.dp))

            val backupOptions = listOf("EXTREME", "NORMAL", "FULL")
            backupOptions.forEach { option ->
                OutlinedButton(
                    onClick = { selectedBackupOption = option },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (selectedBackupOption == option) Color.White else Color.Transparent,
                        contentColor = if (selectedBackupOption == option) Color.Black else Color.White
                    )
                ) {
                    Text(option)
                }
                Spacer(Modifier.height(6.dp))
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (backupHistoryLoading) return@launch
                        showBackupHistoryDialog = true
                        backupHistoryLoading = true
                        backupHistoryError = null
                        val history = withContext(Dispatchers.IO) {
                            runCatching { backupManager.listBackupHistory() }.getOrElse { emptyList() }
                        }
                        backupHistoryEntries = history
                        if (history.isEmpty()) {
                            backupHistoryError = "No backups found."
                        }
                        backupHistoryLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !backupActionBusy && !restoreActionBusy
            ) {
                Text("BACKUP HISTORY")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        if (vaultHealthLoading) return@launch
                        showVaultHealthDialog = true
                        vaultHealthLoading = true
                        vaultHealthError = null
                        val result = runCatching {
                            val history = withContext(Dispatchers.IO) { backupManager.listBackupHistory() }
                            val decryptedItems = withContext(Dispatchers.IO) {
                                viewModel.repository.getDecryptedItemsOnce()
                            }
                            val healthyHistory = history.filter { it.isHealthy }
                            val hasFullCoverage = healthyHistory.any { it.scope == BackupScope.ENTIRE_APP }
                            val coveredVaultIds = healthyHistory
                                .asSequence()
                                .filter { it.scope == BackupScope.SINGLE_VAULT }
                                .mapNotNull { it.targetVaultId }
                                .toSet()
                            val missingBackupVaults = if (hasFullCoverage) {
                                emptyList()
                            } else {
                                vaults.filter { !coveredVaultIds.contains(it.id) }
                            }
                            evaluateVaultHealth(
                                items = decryptedItems,
                                vaultsWithoutBackup = missingBackupVaults
                            )
                        }
                        if (result.isSuccess) {
                            vaultHealth = result.getOrNull()
                            vaultHealthError = null
                        } else {
                            vaultHealthError = "Unable to load vault health. Try again."
                        }
                        vaultHealthLoading = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !backupActionBusy && !restoreActionBusy
            ) {
                Text("VAULT HEALTH")
            }
            Spacer(Modifier.height(8.dp))

            if (selectedBackupOption == "NORMAL") {
                Spacer(Modifier.height(4.dp))
                Text("NORMAL BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "Create a single-vault backup that can be restored on any Android device using this app, your vault PIN, and your master backup key.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = { showNormalVaultPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vaults.isNotEmpty() && !backupActionBusy && !restoreActionBusy
                ) {
                    Text(
                        selectedNormalVault?.let { "VAULT: ${it.name}" } ?: "SELECT VAULT",
                        color = if (vaults.isEmpty()) Color.Gray else Color.White
                    )
                }

                if (showNormalVaultPicker) {
                    AlertDialog(
                        onDismissRequest = { showNormalVaultPicker = false },
                        title = { Text("SELECT VAULT", color = Color.White) },
                        text = {
                            if (vaults.isEmpty()) {
                                Text("No vaults available.", color = Color.Gray)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    vaults.forEach { vault ->
                                        val selected = selectedNormalVaultId == vault.id
                                        OutlinedButton(
                                            onClick = {
                                                selectedNormalVaultId = vault.id
                                                showNormalVaultPicker = false
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (selected) Color.White else Color.Transparent,
                                                contentColor = if (selected) Color.Black else Color.White
                                            )
                                        ) {
                                            Text(vault.name)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showNormalVaultPicker = false }) {
                                Text("CLOSE", color = Color.White)
                            }
                        },
                        containerColor = Color.Black
                    )
                }

                Spacer(Modifier.height(8.dp))
                TextField(
                    value = normalVaultPin,
                    onValueChange = { normalVaultPin = it.take(32) },
                    label = { Text("VAULT PIN (FOR SELECTED VAULT)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showNormalVaultPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNormalVaultPin = !showNormalVaultPin }) {
                            Icon(
                                imageVector = if (showNormalVaultPin) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = normalMasterKey,
                    onValueChange = { normalMasterKey = it.take(20) },
                    label = { Text("MASTER BACKUP KEY (8-20 CHAR)") },
                    visualTransformation = if (showNormalMasterKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showNormalMasterKey = !showNormalMasterKey }) {
                            Icon(
                                imageVector = if (showNormalMasterKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Normal backup is saved to Downloads/Vault/Backups/Normal Backup as <vault_name>_backup.vltbck",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    "For best stability stay on this screen while creating backup. If app is minimized, backup still continues in background.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!backupIdentityWarningAcknowledged) {
                            backupImportStatus = "Please acknowledge the device-identity warning first."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        val vault = selectedNormalVault
                        if (vault == null) {
                            backupImportStatus = "Select a vault first."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (normalVaultPin.isBlank() || normalMasterKey.isBlank()) {
                            backupImportStatus = "Enter both vault PIN and master backup key."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (normalMasterKey.length !in 8..20) {
                            backupImportStatus = "Master backup key must be 8 to 20 characters."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (!securityManager.verifyPin(normalVaultPin, vault.pinHash, vault.pinSalt)) {
                            backupImportStatus = "Vault PIN does not match the selected vault."
                            backupStatusIsError = true
                            return@Button
                        }
                        backupActionBusy = true
                        backupStartedAtMs = startedAt
                        backupStatusIsError = false
                        backupImportStatus = "Scheduling backup..."
                        scope.launch {
                            try {
                                val encryptedPin = securityManager.encrypt(normalVaultPin)
                                val encryptedMaster = securityManager.encrypt(normalMasterKey)
                                val request = OneTimeWorkRequestBuilder<ManualNormalBackupWorker>()
                                    .setInputData(
                                        workDataOf(
                                            ManualNormalBackupWorker.KEY_VAULT_ID to vault.id,
                                            ManualNormalBackupWorker.KEY_ENCRYPTED_VAULT_PIN to encryptedPin,
                                            ManualNormalBackupWorker.KEY_ENCRYPTED_MASTER_KEY to encryptedMaster
                                        )
                                    )
                                    .build()
                                workManager.enqueueUniqueWork(
                                    ManualNormalBackupWorker.UNIQUE_WORK_NAME,
                                    ExistingWorkPolicy.REPLACE,
                                    request
                                )
                                monitorManualBackup(request.id, startedAt)
                            } catch (e: Exception) {
                                backupImportStatus = e.message ?: "Failed to create backup."
                                backupStatusIsError = true
                                backupActionBusy = false
                                backupStartedAtMs = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy && backupIdentityWarningAcknowledged
                ) {
                    Text(if (backupActionBusy) "CREATING..." else "CREATE NORMAL BACKUP")
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))
                Text("IMPORT NORMAL BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importBackupLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("SELECT .VLTBCK FILE")
                }
                if (selectedBackupFileName != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Selected file: ${selectedBackupFileName ?: ""}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            val uri = backupImportUri
                            if (uri == null) {
                                backupImportStatus = "Select a .vltbck file first."
                                backupStatusIsError = true
                            } else {
                                scope.launch {
                                    val report = backupManager.verifyBackup(uri)
                                    backupStatusIsError = !report.isValid
                                    backupImportStatus = if (report.isValid) {
                                        buildString {
                                            append("Verify passed")
                                            report.fileVersion?.let { append(" | v$it") }
                                            report.mode?.let { append(" | ${it.name}") }
                                            report.scope?.let { append(" | ${it.name}") }
                                            report.createdAt?.let {
                                                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                    .format(java.util.Date(it))
                                                append(" | created $stamp")
                                            }
                                            report.estimatedVaultCount?.let { append(" | vaults ~$it") }
                                            append(if (report.integrityPassed) " | integrity PASS" else " | integrity FAIL")
                                        }
                                    } else {
                                        "Verify failed: ${report.message}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !backupActionBusy && !restoreActionBusy
                    ) {
                        Text("VERIFY BACKUP")
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = restoreVaultPin,
                    onValueChange = { restoreVaultPin = it.take(32) },
                    label = { Text("RESTORE VAULT PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = if (showRestoreVaultPin) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showRestoreVaultPin = !showRestoreVaultPin }) {
                            Icon(
                                imageVector = if (showRestoreVaultPin) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = restoreMasterKey,
                    onValueChange = { restoreMasterKey = it.take(20) },
                    label = { Text("RESTORE MASTER KEY (8-20 CHAR)") },
                    visualTransformation = if (showRestoreMasterKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showRestoreMasterKey = !showRestoreMasterKey }) {
                            Icon(
                                imageVector = if (showRestoreMasterKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!backupIdentityWarningAcknowledged) {
                            backupImportStatus = "Please acknowledge the device-identity warning first."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        val uri = backupImportUri
                        val descriptor = selectedBackupDescriptor
                        if (uri == null) {
                            backupImportStatus = "Select a normal .vltbck file first."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (descriptor == null || descriptor.mode != BackupMode.NORMAL || descriptor.scope != BackupScope.SINGLE_VAULT) {
                            backupImportStatus = "Select a NORMAL single-vault backup file."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (restoreVaultPin.isBlank() || restoreMasterKey.isBlank()) {
                            backupImportStatus = "Enter vault PIN and master backup key to restore."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (restoreMasterKey.length !in 8..20) {
                            backupImportStatus = "Master backup key must be 8 to 20 characters."
                            backupStatusIsError = true
                            return@Button
                        }
                        restoreActionBusy = true
                        restoreStartedAtMs = startedAt
                        backupStatusIsError = false
                        backupImportStatus = "Scheduling restore..."
                        scope.launch {
                            try {
                                val encryptedPin = securityManager.encrypt(restoreVaultPin)
                                val encryptedMaster = securityManager.encrypt(restoreMasterKey)
                                val request = OneTimeWorkRequestBuilder<ManualRestoreBackupWorker>()
                                    .setInputData(
                                        workDataOf(
                                            ManualRestoreBackupWorker.KEY_URI to uri.toString(),
                                            ManualRestoreBackupWorker.KEY_MODE to BackupMode.NORMAL.name,
                                            ManualRestoreBackupWorker.KEY_ENCRYPTED_VAULT_PIN to encryptedPin,
                                            ManualRestoreBackupWorker.KEY_ENCRYPTED_MASTER_KEY to encryptedMaster
                                        )
                                    )
                                    .build()
                                workManager.enqueueUniqueWork(
                                    ManualRestoreBackupWorker.UNIQUE_WORK_NAME,
                                    ExistingWorkPolicy.REPLACE,
                                    request
                                )
                                monitorRestoreWork(request.id, startedAt)
                            } catch (e: Exception) {
                                backupImportStatus = e.message ?: "Failed to restore backup."
                                backupStatusIsError = true
                                restoreActionBusy = false
                                restoreStartedAtMs = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text(if (restoreActionBusy) "RESTORING..." else "RESTORE NORMAL BACKUP")
                }

                BackupStatusMessage(
                    status = backupImportStatus,
                    isError = backupStatusIsError
                )
            } else if (selectedBackupOption == "EXTREME") {
                Spacer(Modifier.height(4.dp))
                Text("EXTREME BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "Extreme backup is device-locked. It can be restored only on this same device with fingerprint authentication.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showExtremeVaultPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vaults.isNotEmpty() && !backupActionBusy && !restoreActionBusy
                ) {
                    Text(
                        selectedExtremeVault?.let { "VAULT: ${it.name}" } ?: "SELECT VAULT",
                        color = if (vaults.isEmpty()) Color.Gray else Color.White
                    )
                }
                if (showExtremeVaultPicker) {
                    AlertDialog(
                        onDismissRequest = { showExtremeVaultPicker = false },
                        title = { Text("SELECT VAULT", color = Color.White) },
                        text = {
                            if (vaults.isEmpty()) {
                                Text("No vaults available.", color = Color.Gray)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    vaults.forEach { vault ->
                                        val selected = selectedExtremeVaultId == vault.id
                                        OutlinedButton(
                                            onClick = {
                                                selectedExtremeVaultId = vault.id
                                                showExtremeVaultPicker = false
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                containerColor = if (selected) Color.White else Color.Transparent,
                                                contentColor = if (selected) Color.Black else Color.White
                                            )
                                        ) {
                                            Text(vault.name)
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showExtremeVaultPicker = false }) {
                                Text("CLOSE", color = Color.White)
                            }
                        },
                        containerColor = Color.Black
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "If fingerprint is removed/changed or device is reset/replaced, this backup becomes unrecoverable.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        if (selectedExtremeVault == null) {
                            backupImportStatus = "Select a vault for extreme backup."
                            backupStatusIsError = true
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        backupActionBusy = true
                        backupStartedAtMs = startedAt
                        backupStatusIsError = false
                        backupImportStatus = "Fingerprint authentication required..."
                        withBackupFingerprintAuth(
                            title = "Create Extreme Backup",
                            subtitle = "Authenticate to create device-locked vault backup",
                            onSuccess = {
                                scope.launch {
                                    try {
                                        val request = OneTimeWorkRequestBuilder<ManualExtremeBackupWorker>()
                                            .setInputData(
                                                workDataOf(
                                                    ManualExtremeBackupWorker.KEY_VAULT_ID to (selectedExtremeVault?.id ?: -1)
                                                )
                                            )
                                            .build()
                                        workManager.enqueueUniqueWork(
                                            ManualExtremeBackupWorker.UNIQUE_WORK_NAME,
                                            ExistingWorkPolicy.REPLACE,
                                            request
                                        )
                                        monitorManualBackup(request.id, startedAt)
                                    } catch (e: Exception) {
                                        backupImportStatus = e.message ?: "Failed to create extreme backup."
                                        backupStatusIsError = true
                                        backupActionBusy = false
                                        backupStartedAtMs = null
                                    }
                                }
                            },
                            onError = { message ->
                                backupImportStatus = message
                                backupStatusIsError = true
                                backupActionBusy = false
                                backupStartedAtMs = null
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy && backupIdentityWarningAcknowledged
                ) {
                    Text(if (backupActionBusy) "CREATING..." else "CREATE EXTREME BACKUP")
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))
                Text("IMPORT EXTREME BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importBackupLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("SELECT .VLTBCK FILE")
                }
                if (selectedBackupFileName != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Selected file: ${selectedBackupFileName ?: ""}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            val uri = backupImportUri
                            if (uri == null) {
                                backupImportStatus = "Select a .vltbck file first."
                                backupStatusIsError = true
                            } else {
                                scope.launch {
                                    val report = backupManager.verifyBackup(uri)
                                    backupStatusIsError = !report.isValid
                                    backupImportStatus = if (report.isValid) {
                                        buildString {
                                            append("Verify passed")
                                            report.fileVersion?.let { append(" | v$it") }
                                            report.mode?.let { append(" | ${it.name}") }
                                            report.scope?.let { append(" | ${it.name}") }
                                            report.createdAt?.let {
                                                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                                    .format(java.util.Date(it))
                                                append(" | created $stamp")
                                            }
                                            report.estimatedVaultCount?.let { append(" | vaults ~$it") }
                                            append(if (report.integrityPassed) " | integrity PASS" else " | integrity FAIL")
                                        }
                                    } else {
                                        "Verify failed: ${report.message}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !backupActionBusy && !restoreActionBusy
                    ) {
                        Text("VERIFY BACKUP")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!backupIdentityWarningAcknowledged) {
                            backupImportStatus = "Please acknowledge the device-identity warning first."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        val uri = backupImportUri
                        val descriptor = selectedBackupDescriptor
                        if (uri == null || descriptor == null || descriptor.mode != BackupMode.EXTREME) {
                            backupImportStatus = "Select an EXTREME backup file first."
                            backupStatusIsError = true
                            return@Button
                        }
                        restoreActionBusy = true
                        restoreStartedAtMs = startedAt
                        backupStatusIsError = false
                        backupImportStatus = "Fingerprint authentication required..."
                        withBackupFingerprintAuth(
                            title = "Restore Extreme Backup",
                            subtitle = "Authenticate with fingerprint to restore",
                            onSuccess = {
                                scope.launch {
                                    try {
                                        backupImportStatus = "Scheduling restore..."
                                        val request = OneTimeWorkRequestBuilder<ManualRestoreBackupWorker>()
                                            .setInputData(
                                                workDataOf(
                                                    ManualRestoreBackupWorker.KEY_URI to uri.toString(),
                                                    ManualRestoreBackupWorker.KEY_MODE to BackupMode.EXTREME.name,
                                                    ManualRestoreBackupWorker.KEY_BIOMETRIC_CONFIRMED to true
                                                )
                                            )
                                            .build()
                                        workManager.enqueueUniqueWork(
                                            ManualRestoreBackupWorker.UNIQUE_WORK_NAME,
                                            ExistingWorkPolicy.REPLACE,
                                            request
                                        )
                                        monitorRestoreWork(request.id, startedAt)
                                    } catch (e: Exception) {
                                        backupImportStatus = e.message ?: "Failed to restore backup."
                                        backupStatusIsError = true
                                        restoreActionBusy = false
                                        restoreStartedAtMs = null
                                    }
                                }
                            },
                            onError = { message ->
                                backupImportStatus = message
                                backupStatusIsError = true
                                restoreActionBusy = false
                                restoreStartedAtMs = null
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text(if (restoreActionBusy) "RESTORING..." else "RESTORE EXTREME BACKUP")
                }

                BackupStatusMessage(
                    status = backupImportStatus,
                    isError = backupStatusIsError
                )
            } else if (selectedBackupOption == "FULL") {
                Spacer(Modifier.height(4.dp))
                Text("FULL BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    "Back up the entire app (all vaults, settings, and intruder captures) into one package.",
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                TextField(
                    value = fullMasterKey,
                    onValueChange = { fullMasterKey = it.take(20) },
                    label = { Text("MASTER KEY (8-20 CHAR)") },
                    visualTransformation = if (showFullMasterKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showFullMasterKey = !showFullMasterKey }) {
                            Icon(
                                imageVector = if (showFullMasterKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = fullGeneratedKey,
                    onValueChange = { fullGeneratedKey = it.take(30) },
                    label = { Text("GENERATED KEY (20-30 CHAR: A-Z, 0-9, #, *)") },
                    visualTransformation = if (showFullGeneratedKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showFullGeneratedKey = !showFullGeneratedKey }) {
                            Icon(
                                imageVector = if (showFullGeneratedKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { fullGeneratedKey = backupManager.generateFullBackupGeneratedKey() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("GENERATE RANDOM KEY")
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = fullRecoveryPhrase,
                    onValueChange = { fullRecoveryPhrase = it },
                    label = { Text("RECOVERY PHRASE (MIN 12 WORDS)") },
                    visualTransformation = if (showFullRecoveryPhrase) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showFullRecoveryPhrase = !showFullRecoveryPhrase }) {
                            Icon(
                                imageVector = if (showFullRecoveryPhrase) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = Color.Gray
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy,
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.1f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f)
                    )
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { fullRecoveryPhrase = RecoveryPhrase.generate(12) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("GENERATE 12-WORD PHRASE")
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Text(
                    "EXTREME ZIP (FINGERPRINT-LOCKED)",
                    color = Color.White,
                    fontSize = 11.sp
                )
                    Switch(
                        checked = fullExtremeZip,
                        onCheckedChange = { fullExtremeZip = it },
                        enabled = !backupActionBusy && !restoreActionBusy
                    )
                }
                Text(
                    "Normal full backup creates backup.vltbck + key.vltk inside Downloads/Vault/Backups/Full Backup/Full Backup N. Extreme ZIP creates an encrypted zip.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        val phraseWordCount = fullRecoveryPhrase
                            .trim()
                            .split(Regex("\\s+"))
                            .filter { it.isNotBlank() }
                            .size
                        val generatedKeyRegex = Regex("^[A-Za-z0-9#*]{20,30}$")
                        if (fullMasterKey.length !in 8..20) {
                            backupImportStatus = "Master key must be 8 to 20 characters."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (!generatedKeyRegex.matches(fullGeneratedKey)) {
                            backupImportStatus = "Generated key must be 20-30 chars with letters, numbers, #, *."
                            backupStatusIsError = true
                            return@Button
                        }
                        if (phraseWordCount < 12) {
                            backupImportStatus = "Recovery phrase must contain at least 12 words."
                            backupStatusIsError = true
                            return@Button
                        }

                        val enqueueFullBackup: () -> Unit = {
                            backupActionBusy = true
                            backupStartedAtMs = startedAt
                            backupStatusIsError = false
                            backupImportStatus = "Scheduling full backup..."
                            showFullBackupNoticeDialog = true
                            scope.launch {
                                try {
                                    val encryptedMaster = securityManager.encrypt(fullMasterKey)
                                    val encryptedGenerated = securityManager.encrypt(fullGeneratedKey)
                                    val encryptedPhrase = securityManager.encrypt(fullRecoveryPhrase)
                                    val request = OneTimeWorkRequestBuilder<ManualFullBackupWorker>()
                                        .setInputData(
                                            workDataOf(
                                                ManualFullBackupWorker.KEY_ENCRYPTED_MASTER_KEY to encryptedMaster,
                                                ManualFullBackupWorker.KEY_ENCRYPTED_GENERATED_KEY to encryptedGenerated,
                                                ManualFullBackupWorker.KEY_ENCRYPTED_PHRASE to encryptedPhrase,
                                                ManualFullBackupWorker.KEY_EXTREME_ZIP to fullExtremeZip
                                            )
                                        )
                                        .build()
                                    workManager.enqueueUniqueWork(
                                        ManualFullBackupWorker.UNIQUE_WORK_NAME,
                                        ExistingWorkPolicy.REPLACE,
                                        request
                                    )
                                    monitorManualBackup(request.id, startedAt)
                                } catch (e: Exception) {
                                    backupImportStatus = e.message ?: "Failed to create full backup."
                                    backupStatusIsError = true
                                    backupActionBusy = false
                                    backupStartedAtMs = null
                                }
                            }
                        }

                        if (fullExtremeZip) {
                            backupImportStatus = "Fingerprint authentication required..."
                            withBackupFingerprintAuth(
                                title = "Create Extreme Full Backup",
                                subtitle = "Authenticate to lock full backup package to this device",
                                onSuccess = { enqueueFullBackup() },
                                onError = { message ->
                                    backupImportStatus = message
                                    backupStatusIsError = true
                                    backupActionBusy = false
                                    backupStartedAtMs = null
                                }
                            )
                        } else {
                            enqueueFullBackup()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy && backupIdentityWarningAcknowledged
                ) {
                    Text(if (backupActionBusy) "CREATING..." else "CREATE FULL BACKUP")
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                Spacer(Modifier.height(10.dp))
                Text("IMPORT FULL BACKUP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importFullBackupLauncher.launch(arrayOf("application/zip", "*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("SELECT FULL BACKUP ZIP (EXTREME)")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { importFullBackupFileLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("SELECT FULL BACKUP FILE (.VLTBCK)")
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { importFullBackupKeyLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text("SELECT KEY FILE (.VLTK)")
                }
                if (selectedFullBackupFileName != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Backup file: ${selectedFullBackupFileName ?: ""}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                if (selectedFullBackupKeyFileName != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Key file: ${selectedFullBackupKeyFileName ?: ""}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (!requireNotificationPermissionForBackgroundWork()) {
                            return@Button
                        }
                        val startedAt = System.currentTimeMillis()
                        val uri = fullBackupImportUri
                        val descriptor = selectedFullBackupDescriptor
                        val keyUri = fullBackupKeyImportUri
                        if (descriptor == null && (uri == null || keyUri == null)) {
                            backupImportStatus = "Select a full backup ZIP or a .vltbck file with its key.vltk."
                            backupStatusIsError = true
                            return@Button
                        }

                        val enqueueFullRestore: (Boolean) -> Unit = { biometricConfirmed ->
                            restoreActionBusy = true
                            restoreStartedAtMs = startedAt
                            backupStatusIsError = false
                            backupImportStatus = "Scheduling full backup restore..."
                            scope.launch {
                                try {
                                    val request = OneTimeWorkRequestBuilder<ManualFullRestoreBackupWorker>()
                                        .setInputData(
                                            workDataOf(
                                                ManualFullRestoreBackupWorker.KEY_URI to (uri?.toString() ?: ""),
                                                ManualFullRestoreBackupWorker.KEY_KEY_URI to (keyUri?.toString() ?: ""),
                                                ManualFullRestoreBackupWorker.KEY_BIOMETRIC_CONFIRMED to biometricConfirmed
                                            )
                                        )
                                        .build()
                                    workManager.enqueueUniqueWork(
                                        ManualFullRestoreBackupWorker.UNIQUE_WORK_NAME,
                                        ExistingWorkPolicy.REPLACE,
                                        request
                                    )
                                    monitorRestoreWork(request.id, startedAt)
                                } catch (e: Exception) {
                                    backupImportStatus = e.message ?: "Failed to restore full backup package."
                                    backupStatusIsError = true
                                    restoreActionBusy = false
                                    restoreStartedAtMs = null
                                }
                            }
                        }

                        if (descriptor?.extremeZip == true) {
                            backupImportStatus = "Fingerprint authentication required..."
                            withBackupFingerprintAuth(
                                title = "Restore Extreme Full Backup",
                                subtitle = "Authenticate with fingerprint to restore package",
                                onSuccess = { enqueueFullRestore(true) },
                                onError = { message ->
                                    backupImportStatus = message
                                    backupStatusIsError = true
                                    restoreActionBusy = false
                                    restoreStartedAtMs = null
                                }
                            )
                        } else {
                            enqueueFullRestore(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !backupActionBusy && !restoreActionBusy
                ) {
                    Text(if (restoreActionBusy) "RESTORING..." else "RESTORE FULL BACKUP")
                }

                BackupStatusMessage(
                    status = backupImportStatus,
                    isError = backupStatusIsError
                )

                if (showFullBackupNoticeDialog) {
                    AlertDialog(
                        onDismissRequest = { showFullBackupNoticeDialog = false },
                        title = { Text("FULL BACKUP NOTE", color = Color.White) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "If your full backup is larger than 10 GB, storage handling can temporarily use up to 2x space. Consider enabling Storage Handling or deleting old backups.",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                                Text(
                                    "Full backups are fastest without fingerprint ZIP. Fingerprint ZIP adds stronger protection but takes longer.",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showFullBackupNoticeDialog = false }) {
                                Text("OK")
                            }
                        },
                        containerColor = Color(0xFF0F0F0F)
                    )
                }
            }
        }
        }

        BackupSectionContent()

        if (showBackupHistoryDialog) {
            AlertDialog(
                onDismissRequest = { showBackupHistoryDialog = false },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("BACKUP HISTORY", color = Color.White) },
                text = {
                    when {
                        backupHistoryLoading -> {
                            Text("Loading backup history...", color = Color.Gray)
                        }
                        backupHistoryError != null -> {
                            Text(backupHistoryError!!, color = Color.Gray)
                        }
                        backupHistoryEntries.isEmpty() -> {
                            Text("No backups found.", color = Color.Gray)
                        }
                        else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(backupHistoryEntries, key = { it.file.absolutePath }) { entry ->
                                val sizeMb = entry.sizeBytes / (1024 * 1024)
                                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(java.util.Date(entry.createdAt))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.White.copy(alpha = 0.06f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(entry.file.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    Text(
                                        "${entry.mode.name} | ${entry.scope.name} | ${sizeMb} MB | $stamp",
                                        color = Color.Gray,
                                        fontSize = 10.sp
                                    )
                                    Text(
                                        if (entry.isHealthy) "Integrity: PASS" else "Integrity: FAIL",
                                        color = if (entry.isHealthy) Color(0xFF66BB6A) else Color(0xFFEF5350),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                selectedBackupHistoryEntry = entry
                                                showBackupHistoryEntryDetails = true
                                            }
                                        ) {
                                            Text("DETAILS", fontSize = 10.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                selectedBackupFileName = entry.file.name
                                                backupImportUri = Uri.fromFile(entry.file)
                                                scope.launch {
                                                    selectedBackupDescriptor = runCatching {
                                                        backupManager.readBackupDescriptor(backupImportUri!!)
                                                    }.getOrNull()
                                                }
                                                backupImportStatus = "Selected from history: ${entry.file.name}"
                                                backupStatusIsError = false
                                                showBackupHistoryDialog = false
                                            }
                                        ) {
                                            Text("USE", fontSize = 10.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    val deleted = withContext(Dispatchers.IO) {
                                                        runCatching { backupManager.deleteBackupEntry(entry.file) }.getOrDefault(false)
                                                    }
                                                    if (deleted) {
                                                        backupHistoryEntries = withContext(Dispatchers.IO) {
                                                            runCatching { backupManager.listBackupHistory() }.getOrElse { emptyList() }
                                                        }
                                                    } else {
                                                        backupImportStatus = "Unable to delete backup entry."
                                                        backupStatusIsError = true
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("DELETE", fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showBackupHistoryDialog = false }) {
                        Text("CLOSE", color = Color.White)
                    }
                },
                containerColor = Color.Black
            )
        }

        if (showBackupHistoryEntryDetails && selectedBackupHistoryEntry != null) {
            val entry = selectedBackupHistoryEntry!!
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(java.util.Date(entry.createdAt))
            val sizeMb = entry.sizeBytes / (1024 * 1024)
            AlertDialog(
                onDismissRequest = { showBackupHistoryEntryDetails = false },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("BACKUP DETAILS", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("File: ${entry.file.name}", color = Color.White, fontSize = 11.sp)
                        Text("Mode: ${entry.mode.name}", color = Color.Gray, fontSize = 11.sp)
                        Text("Scope: ${entry.scope.name}", color = Color.Gray, fontSize = 11.sp)
                        Text("Created: $stamp", color = Color.Gray, fontSize = 11.sp)
                        Text("Size: ${sizeMb} MB", color = Color.Gray, fontSize = 11.sp)
                        if (entry.targetVaultId != null) {
                            Text("Target vault id: ${entry.targetVaultId}", color = Color.Gray, fontSize = 11.sp)
                        }
                        Text(
                            if (entry.isHealthy) "Integrity: PASS" else "Integrity: FAIL",
                            color = if (entry.isHealthy) Color(0xFF66BB6A) else Color(0xFFEF5350),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                selectedBackupFileName = entry.file.name
                                backupImportUri = Uri.fromFile(entry.file)
                                scope.launch {
                                    selectedBackupDescriptor = runCatching {
                                        backupManager.readBackupDescriptor(backupImportUri!!)
                                    }.getOrNull()
                                }
                                backupImportStatus = "Selected from history: ${entry.file.name}"
                                backupStatusIsError = false
                                showBackupHistoryEntryDetails = false
                                showBackupHistoryDialog = false
                            }
                        ) {
                            Text("USE", fontSize = 10.sp)
                        }
                        TextButton(onClick = { showBackupHistoryEntryDetails = false }) {
                            Text("CLOSE", color = Color.White)
                        }
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val deleted = withContext(Dispatchers.IO) {
                                    runCatching { backupManager.deleteBackupEntry(entry.file) }.getOrDefault(false)
                                }
                                if (deleted) {
                                    backupHistoryEntries = withContext(Dispatchers.IO) {
                                        runCatching { backupManager.listBackupHistory() }.getOrElse { emptyList() }
                                    }
                                    selectedBackupHistoryEntry = null
                                    showBackupHistoryEntryDetails = false
                                } else {
                                    backupImportStatus = "Unable to delete backup entry."
                                    backupStatusIsError = true
                                }
                            }
                        }
                    ) {
                        Text("DELETE", fontSize = 10.sp)
                    }
                },
                containerColor = Color.Black
            )
        }

        if (showVaultHealthDialog) {
            val summary = vaultHealth
            AlertDialog(
                onDismissRequest = { showVaultHealthDialog = false },
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("VAULT HEALTH", color = Color.White) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            vaultHealthLoading -> {
                                Text("Loading vault health...", color = Color.Gray)
                            }
                            vaultHealthError != null -> {
                                Text(vaultHealthError!!, color = Color.Gray)
                            }
                            summary == null -> {
                                Text("No vault health data available.", color = Color.Gray)
                            }
                            else -> {
                                val safeSummary = summary!!
                                OutlinedButton(
                                    onClick = {
                                        selectedHealthCategory = VaultHealthCategory.DUPLICATE_PASSWORDS
                                        showVaultHealthDetailsDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Duplicate passwords: ${safeSummary.duplicatePasswordItems.size}")
                                }
                                OutlinedButton(
                                    onClick = {
                                        selectedHealthCategory = VaultHealthCategory.WEAK_PASSWORDS
                                        showVaultHealthDetailsDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Weak passwords: ${safeSummary.weakPasswordItems.size}")
                                }
                                OutlinedButton(
                                    onClick = {
                                        selectedHealthCategory = VaultHealthCategory.OLD_ITEMS
                                        showVaultHealthDetailsDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Items older than 365 days: ${safeSummary.oldItems.size}")
                                }
                                OutlinedButton(
                                    onClick = {
                                        selectedHealthCategory = VaultHealthCategory.MISSING_BACKUP
                                        showVaultHealthDetailsDialog = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Vaults with no backup: ${safeSummary.vaultsWithoutBackup.size}")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVaultHealthDialog = false }) {
                        Text("CLOSE", color = Color.White)
                    }
                },
                containerColor = Color.Black
            )
        }

        if (showVaultHealthDetailsDialog && selectedHealthCategory != null && vaultHealth != null) {
            val summary = vaultHealth!!
            val category = selectedHealthCategory!!
            val title = when (category) {
                VaultHealthCategory.DUPLICATE_PASSWORDS -> "DUPLICATE PASSWORDS"
                VaultHealthCategory.WEAK_PASSWORDS -> "WEAK PASSWORDS"
                VaultHealthCategory.OLD_ITEMS -> "OLD ITEMS"
                VaultHealthCategory.MISSING_BACKUP -> "VAULTS WITHOUT BACKUP"
            }
            AlertDialog(
                onDismissRequest = { showVaultHealthDetailsDialog = false },
                title = { Text(title, color = Color.White) },
                text = {
                    when (category) {
                        VaultHealthCategory.DUPLICATE_PASSWORDS -> {
                            if (summary.duplicatePasswordItems.isEmpty()) {
                                Text("No duplicate passwords found.", color = Color.Gray)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(summary.duplicatePasswordItems.distinctBy { it.id }, key = { it.id }) { item ->
                                        Text("- ${item.name} (Vault ${item.vaultId})", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        VaultHealthCategory.WEAK_PASSWORDS -> {
                            if (summary.weakPasswordItems.isEmpty()) {
                                Text("No weak passwords found.", color = Color.Gray)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(summary.weakPasswordItems.distinctBy { it.id }, key = { it.id }) { item ->
                                        Text("- ${item.name} (Vault ${item.vaultId})", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        VaultHealthCategory.OLD_ITEMS -> {
                            if (summary.oldItems.isEmpty()) {
                                Text("No old items found.", color = Color.Gray)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(summary.oldItems.distinctBy { it.id }, key = { it.id }) { item ->
                                        Text("- ${item.name} (Vault ${item.vaultId})", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        VaultHealthCategory.MISSING_BACKUP -> {
                            if (summary.vaultsWithoutBackup.isEmpty()) {
                                Text("Every vault has backup coverage.", color = Color.Gray)
                            } else {
                                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    items(summary.vaultsWithoutBackup.distinctBy { it.id }, key = { it.id }) { vault ->
                                        Text("- ${vault.name}", color = Color.White, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showVaultHealthDetailsDialog = false }) {
                        Text("CLOSE", color = Color.White)
                    }
                },
                containerColor = Color.Black
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

private data class TransferInfo(
    val speedPerSecond: String?,
    val eta: String?
)

@Composable
private fun BackupStatusMessage(
    status: String?,
    isError: Boolean
) {
    val safeStatus = status ?: return
    val transfer = remember(safeStatus) { parseTransferInfo(safeStatus) }
    Spacer(Modifier.height(6.dp))
    Text(
        safeStatus,
        color = if (isError) Color.Red else Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp
    )
    if (transfer?.speedPerSecond != null || transfer?.eta != null) {
        Spacer(Modifier.height(4.dp))
        transfer.speedPerSecond?.let { speed ->
            Text(
                "Speed: $speed",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        transfer.eta?.let { eta ->
            Text(
                "ETA: $eta",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun parseTransferInfo(status: String): TransferInfo {
    val speedMatch = Regex("""\|\s*([^|]*?/s)""").find(status)
    val speed = speedMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    val etaMatch = Regex("""ETA\s*([0-9]{2}:[0-9]{2}:[0-9]{2})""").find(status)
    val eta = etaMatch?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    return TransferInfo(
        speedPerSecond = speed,
        eta = eta
    )
}

private fun formatOperationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format("%02d:%02d:%02d", hours, minutes, seconds)
}

private fun hasNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun resolveDocumentName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
    }.getOrNull()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VaultListItem(vault: Vault, onClick: (Vault) -> Unit, onLongClick: (Vault) -> Unit) {
    val dotColor = vault.colorHex?.let { parseVaultColor(it) } ?: Color.White.copy(alpha = 0.35f)
    ListItem(
        headlineContent = {
            Text(
                vault.name.uppercase(),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "Vault name: ${'$'}{vault.name}" }
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(dotColor, CircleShape)
            )
        },
        supportingContent = {
            if (!vault.description.isNullOrBlank()) {
                Text(
                    vault.description,
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = "Description: ${'$'}{vault.description}" }
                )
            }
        },
        modifier = Modifier
            .combinedClickable(
                onClickLabel = "Open vault ${'$'}{vault.name}",
                role = Role.Button,
                onClick = { onClick(vault) },
                onLongClick = { onLongClick(vault) }
            )
            .background(Color.Black),
        colors = ListItemDefaults.colors(containerColor = Color.Black)
    )
}

@Composable
private fun EditVaultDialog(
    vault: Vault,
    onDismiss: () -> Unit,
    onSave: (Vault) -> Unit
) {
    var description by remember { mutableStateOf(vault.description.orEmpty()) }
    val colorOptions = listOf(
        "IMPORTANT (RED)" to "#D32F2F",
        "MEDIUM (YELLOW)" to "#FBC02D",
        "BASIC (GREEN)" to "#388E3C",
        "NEUTRAL (GRAY)" to "#616161"
    )
    var selectedColor by remember { mutableStateOf(vault.colorHex ?: "#616161") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("EDIT VAULT") },
        text = {
            Column {
                Text("Select importance color", fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                colorOptions.forEach { (label, hex) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedColor = hex }
                            .padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(parseVaultColor(hex), CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        RadioButton(
                            selected = selectedColor.equals(hex, ignoreCase = true),
                            onClick = { selectedColor = hex }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    vault.copy(
                        description = description.trim().ifBlank { null },
                        colorHex = selectedColor
                    )
                )
            }) { Text("SAVE") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}

private fun parseVaultColor(hex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(Color.Gray)
}
