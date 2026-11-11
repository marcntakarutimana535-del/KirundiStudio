package org.kirundi.voicestudio.data.files

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Informations lues dans l'en-tête d'un fichier WAV. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bits: Int, val dataBytes: Long) {
    val seconds: Double
        get() {
            val bytesPerSecond = sampleRate.toLong() * channels * (bits / 8)
            return if (bytesPerSecond > 0) dataBytes.toDouble() / bytesPerSecond else 0.0
        }
}

fun sha256Hex(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    val buf = ByteArray(65536)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        md.update(buf, 0, n)
    }
    return md.digest().joinToString("") { String.format("%02x", it) }
}

/** Dossier privé de l'application où sont rangés les enregistrements. */
object RecordingStore {

    fun dir(context: Context): File {
        val d = File(context.filesDir, "recordings")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun list(context: Context): List<File> {
        val files = dir(context).listFiles { f -> f.extension.lowercase(Locale.ROOT) == "wav" }
        return files?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Crée un nom de fichier sûr et qui n'écrase jamais un fichier existant. */
    fun newFile(context: Context, rawName: String): File {
        val cleaned = rawName.trim()
            .replace(Regex("[^\\p{L}\\p{N}_.-]"), "_")
            .trim('_', '.')
            .take(60)
        val base = if (cleaned.isEmpty()) {
            "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        } else {
            cleaned
        }
        val d = dir(context)
        var f = File(d, "$base.wav")
        var i = 2
        while (f.exists()) {
            f = File(d, "${base}_$i.wav")
            i++
        }
        return f
    }

    fun sidecar(wav: File): File = File(wav.parentFile, wav.nameWithoutExtension + ".json")

    fun info(file: File): WavInfo? {
        if (file.length() < 44) return null
        val h = ByteArray(44)
        try {
            RandomAccessFile(file, "r").use { it.readFully(h) }
        } catch (e: Exception) {
            return null
        }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        return WavInfo(
            sampleRate = bb.getInt(24),
            channels = bb.getShort(22).toInt(),
            bits = bb.getShort(34).toInt(),
            dataBytes = bb.getInt(40).toLong() and 0xFFFFFFFFL
        )
    }

    /**
     * Après une interruption brutale, remet l'en-tête en accord avec la taille réelle du fichier.
     * À n'appeler que lorsqu'aucun enregistrement n'est en cours.
     */
    fun repairAll(context: Context): Int {
        var repaired = 0
        for (f in list(context)) {
            if (repairIfNeeded(f)) repaired++
        }
        return repaired
    }

    private fun repairIfNeeded(file: File): Boolean {
        if (file.length() < 44) return false
        var changed = false
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val h = ByteArray(44)
                raf.readFully(h)
                val ok = String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(h, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                    String(h, 36, 4, Charsets.US_ASCII) == "data"
                if (ok) {
                    val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                    val blockAlign = bb.getShort(32).toInt()
                    val declared = bb.getInt(40).toLong() and 0xFFFFFFFFL
                    var actual = file.length() - 44
                    if (blockAlign > 0) actual -= actual % blockAlign
                    if (declared != actual) {
                        bb.putInt(4, (36L + actual).toInt())
                        bb.putInt(40, actual.toInt())
                        raf.seek(0)
                        raf.write(h)
                        raf.setLength(44 + actual)
                        changed = true
                    }
                }
            }
        } catch (e: Exception) {
            return false
        }
        return changed
    }

    fun delete(wav: File) {
        wav.delete()
        sidecar(wav).delete()
    }

    /** Copie le fichier vers l'emplacement choisi puis vérifie la copie (SHA-256). */
    fun exportTo(context: Context, src: File, uri: Uri): String {
        return try {
            val out = context.contentResolver.openOutputStream(uri)
                ?: return "Export impossible : destination illisible."
            src.inputStream().use { input -> out.use { o -> input.copyTo(o) } }
            val expected = src.inputStream().use { sha256Hex(it) }
            val actual = context.contentResolver.openInputStream(uri)?.use { sha256Hex(it) }
            if (actual == expected) {
                "Export réussi et vérifié (SHA-256 identique) : ${src.name}"
            } else {
                "ATTENTION : la copie exportée est différente de l'original (SHA-256 différent)."
            }
        } catch (e: Exception) {
            "Export échoué : ${e.message}"
        }
    }
}
