package com.vault.srd.backup.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupSegmentedEnvelope(
    private val chunkSize: Int,
    private val secureRandom: SecureRandom = SecureRandom()
) {

    companion object {
        const val INTEGRITY_HASH_LENGTH = 32
        const val INTEGRITY_TRAILER_MARKER = -1
    }

    fun openEncryptingStream(
        out: OutputStream,
        key: ByteArray,
        appendIntegrityTrailer: Boolean,
        onChunkPlainBytesWritten: ((Long) -> Unit)? = null
    ): OutputStream {
        return SegmentedGcmEncryptingOutputStream(
            out = out,
            key = key,
            appendIntegrityTrailer = appendIntegrityTrailer,
            onChunkPlainBytesWritten = onChunkPlainBytesWritten
        )
    }

    fun openDecryptingStream(
        input: InputStream,
        key: ByteArray,
        expectIntegrityTrailer: Boolean,
        onChunkPlainBytesRead: ((Long) -> Unit)? = null
    ): InputStream {
        return SegmentedGcmDecryptingInputStream(
            input = input,
            key = key,
            expectIntegrityTrailer = expectIntegrityTrailer,
            onChunkPlainBytesRead = onChunkPlainBytesRead
        )
    }

    fun verifyEnvelope(
        input: DataInputStream,
        requireIntegrityTrailer: Boolean
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        var sawDataSegment = false
        while (true) {
            val segmentIndex = try {
                input.readInt()
            } catch (_: EOFException) {
                return !requireIntegrityTrailer && sawDataSegment
            }
            if (segmentIndex == INTEGRITY_TRAILER_MARKER) {
                val expectedHashLen = input.readInt()
                if (expectedHashLen != INTEGRITY_HASH_LENGTH) return false
                val expectedHash = ByteArray(expectedHashLen)
                input.readFully(expectedHash)
                val computedHash = digest.digest()
                val valid = MessageDigest.isEqual(expectedHash, computedHash)
                wipe(expectedHash)
                wipe(computedHash)
                val trailingByte = input.read()
                return valid && trailingByte == -1
            }
            if (segmentIndex < 0) return false
            val plainLen = input.readInt()
            if (plainLen <= 0 || plainLen > chunkSize) return false
            val iv = ByteArray(12)
            input.readFully(iv)
            val encryptedLen = input.readInt()
            if (encryptedLen < 16 || encryptedLen > plainLen + 64) return false
            val encrypted = ByteArray(encryptedLen)
            input.readFully(encrypted)
            updateStreamIntegrityDigest(
                digest = digest,
                segmentIndex = segmentIndex,
                plainLength = plainLen,
                iv = iv,
                encryptedLength = encryptedLen,
                encrypted = encrypted
            )
            sawDataSegment = true
        }
    }

    private inner class SegmentedGcmEncryptingOutputStream(
        private val out: OutputStream,
        private val key: ByteArray,
        private val appendIntegrityTrailer: Boolean,
        private val onChunkPlainBytesWritten: ((Long) -> Unit)? = null
    ) : OutputStream() {
        private val chunkBuffer = ByteArray(chunkSize)
        private val segmentHeaderOut = java.io.DataOutputStream(out)
        private val secretKeySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val streamDigest = MessageDigest.getInstance("SHA-256")
        private val baseNonce = randomBytes(12)
        private var bufferedBytes = 0
        private var segmentIndex = 0
        private var totalPlainBytesWritten = 0L
        private var closed = false

        override fun write(b: Int) {
            ensureOpen()
            chunkBuffer[bufferedBytes] = b.toByte()
            bufferedBytes++
            if (bufferedBytes >= chunkBuffer.size) {
                writeCurrentChunk()
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            ensureOpen()
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid write range")
            }
            var cursor = off
            var remaining = len
            while (remaining > 0) {
                val copyLen = minOf(chunkBuffer.size - bufferedBytes, remaining)
                System.arraycopy(b, cursor, chunkBuffer, bufferedBytes, copyLen)
                bufferedBytes += copyLen
                cursor += copyLen
                remaining -= copyLen
                if (bufferedBytes >= chunkBuffer.size) {
                    writeCurrentChunk()
                }
            }
        }

        override fun flush() {
            ensureOpen()
            writeCurrentChunk()
            out.flush()
        }

        override fun close() {
            if (closed) return
            writeCurrentChunk()
            if (appendIntegrityTrailer) {
                val digestBytes = streamDigest.digest()
                segmentHeaderOut.writeInt(INTEGRITY_TRAILER_MARKER)
                segmentHeaderOut.writeInt(digestBytes.size)
                segmentHeaderOut.write(digestBytes)
                wipe(digestBytes)
            }
            out.flush()
            out.close()
            closed = true
        }

        private fun ensureOpen() {
            if (closed) throw IOException("Stream is closed")
        }

        private fun writeCurrentChunk() {
            if (bufferedBytes <= 0) return
            val plainChunk = if (bufferedBytes == chunkBuffer.size) {
                chunkBuffer
            } else {
                chunkBuffer.copyOf(bufferedBytes)
            }
            val iv = deriveSegmentIv(baseNonce, segmentIndex)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKeySpec,
                GCMParameterSpec(128, iv)
            )
            cipher.updateAAD(segmentAadBytes(segmentIndex))
            val encrypted = cipher.doFinal(plainChunk)
            segmentHeaderOut.writeInt(segmentIndex)
            segmentHeaderOut.writeInt(bufferedBytes)
            segmentHeaderOut.write(iv)
            segmentHeaderOut.writeInt(encrypted.size)
            segmentHeaderOut.write(encrypted)
            updateStreamIntegrityDigest(
                digest = streamDigest,
                segmentIndex = segmentIndex,
                plainLength = bufferedBytes,
                iv = iv,
                encryptedLength = encrypted.size,
                encrypted = encrypted
            )
            totalPlainBytesWritten += bufferedBytes.toLong()
            onChunkPlainBytesWritten?.invoke(totalPlainBytesWritten)
            segmentIndex++
            if (plainChunk !== chunkBuffer) {
                wipe(plainChunk)
            }
            bufferedBytes = 0
        }
    }

    private inner class SegmentedGcmDecryptingInputStream(
        input: InputStream,
        private val key: ByteArray,
        private val expectIntegrityTrailer: Boolean,
        private val onChunkPlainBytesRead: ((Long) -> Unit)? = null
    ) : InputStream() {
        private val dataInput = if (input is DataInputStream) input else DataInputStream(input)
        private val secretKeySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val streamDigest = MessageDigest.getInstance("SHA-256")
        private var expectedSegmentIndex = 0
        private var currentChunk = ByteArray(0)
        private var currentOffset = 0
        private var totalPlainBytesRead = 0L
        private var integrityTrailerVerified = false
        private var eof = false
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            ensureOpen()
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException("Invalid read range")
            }
            if (len == 0) return 0

            var written = 0
            while (written < len) {
                if (currentOffset >= currentChunk.size) {
                    if (!loadNextChunk()) {
                        return if (written == 0) -1 else written
                    }
                }
                val copyLen = minOf(len - written, currentChunk.size - currentOffset)
                System.arraycopy(currentChunk, currentOffset, b, off + written, copyLen)
                currentOffset += copyLen
                written += copyLen
            }
            return written
        }

        override fun close() {
            if (closed) return
            dataInput.close()
            closed = true
        }

        private fun ensureOpen() {
            if (closed) throw IOException("Stream is closed")
        }

        private fun loadNextChunk(): Boolean {
            if (eof) return false
            val segmentIndex = try {
                dataInput.readInt()
            } catch (_: EOFException) {
                if (expectIntegrityTrailer && !integrityTrailerVerified) {
                    throw IOException("Missing backup integrity trailer")
                }
                eof = true
                return false
            }
            if (segmentIndex == INTEGRITY_TRAILER_MARKER) {
                val expectedLen = dataInput.readInt()
                if (expectedLen != INTEGRITY_HASH_LENGTH) {
                    throw IOException("Invalid backup integrity trailer")
                }
                val expectedHash = ByteArray(expectedLen)
                dataInput.readFully(expectedHash)
                val computedHash = streamDigest.digest()
                val valid = MessageDigest.isEqual(expectedHash, computedHash)
                wipe(expectedHash)
                wipe(computedHash)
                if (!valid) {
                    throw IOException("Backup integrity check failed")
                }
                integrityTrailerVerified = true
                eof = true
                return false
            }
            if (segmentIndex < 0) {
                throw IOException("Invalid backup segment marker")
            }
            if (segmentIndex != expectedSegmentIndex) {
                throw IOException("Backup segment order is invalid")
            }
            val plainLen = dataInput.readInt()
            if (plainLen <= 0 || plainLen > chunkSize) {
                throw IOException("Invalid backup segment length")
            }
            val iv = ByteArray(12)
            dataInput.readFully(iv)
            val encryptedLen = dataInput.readInt()
            if (encryptedLen < 16 || encryptedLen > plainLen + 64) {
                throw IOException("Invalid encrypted segment length")
            }
            val encrypted = ByteArray(encryptedLen)
            dataInput.readFully(encrypted)
            updateStreamIntegrityDigest(
                digest = streamDigest,
                segmentIndex = segmentIndex,
                plainLength = plainLen,
                iv = iv,
                encryptedLength = encryptedLen,
                encrypted = encrypted
            )

            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKeySpec,
                GCMParameterSpec(128, iv)
            )
            cipher.updateAAD(segmentAadBytes(segmentIndex))
            val decrypted = cipher.doFinal(encrypted)
            if (decrypted.size != plainLen) {
                throw IOException("Decrypted segment length mismatch")
            }

            currentChunk = decrypted
            currentOffset = 0
            expectedSegmentIndex++
            totalPlainBytesRead += plainLen.toLong()
            onChunkPlainBytesRead?.invoke(totalPlainBytesRead)
            return true
        }
    }

    private fun updateStreamIntegrityDigest(
        digest: MessageDigest,
        segmentIndex: Int,
        plainLength: Int,
        iv: ByteArray,
        encryptedLength: Int,
        encrypted: ByteArray
    ) {
        writeIntToDigest(digest, segmentIndex)
        writeIntToDigest(digest, plainLength)
        digest.update(iv)
        writeIntToDigest(digest, encryptedLength)
        digest.update(encrypted)
    }

    private fun writeIntToDigest(digest: MessageDigest, value: Int) {
        digest.update(((value ushr 24) and 0xFF).toByte())
        digest.update(((value ushr 16) and 0xFF).toByte())
        digest.update(((value ushr 8) and 0xFF).toByte())
        digest.update((value and 0xFF).toByte())
    }

    private fun deriveSegmentIv(baseNonce: ByteArray, index: Int): ByteArray {
        val iv = baseNonce.copyOf()
        iv[8] = (iv[8].toInt() xor ((index ushr 24) and 0xFF)).toByte()
        iv[9] = (iv[9].toInt() xor ((index ushr 16) and 0xFF)).toByte()
        iv[10] = (iv[10].toInt() xor ((index ushr 8) and 0xFF)).toByte()
        iv[11] = (iv[11].toInt() xor (index and 0xFF)).toByte()
        return iv
    }

    private fun segmentAadBytes(index: Int): ByteArray {
        return byteArrayOf(
            ((index ushr 24) and 0xFF).toByte(),
            ((index ushr 16) and 0xFF).toByte(),
            ((index ushr 8) and 0xFF).toByte(),
            (index and 0xFF).toByte()
        )
    }

    private fun randomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    private fun wipe(bytes: ByteArray) {
        java.util.Arrays.fill(bytes, 0)
    }
}
