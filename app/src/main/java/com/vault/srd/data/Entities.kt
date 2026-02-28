package com.vault.srd.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "vaults",
    indices = [Index(value = ["pinHash"]), Index(value = ["name"]) ]
)
data class Vault(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val pinHash: String,
    val pinSalt: String,
    val colorHex: String?,
    val logoPath: String?,
    val description: String?,
    val biometricUnlockEnabled: Boolean = false,
    val isDecoy: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "vault_items",
    indices = [
        Index(value = ["vaultId"]),
        Index(value = ["folderId"]),
        Index(value = ["vaultId", "name"]),
        Index(value = ["vaultId", "type"]),
        Index(value = ["vaultId", "createdAt"])
    ]
)
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vaultId: Int,
    val type: String,           // "NOTE", "IMAGE", "PASSWORD", "FILE", "CONTACT"
    val name: String,           // Item Name (e.g. "Gmail", "My Secret Note")
    val description: String?,   // Item Description
    val content: String?,       // Encrypted Note/Password
    val username: String?,      // Specifically for Passwords
    val passCategory: String?,  // "APP" or "WEBSITE"
    val link: String?,          // Website Link
    val logoPath: String?,      // Path to App Logo PNG
    val filePath: String?,      // For general Files/Images
    val extension: String?,     // .txt, .png, etc.
    val email: String? = null,
    val phoneNumber: String? = null,
    val folderId: Int? = null,  // Link to a folder
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt
)

@Entity(tableName = "folders", indices = [Index(value = ["vaultId"])])
data class VaultFolder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val vaultId: Int,
    val name: String,
    val description: String?,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "vault_tags",
    indices = [
        Index(value = ["vaultId"]),
        Index(value = ["vaultId", "name"], unique = true)
    ]
)
data class VaultTag(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String,
    val vaultId: Int,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "vault_item_tags",
    primaryKeys = ["itemId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = VaultItem::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = VaultTag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["tagId"]), Index(value = ["itemId"])]
)
data class VaultItemTagCrossRef(
    val itemId: Int,
    val tagId: String
)
