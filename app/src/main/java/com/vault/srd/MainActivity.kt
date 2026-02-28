package com.vault.srd

import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.vault.srd.security.SecurityManager
import com.vault.srd.ui.auth.TermsOfServiceScreen
import com.vault.srd.ui.dashboard.*
import com.vault.srd.ui.theme.TheVaultTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val securityManager: SecurityManager by inject()
    private lateinit var globalViewModel: VaultViewModel

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (::globalViewModel.isInitialized) {
            globalViewModel.resetInactivityTimer()
        }
        securityManager.recordUserInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enforce FLAG_SECURE to block screenshots / screen recording / recents previews
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            var currentScreen by remember {
                mutableStateOf<Screen>(if (securityManager.isTosAccepted()) Screen.Dashboard else Screen.Tos)
            }
            var tosAccepting by remember { mutableStateOf(false) }
            var selectedVault by remember { mutableStateOf<com.vault.srd.data.Vault?>(null) }
            val scope = rememberCoroutineScope()

            val viewModel: VaultViewModel = koinViewModel()
            globalViewModel = viewModel

            TheVaultTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Black) {
                    // Security warnings are now handled inside the dashboard UI (Warnings button)

                    when (currentScreen) {
                        Screen.Tos -> TermsOfServiceScreen(
                            onAccept = {
                                if (tosAccepting) return@TermsOfServiceScreen
                                tosAccepting = true
                                currentScreen = Screen.Dashboard
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    securityManager.acceptTos()
                                }
                            },
                            onExit = { finishAndRemoveTask() }
                        )
                        Screen.Dashboard -> DashboardScreen(
                            viewModel = viewModel,
                            onCreateVault = { currentScreen = Screen.CreateVault },
                            onVaultClick = { 
                                selectedVault = it
                                currentScreen = Screen.VaultContent 
                            }
                        )
                        Screen.CreateVault -> CreateVaultScreen(
                            viewModel = viewModel,
                            onVaultCreated = { currentScreen = Screen.Dashboard },
                            onBack = { currentScreen = Screen.Dashboard }
                        )
                        Screen.VaultContent -> selectedVault?.let { 
                            VaultContentScreen(
                                vault = it,
                                viewModel = viewModel,
                                securityManager = securityManager,
                                onBack = { currentScreen = Screen.Dashboard },
                                onSwitchVault = { newVault -> selectedVault = newVault }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        securityManager.recordUserInteraction()
    }

    override fun onStop() {
        super.onStop()
        if (::globalViewModel.isInitialized && !globalViewModel.hasActiveOperations()) {
            globalViewModel.lockAllVaults()
        }
    }
}

sealed class Screen {
    object Tos : Screen()
    object Dashboard : Screen()
    object CreateVault : Screen()
    object VaultContent : Screen()
}
