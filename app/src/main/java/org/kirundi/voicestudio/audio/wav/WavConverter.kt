package org.kirundi.voicestudio.audio.wav

import android.content.Context
import android.net.Uri
import org.kirundi.voicestudio.data.files.RecordingStore
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestOutputStream
import java.security.MessageDigest

class ConversionResult(val ok: Boolean, val message: String, val sha256: String?)

/**
 * Export WAV 24 bits d'un enregistrement 16 bits (comme le fait Audacity) :
 * chaque échantillon est simplement décalé de 8 bits (valeur x 256), sans aucune perte ni ajout.
 * Le fichier est un WAV 24 bits valide, mais son contenu reste celui d'un enregistrement 16 bits :
 * ce n'est PAS une capture native en 24 bits. L'original n'est jamais modifié.
 */
object WavConverter {

    private fun header(sampleRate: Int, channels: Int, dataSize: Long): ByteArray {
        val blockAlign = channels * 3
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt((36L + dataSize).toInt())
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * blockAlign)
        bb.putShort(blockAlign.toShort())
        bb.putShort(24)
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(dataSize.toInt())
        return bb.array()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format("%02x", it) }

    fun exportTo24(context: Context, src: File, uri: Uri): ConversionResult {
        val info = RecordingStore.info(src)
            ?: return ConversionResult(false, "Export 24 bits impossible : en-tête WAV illisible.", null)
        if (info.bits != 16) {
            return ConversionResult(
                false,
                "Export 24 bits impossible : le fichier est en ${info.bits} bits (la conversion ne concerne que le 16 bits).",
                null
            )
        }
        val available = minOf(info.dataBytes, src.length() - 44)
        val samples = available / 2
        if (samples <= 0L) return ConversionResult(false, "Export 24 bits impossible : fichier vide.", null)
        val outData = samples * 3

        try {
            val md = MessageDigest.getInstance("SHA-256")
            val rawOut = context.contentResolver.openOutputStream(uri)
                ?: return ConversionResult(false, "Export 24 bits impossible : destination illisible.", null)
            rawOut.use { raw ->
                val out = BufferedOutputStream(DigestOutputStream(raw, md), 65536)
                out.write(header(info.sampleRate, info.channels, outData))
                DataInputStream(src.inputStream().buffered()).use { ins ->
                    var toSkip = 44L
                    while (toSkip > 0) {
                        val s = ins.skip(toSkip)
                        if (s <= 0) break
                        toSkip -= s
                    }
                    val inBuf = ByteArray(8192)
                    val outBuf = ByteArray(8192 / 2 * 3)
                    var remaining = samples * 2
                    while (remaining > 0) {
                        val n = minOf(inBuf.size.toLong(), remaining).toInt()
                        ins.readFully(inBuf, 0, n)
                        var j = 0
                        var i = 0
                        while (i < n) {
                            outBuf[j] = 0
                            outBuf[j + 1] = inBuf[i]
                            outBuf[j + 2] = inBuf[i + 1]
                            j += 3
                            i += 2
                        }
                        out.write(outBuf, 0, j)
                        remaining -= n
                    }
                }
                out.flush()
            }
            val written = hex(md.digest())

            // Vérification : on relit le fichier exporté et on le compare à l'original.
            val inStream = context.contentResolver.openInputStream(uri)
                ?: return ConversionResult(false, "Export écrit mais impossible à relire pour vérification.", written)
            val md2 = MessageDigest.getInstance("SHA-256")
            var identical = true
            var checked = 0L
            DataInputStream(inStream.buffered()).use { exp ->
                val h = ByteArray(44)
                exp.readFully(h)
                md2.update(h)
                val hb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                if (hb.getShort(34).toInt() != 24 || (hb.getInt(40).toLong() and 0xFFFFFFFFL) != outData) {
                    identical = false
                }
                DataInputStream(src.inputStream().buffered()).use { orig ->
                    var toSkip = 44L
                    while (toSkip > 0) {
                        val s = orig.skip(toSkip)
                        if (s <= 0) break
                        toSkip -= s
                    }
                    val a = ByteArray(4096 * 2)
                    val b = ByteArray(4096 * 3)
                    var remaining = samples
                    while (remaining > 0) {
                        val cnt = minOf(4096L, remaining).toInt()
                        orig.readFully(a, 0, cnt * 2)
                        exp.readFully(b, 0, cnt * 3)
                        md2.update(b, 0, cnt * 3)
                        for (k in 0 until cnt) {
                            if (b[3 * k].toInt() != 0 ||
                                b[3 * k + 1] != a[2 * k] ||
                                b[3 * k + 2] != a[2 * k + 1]
                            ) {
                                identical = false
                            }
                        }
                        checked += cnt
                        remaining -= cnt
                    }
                }
            }
            val reread = hex(md2.digest())
            if (!identical || reread != written) {
                return ConversionResult(
                    false,
                    "ATTENTION : la vérification de l'export 24 bits a échoué (le fichier exporté ne correspond pas à l'original).",
                    written
                )
            }
            return ConversionResult(
                true,
                "Export 24 bits réussi et vérifié : $checked échantillons identiques à l'original (valeur x 256, sans perte). " +
                    "CONVERTI depuis du 16 bits natif : ce n'est pas une capture native en 24 bits. " +
                    "SHA-256 : ${written.take(16)}...",
                written
            )
        } catch (e: Exception) {
            return ConversionResult(false, "Export 24 bits échoué : ${e.message}", null)
        }
    }
}
