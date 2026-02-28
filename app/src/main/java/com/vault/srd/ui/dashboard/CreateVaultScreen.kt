package com.vault.srd.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vault.srd.data.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateVaultScreen(
    onVaultCreated: (Boolean) -> Unit,
    onBack: () -> Unit,
    viewModel: VaultViewModel
) {
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    val colorOptions = listOf(
        "IMPORTANT (RED)" to "#D32F2F",
        "MEDIUM (YELLOW)" to "#FBC02D",
        "BASIC (GREEN)" to "#388E3C",
        "NEUTRAL (GRAY)" to "#616161"
    )
    var selectedColor by remember { mutableStateOf("#616161") }
    var error by remember { mutableStateOf<String?>(null) }
    var isCreating by remember { mutableStateOf(false) }
    val userVaults by viewModel.vaults.collectAsState()
    val maxVaults = VaultRepository.MAX_USER_VAULTS
    val limitReached = userVaults.size >= maxVaults
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CREATE NEW VAULT", fontWeight = FontWeight.Black) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Vault Name (Mandatory)") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = pin,
                onValueChange = { if (it.length <= 6) pin = it },
                label = { Text("6-Digit PIN (Mandatory)") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = desc,
                onValueChange = { desc = it },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text("VAULT IMPORTANCE COLOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            colorOptions.forEach { (label, hex) ->
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                    Text(label, color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    RadioButton(
                        selected = selectedColor.equals(hex, ignoreCase = true),
                        onClick = { selectedColor = hex }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Vaults: ${userVaults.size}/$maxVaults",
                color = if (limitReached) Color.Red else Color.Gray,
                fontSize = 11.sp
            )
            if (limitReached) {
                Text(
                    "Maximum $maxVaults vaults reached. Delete one to create a new vault.",
                    color = Color.Red,
                    fontSize = 11.sp
                )
            }
            
            if (error != null) {
                Text(error!!, color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (isCreating) return@Button
                    if (limitReached) {
                        error = "Maximum $maxVaults vaults reached"
                    } else if (name.isBlank() || pin.length != 6) {
                        error = "Name and 6-Digit PIN are mandatory"
                    } else {
                        isCreating = true
                        error = null
                        val nameSnapshot = name
                        val pinSnapshot = pin
                        val descSnapshot = desc
                        scope.launch {
                            val isDecoy = withContext(Dispatchers.Default) {
                                viewModel.isDecoyPin(pinSnapshot)
                            }
                            if (isDecoy) {
                                error = "This PIN cannot match the decoy PIN. Choose a different PIN."
                                isCreating = false
                                return@launch
                            }
                            viewModel.createVault(nameSnapshot, pinSnapshot, selectedColor, null, descSnapshot) { success ->
                                isCreating = false
                                if (success) {
                                    onVaultCreated(true)
                                } else {
                                    error = "Vault already exists or could not be created."
                                }
                            }
                        }
                    }
                },
                enabled = !limitReached && !isCreating,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
            ) {
                Text(if (isCreating) "CREATING..." else "CREATE VAULT")
            }
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text("CANCEL", color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

private fun parseVaultColor(hex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(Color.Gray)
}
