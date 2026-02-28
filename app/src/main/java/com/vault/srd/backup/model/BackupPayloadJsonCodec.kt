package com.vault.srd.backup.model

import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultFolder
import okio.buffer
import okio.source
import java.io.InputStream

object BackupPayloadJsonCodec {
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(BackupPayloadDto::class.java)

    fun encodeToByteArray(payload: BackupArchive.BackupPayload): ByteArray {
        return encodeToString(payload).toByteArray(Charsets.UTF_8)
    }

    fun encodeToString(payload: BackupArchive.BackupPayload): String {
        return adapter.toJson(payload.toDto())
    }

    fun decodeFromBytes(bytes: ByteArray): BackupArchive.BackupPayload {
        return decodeFromString(String(bytes, Charsets.UTF_8))
    }

    fun decodeFromStream(input: InputStream): BackupArchive.BackupPayload {
        val source = input.source().buffer()
        val reader = JsonReader.of(source)
        val dto = adapter.fromJson(reader) ?: throw IllegalArgumentException("Invalid payload JSON")
        return dto.toDomain()
    }

    fun decodeFromString(json: String): BackupArchive.BackupPayload {
        val dto = adapter.fromJson(json) ?: throw IllegalArgumentException("Invalid payload JSON")
        return dto.toDomain()
    }

    private fun BackupArchive.BackupPayload.toDto(): BackupPayloadDto {
        return BackupPayloadDto(
            version = version,
            createdAt = createdAt,
            appVersion = appVersion,
            vaults = vaults.map { vault ->
                VaultDto(
                    id = vault.id,
                    name = vault.name,
                    pinHash = vault.pinHash,
                    pinSalt = vault.pinSalt,
                    colorHex = vault.colorHex,
                    logoPath = vault.logoPath,
                    description = vault.description,
                    biometricUnlockEnabled = vault.biometricUnlockEnabled,
                    isDecoy = vault.isDecoy,
                    createdAt = vault.createdAt
                )
            },
            folders = folders.map { folder ->
                FolderDto(
                    id = folder.id,
                    vaultId = folder.vaultId,
                    name = folder.name,
                    description = folder.description,
                    createdAt = folder.createdAt
                )
            },
            items = items.map { item ->
                ItemDto(
                    id = item.id,
                    vaultId = item.vaultId,
                    type = item.type,
                    name = item.name,
                    description = item.description,
                    content = item.content,
                    username = item.username,
                    passCategory = item.passCategory,
                    link = item.link,
                    fileRef = item.fileRef,
                    logoRef = item.logoRef,
                    extension = item.extension,
                    email = item.email,
                    phoneNumber = item.phoneNumber,
                    folderId = item.folderId,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt
                )
            },
            settings = settings.map { setting ->
                SettingDto(
                    key = setting.key,
                    type = setting.type,
                    value = setting.value
                )
            },
            tags = tags.map { tag ->
                TagDto(
                    id = tag.id,
                    name = tag.name,
                    colorHex = tag.colorHex,
                    vaultId = tag.vaultId,
                    createdAt = tag.createdAt
                )
            },
            itemTags = itemTags.map { ref ->
                ItemTagDto(
                    itemId = ref.itemId,
                    tagId = ref.tagId
                )
            }
        )
    }

    private fun BackupPayloadDto.toDomain(): BackupArchive.BackupPayload {
        return BackupArchive.BackupPayload(
            version = version,
            createdAt = createdAt,
            appVersion = appVersion,
            vaults = vaults.map { vault ->
                Vault(
                    id = vault.id,
                    name = vault.name,
                    pinHash = vault.pinHash,
                    pinSalt = vault.pinSalt,
                    colorHex = vault.colorHex,
                    logoPath = vault.logoPath,
                    description = vault.description,
                    biometricUnlockEnabled = vault.biometricUnlockEnabled,
                    isDecoy = vault.isDecoy,
                    createdAt = vault.createdAt
                )
            },
            folders = folders.map { folder ->
                VaultFolder(
                    id = folder.id,
                    vaultId = folder.vaultId,
                    name = folder.name,
                    description = folder.description,
                    createdAt = folder.createdAt
                )
            },
            items = items.map { item ->
                BackupArchive.BackupItem(
                    id = item.id,
                    vaultId = item.vaultId,
                    type = item.type,
                    name = item.name,
                    description = item.description,
                    content = item.content,
                    username = item.username,
                    passCategory = item.passCategory,
                    link = item.link,
                    fileRef = item.fileRef,
                    logoRef = item.logoRef,
                    extension = item.extension,
                    email = item.email,
                    phoneNumber = item.phoneNumber,
                    folderId = item.folderId,
                    createdAt = item.createdAt,
                    updatedAt = item.updatedAt
                )
            },
            settings = settings.map { setting ->
                BackupArchive.BackupSetting(
                    key = setting.key,
                    type = setting.type,
                    value = setting.value
                )
            },
            tags = tags.map { tag ->
                BackupArchive.BackupTag(
                    id = tag.id,
                    name = tag.name,
                    colorHex = tag.colorHex,
                    vaultId = tag.vaultId,
                    createdAt = tag.createdAt
                )
            },
            itemTags = itemTags.map { ref ->
                BackupArchive.BackupItemTag(
                    itemId = ref.itemId,
                    tagId = ref.tagId
                )
            }
        )
    }

    data class BackupPayloadDto(
        val version: Int = 1,
        val createdAt: Long = System.currentTimeMillis(),
        val appVersion: String = "unknown",
        val vaults: List<VaultDto> = emptyList(),
        val folders: List<FolderDto> = emptyList(),
        val items: List<ItemDto> = emptyList(),
        val settings: List<SettingDto> = emptyList(),
        val tags: List<TagDto> = emptyList(),
        val itemTags: List<ItemTagDto> = emptyList()
    )

    data class VaultDto(
        val id: Int = 0,
        val name: String = "",
        val pinHash: String = "",
        val pinSalt: String = "",
        val colorHex: String? = null,
        val logoPath: String? = null,
        val description: String? = null,
        val biometricUnlockEnabled: Boolean = false,
        val isDecoy: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class FolderDto(
        val id: Int = 0,
        val vaultId: Int = 0,
        val name: String = "",
        val description: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class ItemDto(
        val id: Int = 0,
        val vaultId: Int = 0,
        val type: String = "",
        val name: String = "",
        val description: String? = null,
        val content: String? = null,
        val username: String? = null,
        val passCategory: String? = null,
        val link: String? = null,
        val fileRef: String? = null,
        val logoRef: String? = null,
        val extension: String? = null,
        val email: String? = null,
        val phoneNumber: String? = null,
        val folderId: Int? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = createdAt
    )

    data class SettingDto(
        val key: String = "",
        val type: String = "string",
        val value: String = ""
    )

    data class TagDto(
        val id: String = "",
        val name: String = "",
        val colorHex: String = "#FFFFFF",
        val vaultId: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    )

    data class ItemTagDto(
        val itemId: Int = 0,
        val tagId: String = ""
    )
}
