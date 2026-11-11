package org.kirundi.voicestudio.audio.capture

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import org.json.JSONObject
import org.kirundi.voicestudio.audio.wav.WavWriter
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.data.files.sha256Hex
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

enum class RecState { IDLE, RECORDING, PAUSED }

/** Niveaux affichés en direct (en dBFS ; moins l'infini = silence numérique). */
data class LevelInfo(
    val rmsDb: Double,
    val peakDb: Double,
    val maxPeakDb: Double,
    val clipEvents: Int,
    val seconds: Double,
    val paused: Boolean
) {
    companion object {
        val EMPTY = LevelInfo(
            Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            0, 0.0, false
        )
    }
}

data class RecordingSummary(val file: File, val seconds: Double, val message: String)

fun formatDb(db: Double): String =
    if (db.isInfinite() || db.isNaN()) "-inf dBFS" else String.format(Locale.US, "%.1f dBFS", db)

/**
 * Moteur d'enregistrement : micro -> fichier WAV, sans aucun traitement.
 * L'analyse des niveaux ne modifie jamais les données écrites.
 */
class Recorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 48000
        private const val SOURCE_MIC = 1
        private const val SOURCE_VOICE_RECOGNITION = 6
        private const val SOURCE_UNPROCESSED = 9
        private const val ENC_24_PACKED = 21
        private const val CLIP_THRESHOLD = 0.999

        fun pickInput(context: Context): AudioDeviceInfo? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val all = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val order = listOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_MIC
            )
            for (t in order) {
                val d = all.firstOrNull { it.type == t }
                if (d != null) return d
            }
            return null
        }

        private fun announces24(d: AudioDeviceInfo?): Boolean =
            Build.VERSION.SDK_INT >= 31 && d != null && d.encodings.contains(ENC_24_PACKED)

        fun plannedFormat(context: Context): String {
            return if (announces24(pickInput(context))) {
                "WAV PCM 24 bits, 48 kHz, mono (le micro annonce le 24 bits ; la profondeur réelle sera vérifiée)"
            } else {
                "WAV PCM 16 bits, 48 kHz, mono (le micro n'annonce que du 16 bits : pas de 24 bits natif)"
            }
        }

        fun plannedBits(context: Context): Int = if (announces24(pickInput(context))) 24 else 16

        fun sourceName(choice: Int): String = when (choice) {
            0 -> "UNPROCESSED"
            1 -> "VOICE_RECOGNITION"
            else -> "MIC"
        }

        private fun sourceCode(choice: Int): Int = when (choice) {
            0 -> SOURCE_UNPROCESSED
            1 -> SOURCE_VOICE_RECOGNITION
            else -> SOURCE_MIC
        }

        private fun db(x: Double): Double = if (x <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(x)
    }

    @Volatile
    private var paused = false

    @Volatile
    private var stopRequested = false

    @Volatile
    private var worker: Thread? = null

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    fun stop() {
        stopRequested = true
    }

    /** Démarre l'enregistrement. Retourne un message d'erreur, ou null si tout va bien. */
    fun start(
        file: File,
        sourceChoice: Int,
        onLevel: (LevelInfo) -> Unit,
        onFinished: (RecordingSummary) -> Unit
    ): String? {
        if (worker != null) return "Un enregistrement est déjà en cours."

        val device = pickInput(context)
        val use24 = announces24(device)
        val encoding = if (use24) ENC_24_PACKED else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerSample = if (use24) 3 else 2
        val bits = bytesPerSample * 8

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        val bufSize = maxOf(if (minBuf > 0) minBuf * 4 else 0, SAMPLE_RATE * bytesPerSample)

        val rec: AudioRecord = try {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            AudioRecord.Builder()
                .setAudioSource(sourceCode(sourceChoice))
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
        } catch (e: Exception) {
            return "Impossible d'ouvrir le micro avec la source ${sourceName(sourceChoice)} : ${e.message}"
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return "Le micro ne s'est pas initialisé avec la source ${sourceName(sourceChoice)}. Essayez une autre source."
        }
        if (device != null) rec.setPreferredDevice(device)

        val granted = rec.format
        if (granted.encoding != encoding || granted.sampleRate != SAMPLE_RATE || granted.channelCount != 1) {
            rec.release()
            return "Android n'accorde pas le format demandé (accordé : ${granted.sampleRate} Hz, " +
                "${granted.channelCount} canal(aux), code ${granted.encoding}). Enregistrement annulé."
        }

        val writer: WavWriter = try {
            WavWriter(file, SAMPLE_RATE, 1, bits)
        } catch (e: IOException) {
            rec.release()
            return "Impossible de créer le fichier : ${e.message}"
        }

        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            writer.close()
            file.delete()
            return "Le micro n'a pas démarré : ${e.message}"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            writer.close()
            file.delete()
            return "Le micro n'a pas démarré (occupé par une autre application ?)."
        }

        paused = false
        stopRequested = false
        val startMillis = System.currentTimeMillis()
        val routed = rec.routedDevice
        val routedName = if (routed != null) "${routed.productName}" else "non précisé"
        val chosenName = if (device != null) "${device.productName}" else "aucun"
        val bytesPerSecond = (SAMPLE_RATE * bytesPerSample).toDouble()

        val t = Thread {
            var error: String? = null
            var sumSq = 0.0
            var recSamples = 0L
            var peakMax = 0.0
            var clipEvents = 0
            var clipRun = 0
            var nonZero = 0L
            var lowBits = 0L
            var lastHeader = System.nanoTime()
            val bytes = ByteArray(14400)
            val shorts = ShortArray(4800)
            val out16 = ByteBuffer.allocate(9600).order(ByteOrder.LITTLE_ENDIAN)

            try {
                while (!stopRequested) {
                    var frames = 0
                    if (use24) {
                        val n = rec.read(bytes, 0, bytes.size)
                        if (n < 0) {
                            error = "Interruption audio (code $n)"
                            break
                        }
                        frames = n / 3
                    } else {
                        val n = rec.read(shorts, 0, shorts.size)
                        if (n < 0) {
                            error = "Interruption audio (code $n)"
                            break
                        }
                        frames = n
                    }
                    if (frames == 0) continue

                    val isRec = !paused
                    var chunkSumSq = 0.0
                    var chunkPeak = 0.0
                    for (i in 0 until frames) {
                        val a: Double
                        if (use24) {
                            val b0 = bytes[3 * i].toInt()
                            val b1 = bytes[3 * i + 1].toInt()
                            val b2 = bytes[3 * i + 2].toInt()
                            val v = (b2 shl 16) or ((b1 and 0xFF) shl 8) or (b0 and 0xFF)
                            a = v / 8388608.0
                            if (isRec) {
                                if (v != 0) nonZero++
                                if ((b0 and 0xFF) != 0) lowBits++
                            }
                        } else {
                            a = shorts[i] / 32768.0
                        }
                        val mag = abs(a)
                        chunkSumSq += a * a
                        if (mag > chunkPeak) chunkPeak = mag
                        if (isRec) {
                            if (mag >= CLIP_THRESHOLD) {
                                clipRun++
                                if (clipRun == 3) clipEvents++
                            } else {
                                clipRun = 0
                            }
                        }
                    }

                    if (isRec) {
                        sumSq += chunkSumSq
                        recSamples += frames
                        if (chunkPeak > peakMax) peakMax = chunkPeak
                        if (use24) {
                            writer.write(bytes, 0, frames * 3)
                        } else {
                            out16.clear()
                            for (i in 0 until frames) out16.putShort(shorts[i])
                            writer.write(out16.array(), 0, frames * 2)
                        }
                    }

                    val rms = sqrt(chunkSumSq / frames)
                    onLevel(
                        LevelInfo(
                            rmsDb = db(rms),
                            peakDb = db(chunkPeak),
                            maxPeakDb = db(peakMax),
                            clipEvents = clipEvents,
                            seconds = writer.dataBytes / bytesPerSecond,
                            paused = paused
                        )
                    )

                    if (System.nanoTime() - lastHeader > 2_000_000_000L) {
                        writer.updateHeader()
                        lastHeader = System.nanoTime()
                    }
                }
            } catch (e: IOException) {
                error = "Erreur d'écriture (disque plein ?) : ${e.message}"
            } catch (e: Exception) {
                error = "Erreur : ${e.javaClass.simpleName} ${e.message}"
            } finally {
                try {
                    rec.stop()
                } catch (_: Exception) {
                }
                rec.release()
            }

            try {
                writer.close()
            } catch (e: Exception) {
                if (error == null) error = "Erreur à la fermeture du fichier : ${e.message}"
            }

            val seconds = writer.dataBytes / bytesPerSecond
            worker = null

            if (writer.dataBytes == 0L) {
                RecordingStore.delete(file)
                onFinished(
                    RecordingSummary(
                        file, 0.0,
                        "Enregistrement vide : aucun échantillon écrit, fichier supprimé." +
                            (if (error != null) "\n$error" else "")
                    )
                )
                return@Thread
            }

            val rmsGlobal = if (recSamples > 0) db(sqrt(sumSq / recSamples)) else Double.NEGATIVE_INFINITY
            val nativeText = if (!use24) {
                "16 bits natifs (le micro n'annonce que du 16 bits)"
            } else if (recSamples == 0L || nonZero < 200) {
                "INDÉTERMINÉ (signal trop faible ou absent)"
            } else if (lowBits == 0L) {
                "NON : 16 bits habillé en 24 bits (bits de poids faible toujours à zéro)"
            } else {
                "OUI probable (les bits de poids faible varient ; indice, pas preuve absolue)"
            }

            var hash = "indisponible"
            try {
                hash = file.inputStream().use { sha256Hex(it) }
            } catch (_: Exception) {
            }

            try {
                val json = JSONObject()
                json.put("fichier", file.name)
                json.put(
                    "date_debut",
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(startMillis))
                )
                json.put("duree_s", seconds)
                json.put("format", "WAV PCM non compressé")
                json.put("frequence_hz", SAMPLE_RATE)
                json.put("canaux", 1)
                json.put("profondeur_wav_bits", bits)
                json.put("profondeur_native", nativeText)
                json.put("source_demandee", sourceName(sourceChoice))
                json.put("peripherique_choisi", chosenName)
                json.put("peripherique_route", routedName)
                json.put("appareil", "${Build.MANUFACTURER} ${Build.MODEL}")
                json.put("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                json.put("rms_global", formatDb(rmsGlobal))
                json.put("peak_max", formatDb(db(peakMax)))
                json.put("saturations_evenements", clipEvents)
                json.put("sha256", hash)
                json.put(
                    "traitements",
                    "Aucun effet activé par l'application. Les traitements internes du pilote ne sont pas vérifiables."
                )
                json.put("interruption", error ?: "aucune")
                RecordingStore.sidecar(file).writeText(json.toString(2))
            } catch (_: Exception) {
            }

            val msg = buildString {
                append("Enregistrement terminé : ${file.name}\n")
                append("Durée : ${String.format(Locale.US, "%.1f", seconds)} s\n")
                append("Format : WAV PCM $bits bits, 48 kHz, mono\n")
                append("Profondeur native : $nativeText\n")
                append("RMS global : ${formatDb(rmsGlobal)} | Peak max : ${formatDb(db(peakMax))}\n")
                append("Saturations détectées : $clipEvents\n")
                append("SHA-256 : ${hash.take(16)}...")
                if (error != null) append("\nATTENTION, enregistrement interrompu : $error")
            }
            onFinished(RecordingSummary(file, seconds, msg))
        }
        worker = t
        t.start()
        return null
    }
}
