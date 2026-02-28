package com.vault.srd.backup.model

import com.vault.srd.data.Vault
import com.vault.srd.data.VaultFolder
import com.vault.srd.data.VaultItemTagCrossRef
import com.vault.srd.data.VaultTag

/**
 * Binary archive that contains a JSON payload and raw attachment bytes.
 * This archive is compressed and encrypted before being written to disk.
 */
object BackupArchive {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 1

    data class BackupItem(
        val id: Int,
        val vaultId: Int,
        val type: String,
        val name: String,
        val description: String?,
        val content: String?,
        val username: String?,
        val passCategory: String?,
        val link: String?,
        val fileRef: String?,
        val logoRef: String?,
        val extension: String?,
        val email: String?,
        val phoneNumber: String?,
        val folderId: Int?,
        val createdAt: Long,
        val updatedAt: Long = createdAt
    )

    data class BackupSetting(
        val key: String,
        val type: String,
        val value: String
    )

    data class BackupTag(
        val id: String,
        val name: String,
        val colorHex: String,
        val vaultId: Int,
        val createdAt: Long
    )

    data class BackupItemTag(
        val itemId: Int,
        val tagId: String
    )

    data class BackupPayload(
        val version: Int,
        val createdAt: Long,
        val appVersion: String,
        val vaults: List<Vault>,
        val folders: List<VaultFolder>,
        val items: List<BackupItem>,
        val settings: List<BackupSetting> = emptyList(),
        val tags: List<BackupTag> = emptyList(),
        val itemTags: List<BackupItemTag> = emptyList()
    )

    data class ArchiveBundle(
        val payload: BackupPayload,
        val attachments: Map<String, ByteArray>
    )

    fun pack(bundle: ArchiveBundle): ByteArray {
        val jsonBytes = BackupPayloadJsonCodec.encodeToByteArray(bundle.payload)
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.write(MAGIC)
        dos.writeInt(VERSION)
        dos.writeInt(jsonBytes.size)
        dos.write(jsonBytes)
        dos.writeInt(bundle.attachments.size)
        bundle.attachments.forEach { (id, bytes) ->
            val idBytes = id.toByteArray(Charsets.UTF_8)
            dos.writeInt(idBytes.size)
            dos.write(idBytes)
            dos.writeInt(bytes.size)
            dos.write(bytes)
        }
        dos.flush()
        return output.toByteArray()
    }

    fun unpack(bytes: ByteArray): ArchiveBundle {
        val input = java.io.DataInputStream(bytes.inputStream())
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid archive signature")
        }
        val version = input.readInt()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported archive version")
        }
        val jsonLen = input.readInt()
        if (jsonLen <= 0 || jsonLen > 50_000_000) {
            throw IllegalArgumentException("Invalid archive payload length")
        }
        val jsonBytes = ByteArray(jsonLen)
        input.readFully(jsonBytes)
        val payload = BackupPayloadJsonCodec.decodeFromBytes(jsonBytes)
        val count = input.readInt()
        if (count < 0 || count > 1_000_000) {
            throw IllegalArgumentException("Invalid attachment count")
        }
        val attachments = mutableMapOf<String, ByteArray>()
        repeat(count) {
            val idLen = input.readInt()
            if (idLen <= 0 || idLen > 10_000) {
                throw IllegalArgumentException("Invalid attachment id length")
            }
            val idBytes = ByteArray(idLen)
            input.readFully(idBytes)
            val id = String(idBytes, Charsets.UTF_8)
            val dataLen = input.readInt()
            if (dataLen < 0 || dataLen > 200_000_000) {
                throw IllegalArgumentException("Invalid attachment size")
            }
            val data = ByteArray(dataLen)
            input.readFully(data)
            attachments[id] = data
        }
        return ArchiveBundle(payload = payload, attachments = attachments)
    }
}
