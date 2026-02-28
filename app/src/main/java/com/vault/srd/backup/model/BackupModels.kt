package com.vault.srd.backup.model

/**
 * Backup file models and binary serializer for .vltbck containers.
 */

enum class BackupMode {
    NORMAL,
    EXTREME
}

enum class BackupScope {
    SINGLE_VAULT,
    ENTIRE_APP
}

enum class BackupProfile {
    LEGACY,
    NORMAL_SINGLE,
    NORMAL_ENTIRE,
    EXTREME
}

enum class BackupCompression {
    ZSTD
}

enum class KeyWrapType {
    PASSWORD,
    RECOVERY_PHRASE,
    MASTER_COMPOSITE,
    EXTREME_DEVICE
}

enum class KdfAlgorithm {
    ARGON2ID
}

data class KdfParams(
    val algorithm: KdfAlgorithm,
    val iterations: Int,
    val memoryKiB: Int,
    val parallelism: Int
)

data class KeyWrap(
    val type: KeyWrapType,
    val kdf: KdfParams,
    val salt: ByteArray,
    val nonce: ByteArray,
    val wrappedKey: ByteArray
)

data class BackupHeader(
    val version: Int,
    val mode: BackupMode,
    val compression: BackupCompression,
    val createdAt: Long,
    val payloadNonce: ByteArray,
    val deviceFingerprintHash: ByteArray?,
    val keyWraps: List<KeyWrap>,
    val profile: BackupProfile = BackupProfile.LEGACY,
    val scope: BackupScope = BackupScope.ENTIRE_APP,
    val targetVaultId: Int? = null,
    val backupId: String = "",
    val phraseHash: ByteArray? = null,
    val includesSettings: Boolean = false
)

data class BackupFile(
    val header: BackupHeader,
    val cipherText: ByteArray,
    val authTag: ByteArray,
    val sha256: ByteArray
)

object BackupSerializer {
    private val MAGIC = byteArrayOf('V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte(), '5'.code.toByte())
    private const val AUTH_TAG_LEN = 16
    private const val SHA256_LEN = 32

    fun serialize(file: BackupFile): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val dos = java.io.DataOutputStream(output)
        dos.write(MAGIC)
        dos.writeInt(file.header.version)
        dos.writeInt(file.header.mode.ordinal)
        dos.writeInt(file.header.compression.ordinal)
        dos.writeLong(file.header.createdAt)
        writeBytes(dos, file.header.payloadNonce)
        val deviceHash = file.header.deviceFingerprintHash
        dos.writeBoolean(deviceHash != null)
        if (deviceHash != null) {
            writeBytes(dos, deviceHash)
        }
        if (file.header.version >= 6) {
            dos.writeInt(file.header.profile.ordinal)
            dos.writeInt(file.header.scope.ordinal)
            dos.writeInt(file.header.targetVaultId ?: -1)
            writeBytes(dos, file.header.backupId.toByteArray(Charsets.UTF_8))
            dos.writeBoolean(file.header.phraseHash != null)
            if (file.header.phraseHash != null) {
                writeBytes(dos, file.header.phraseHash)
            }
            dos.writeBoolean(file.header.includesSettings)
            if (file.header.version < 7) {
                // Legacy field (auto-backup). Always false for new builds.
                dos.writeBoolean(false)
            }
        }
        dos.writeInt(file.header.keyWraps.size)
        file.header.keyWraps.forEach { wrap ->
            dos.writeInt(wrap.type.ordinal)
            dos.writeInt(wrap.kdf.algorithm.ordinal)
            dos.writeInt(wrap.kdf.iterations)
            dos.writeInt(wrap.kdf.memoryKiB)
            dos.writeInt(wrap.kdf.parallelism)
            writeBytes(dos, wrap.salt)
            writeBytes(dos, wrap.nonce)
            writeBytes(dos, wrap.wrappedKey)
        }
        writeBytes(dos, file.cipherText)
        if (file.authTag.size != AUTH_TAG_LEN) {
            throw IllegalArgumentException("Invalid auth tag length")
        }
        dos.write(file.authTag)
        if (file.sha256.size != SHA256_LEN) {
            throw IllegalArgumentException("Invalid hash length")
        }
        dos.write(file.sha256)
        dos.flush()
        return output.toByteArray()
    }

