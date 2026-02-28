package com.vault.srd.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class TosSection(val title: String, val points: List<String>)

internal fun buildTosSections(): List<TosSection> {
    return listOf(
        TosSection("Acceptance of Terms", listOf(
            "By installing or using The Vault, you agree to these Terms of Service.",
            "If you do not agree, do not use the application."
        )),
        TosSection("Offline Architecture", listOf(
            "The Vault is designed to run fully offline.",
            "No user account, cloud sync, or developer-hosted storage is required.",
            "The developer cannot access vaults, recover passwords, reset keys, or unlock backups."
        )),
        TosSection("User Responsibility", listOf(
            "All data stays on your device and is managed by you.",
            "You are responsible for passwords, backups, recovery phrases, and device security.",
            "Lost credentials may cause permanent data loss."
        )),
        TosSection("Encryption and Security Disclaimer", listOf(
            "Encryption is performed locally on-device.",
            "Security depends on password strength, device integrity, and user practices.",
            "No system can guarantee full protection against compromise, malware, or hardware attacks."
        )),
        TosSection("Backups", listOf(
            "Device-locked (Extreme) backups can only be restored on the original device.",
            "After factory reset or key destruction, these backups may be unrecoverable."
        )),
        TosSection("Liability and Warranty", listOf(
            "The application is provided AS IS, without warranties.",
            "The developer is not liable for data loss, inaccessible data, or consequential damages."
        )),
        TosSection("Prohibited Use", listOf(
            "No reverse engineering or security bypass attempts.",
            "No unlawful use or tampering with app security mechanisms."
        )),
        TosSection("Final Terms", listOf(
            "Updates may be provided at the developer's discretion.",
            "These terms form the full agreement regarding use of The Vault."
        ))
    )
}

@Composable
internal fun TosSectionsCard() {
    val sections = remember { buildTosSections() }
    Card(colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))) {
        Column(modifier = Modifier.padding(14.dp)) {
            sections.forEachIndexed { index, section ->
                Text(
                    section.title.uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(6.dp))
                section.points.forEach { point ->
                    Text("• $point", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }
                if (index != sections.lastIndex) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}
