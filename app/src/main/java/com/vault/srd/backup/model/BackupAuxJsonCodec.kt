package com.vault.srd.backup.model

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object BackupAuxJsonCodec {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val phraseSidecarAdapter = moshi.adapter(PhraseSidecarDto::class.java)
    private val fullKeyPayloadAdapter = moshi.adapter(FullKeyPayloadDto::class.java)
    private val legacyFullKeyFileAdapter = moshi.adapter(LegacyFullKeyFileDto::class.java)
    private val intruderCaptureAdapter = moshi.adapter(IntruderCaptureSettingDto::class.java)
    private val stringSetAdapter = moshi.adapter(StringSetSettingDto::class.java)

    data class PhraseSidecarDto(
        val version: Int = 1,
        val backupId: String = "",
        val nonce: String = "",
        val cipher: String = ""
    )

    data class FullKeyPayloadDto(
        val version: Int = 2,
        val backupId: String = "",
        val masterKey: String = "",
        val generatedKey: String = "",
        val phrase: String = "",
        val wordCount: Int = 0
    )

    data class LegacyFullKeyFileDto(
        val version: Int = 1,
        val backupId: String = "",
        val masterKey: String = "",
        val generatedKey: String = "",
        val phrase: String = "",
        val materialHash: String = ""
    )

    data class IntruderCaptureSettingDto(
        val ref: String = "",
        val name: String = ""
    )

    data class StringSetSettingDto(
        val items: List<String> = emptyList()
    )

    fun encodePhraseSidecar(dto: PhraseSidecarDto): ByteArray {
        return phraseSidecarAdapter.toJson(dto).toByteArray(Charsets.UTF_8)
    }

    fun decodePhraseSidecar(bytes: ByteArray): PhraseSidecarDto? {
        return runCatching {
            phraseSidecarAdapter.fromJson(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    fun encodeFullKeyPayload(dto: FullKeyPayloadDto): ByteArray {
        return fullKeyPayloadAdapter.toJson(dto).toByteArray(Charsets.UTF_8)
    }

    fun decodeFullKeyPayload(bytes: ByteArray): FullKeyPayloadDto? {
        return runCatching {
            fullKeyPayloadAdapter.fromJson(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    fun decodeLegacyFullKeyFile(bytes: ByteArray): LegacyFullKeyFileDto? {
        return runCatching {
            legacyFullKeyFileAdapter.fromJson(String(bytes, Charsets.UTF_8))
        }.getOrNull()
    }

    fun encodeIntruderCaptureSetting(dto: IntruderCaptureSettingDto): String {
        return intruderCaptureAdapter.toJson(dto)
    }

    fun decodeIntruderCaptureSetting(value: String): IntruderCaptureSettingDto? {
        return runCatching { intruderCaptureAdapter.fromJson(value) }.getOrNull()
    }

    fun encodeStringSet(items: Set<String>): String {
        return stringSetAdapter.toJson(StringSetSettingDto(items = items.toList()))
    }

    fun decodeStringSet(value: String): Set<String> {
        return runCatching {
            stringSetAdapter.fromJson(value)?.items.orEmpty()
                .filter { it.isNotBlank() }
                .toCollection(linkedSetOf())
        }.getOrDefault(linkedSetOf())
    }
}