    fun deserialize(bytes: ByteArray): BackupFile {
        if (bytes.size < MAGIC.size + 4 + 4 + 4 + 8 + AUTH_TAG_LEN + SHA256_LEN) {
            throw IllegalArgumentException("Backup file too small")
        }
        val input = java.io.DataInputStream(bytes.inputStream())
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IllegalArgumentException("Invalid backup signature")
        }
        val version = input.readInt()
        val mode = BackupMode.values()[input.readInt()]
        val compression = BackupCompression.values()[input.readInt()]
        val createdAt = input.readLong()
        val payloadNonce = readBytes(input)
        val hasDeviceHash = input.readBoolean()
        val deviceFingerprintHash = if (hasDeviceHash) readBytes(input) else null
        val profile: BackupProfile
        val scope: BackupScope
        val targetVaultId: Int?
        val backupId: String
        val phraseHash: ByteArray?
        val includesSettings: Boolean
        if (version >= 6) {
            val profileIdx = input.readInt()
            profile = BackupProfile.values().getOrElse(profileIdx) { BackupProfile.LEGACY }

            val scopeIdx = input.readInt()
            scope = BackupScope.values().getOrElse(scopeIdx) { BackupScope.ENTIRE_APP }

            val rawTargetVaultId = input.readInt()
            targetVaultId = if (rawTargetVaultId >= 0) rawTargetVaultId else null

            backupId = String(readBytes(input), Charsets.UTF_8)
            val hasPhraseHash = input.readBoolean()
            phraseHash = if (hasPhraseHash) readBytes(input) else null
            includesSettings = input.readBoolean()
            if (version < 7) {
                // Legacy field (auto-backup). Ignore.
                input.readBoolean()
            }
        } else {
            profile = BackupProfile.LEGACY
            scope = BackupScope.ENTIRE_APP
            targetVaultId = null
            backupId = ""
            phraseHash = null
            includesSettings = false
        }
        val keyWrapCount = input.readInt()
        val wraps = mutableListOf<KeyWrap>()
        repeat(keyWrapCount) {
            val typeIdx = input.readInt()
            val type = KeyWrapType.values().getOrElse(typeIdx) { KeyWrapType.PASSWORD }
            val kdfAlgIdx = input.readInt()
            val kdfAlg = KdfAlgorithm.values().getOrElse(kdfAlgIdx) { KdfAlgorithm.ARGON2ID }
            val iterations = input.readInt()
            val memoryKiB = input.readInt()
            val parallelism = input.readInt()
            val salt = readBytes(input)
            val nonce = readBytes(input)
            val wrappedKey = readBytes(input)
            wraps.add(
                KeyWrap(
                    type = type,
                    kdf = KdfParams(kdfAlg, iterations, memoryKiB, parallelism),
                    salt = salt,
                    nonce = nonce,
                    wrappedKey = wrappedKey
                )
            )
        }
        val cipherText = readBytes(input)
        val authTag = ByteArray(AUTH_TAG_LEN)
        input.readFully(authTag)
        val sha256 = ByteArray(SHA256_LEN)
        input.readFully(sha256)
        return BackupFile(
            header = BackupHeader(
                version = version,
                mode = mode,
                compression = compression,
                createdAt = createdAt,
                payloadNonce = payloadNonce,
                deviceFingerprintHash = deviceFingerprintHash,
                keyWraps = wraps,
                profile = profile,
                scope = scope,
                targetVaultId = targetVaultId,
                backupId = backupId,
                phraseHash = phraseHash,
                includesSettings = includesSettings
            ),
            cipherText = cipherText,
            authTag = authTag,
            sha256 = sha256
        )
    }

    fun computeSha256(payload: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(payload)
    }

    fun splitHashArea(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (bytes.size < SHA256_LEN) {
            throw IllegalArgumentException("Invalid backup file size")
        }
        val contentLen = bytes.size - SHA256_LEN
        val content = bytes.copyOfRange(0, contentLen)
        val hash = bytes.copyOfRange(contentLen, bytes.size)
        return content to hash
    }

    fun expectedMagic(): ByteArray = MAGIC.copyOf()

    private fun writeBytes(dos: java.io.DataOutputStream, bytes: ByteArray) {
        dos.writeInt(bytes.size)
        dos.write(bytes)
    }

    private fun readBytes(dis: java.io.DataInputStream): ByteArray {
        val len = dis.readInt()
        if (len < 0 || len > 100_000_000) {
            throw IllegalArgumentException("Invalid length in backup file")
        }
        val out = ByteArray(len)
        dis.readFully(out)
        return out
    }
}
