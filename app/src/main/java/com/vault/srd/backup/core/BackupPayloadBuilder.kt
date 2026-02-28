package com.vault.srd.backup.core

import com.vault.srd.backup.model.BackupArchive
import com.vault.srd.data.Vault
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItemTagCrossRef
import com.vault.srd.data.VaultTag

class BackupPayloadBuilder {
    fun buildPayload(
        version: Int,
        createdAt: Long,
        appVersion: String,
        vaults: List<Vault>,
        folders: List<VaultFolder>,
        items: List<BackupArchive.BackupItem>,
        settings: List<BackupArchive.BackupSetting>,
        tags: List<VaultTag>,
        itemTags: List<VaultItemTagCrossRef>
    ): BackupArchive.BackupPayload {
        return BackupArchive.BackupPayload(
            version = version,
            createdAt = createdAt,
            appVersion = appVersion,
            vaults = vaults,
            folders = folders,
            items = items,
            settings = settings,
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
}
