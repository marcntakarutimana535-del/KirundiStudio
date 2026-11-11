package org.kirundi.voicestudio.audio.wav

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Écrit un fichier WAV PCM non compressé, au fur et à mesure.
 * L'en-tête (44 octets) est remis à jour régulièrement : si l'application plante,
 * le fichier reste lisible, il manque au plus quelques secondes.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int
) {
    companion object {
        const val HEADER_SIZE = 44L
    }

    private val raf = RandomAccessFile(file, "rw")

    var dataBytes: Long = 0L
        private set

    init {
        raf.setLength(0)
        raf.write(header(0L))
    }

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        raf.write(buffer, offset, length)
        dataBytes += length
    }

    /** Remet à jour les tailles dans l'en-tête, puis revient en fin de fichier. */
    fun updateHeader() {
        raf.seek(0)
        raf.write(header(dataBytes))
        raf.seek(HEADER_SIZE + dataBytes)
    }

    fun close() {
        try {
            updateHeader()
            raf.fd.sync()
        } finally {
            raf.close()
        }
    }

    private fun header(dataSize: Long): ByteArray {
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val bb = ByteBuffer.allocate(HEADER_SIZE.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36L + dataSize).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1) // PCM non compressé
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize.toInt())
        return bb.array()
    }
}
