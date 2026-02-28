package com.vault.srd.backup.core

import android.util.Base64
import com.vault.srd.backup.model.BackupMode
import com.vault.srd.backup.model.KdfParams
import com.vault.srd.backup.model.KeyWrapType
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class BackupKeyDerivation {
    fun deriveKdfInput(
        credential: CharArray,
        mode: BackupMode,
        wrapType: KeyWrapType,
        deviceFingerprintProvider: () -> ByteArray
    ): ByteArray {
        val base = if (mode == BackupMode.EXTREME || wrapType == KeyWrapType.EXTREME_DEVICE) {
            val fingerprint = deviceFingerprintProvider()
            val fp = Base64.encodeToString(fingerprint, Base64.NO_WRAP)
            if (credential.isNotEmpty()) String(credential) + ":" + fp else fp
        } else {
            String(credential)
        }
        return base.toByteArray(Charsets.UTF_8)
    }

    fun argon2id(input: ByteArray, salt: ByteArray, params: KdfParams, outLen: Int): ByteArray {
        val generator = Argon2BytesGenerator()
        val argonParams = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
            .build()
        generator.init(argonParams)
        val out = ByteArray(outLen)
        generator.generateBytes(input, out)
        return out
    }
}
