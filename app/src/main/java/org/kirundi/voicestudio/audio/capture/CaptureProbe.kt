package org.kirundi.voicestudio.audio.capture

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import org.kirundi.voicestudio.audio.device.encodingLabel
import org.kirundi.voicestudio.audio.device.typeLabel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/** Résultat d'un essai d'ouverture du micro avec un format donné. */
data class ProbeResult(val title: String, val lines: List<String>)

/** Compteurs pendant la lecture. */
private class Stats {
    var total = 0L
    var nonZero = 0L
    var lowBitsUsed = 0L
    var peak = 0.0
}

/**
 * Ouvre réellement le micro en 48 kHz mono avec plusieurs formats et dit ce
 * qu'Android accorde. Rien n'est enregistré sur disque.
 */
object CaptureProbe {

    private const val SAMPLE_RATE = 48000
    private const val SOURCE_MIC = 1
    private const val SOURCE_UNPROCESSED = 9
    private const val ENC_24_PACKED = 21
    private const val TEST_MS = 3000L
    private const val WARMUP_MS = 400L

    fun unprocessedSupport(context: Context): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val value: String? = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
        return when (value) {
            "true" -> "oui"
            null -> "non annoncé"
            else -> "non"
        }
    }

    fun runAll(context: Context, onStatus: (String) -> Unit): List<ProbeResult> {
        val useUnprocessed = unprocessedSupport(context) == "oui"
        val source = if (useUnprocessed) SOURCE_UNPROCESSED else SOURCE_MIC
        val sourceName = if (useUnprocessed) "UNPROCESSED (sans traitement)" else "MIC (source standard)"

        val tests = mutableListOf<Pair<Int, String>>()
        if (Build.VERSION.SDK_INT >= 31) {
            tests.add(ENC_24_PACKED to "PCM 24 bits (packed)")
        }
        tests.add(AudioFormat.ENCODING_PCM_FLOAT to "PCM float 32 bits")
        tests.add(AudioFormat.ENCODING_PCM_16BIT to "PCM 16 bits")

        val results = mutableListOf<ProbeResult>()
        for ((encoding, label) in tests) {
            onStatus(label)
            results.add(probeOne(context, source, sourceName, encoding, label))
        }
        return results
    }

    private fun probeOne(
        context: Context,
        source: Int,
        sourceName: String,
        encoding: Int,
        label: String
    ): ProbeResult {
        val lines = mutableListOf<String>()
        lines.add("Demandé : $label, $SAMPLE_RATE Hz, mono, source $sourceName")
        var record: AudioRecord? = null
        try {
            val bytesPerSample = when (encoding) {
                ENC_24_PACKED -> 3
                AudioFormat.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding
            )
            val bufSize = if (minBuf > 0) {
                maxOf(minBuf, SAMPLE_RATE * bytesPerSample / 5)
            } else {
                SAMPLE_RATE * bytesPerSample
            }
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .build()
            record = rec

            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                lines.add("Résultat : REFUSÉ (le micro n'a pas pu s'ouvrir avec ce format)")
                return ProbeResult(label, lines)
            }

            val granted = rec.format
            val actualEnc = granted.encoding
            lines.add(
                "Accordé : ${encodingLabel(actualEnc)}, ${granted.sampleRate} Hz, " +
                    "${granted.channelCount} canal(aux)"
            )
            if (actualEnc != encoding || granted.sampleRate != SAMPLE_RATE) {
                lines.add("ATTENTION : le format accordé diffère de celui demandé.")
            }

            val sid = rec.audioSessionId
            lines.add(
                effectLine("Contrôle auto du gain (AGC)", AutomaticGainControl.isAvailable()) {
                    val e = AutomaticGainControl.create(sid)
                    val on = e?.enabled
                    e?.release()
                    on
                }
            )
            lines.add(
                effectLine("Réduction de bruit", NoiseSuppressor.isAvailable()) {
                    val e = NoiseSuppressor.create(sid)
                    val on = e?.enabled
                    e?.release()
                    on
                }
            )
            lines.add(
                effectLine("Annulation d'écho", AcousticEchoCanceler.isAvailable()) {
                    val e = AcousticEchoCanceler.create(sid)
                    val on = e?.enabled
                    e?.release()
                    on
                }
            )

            val stats = Stats()
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                lines.add("Résultat : le micro n'a pas démarré (occupé par une autre application ?)")
                return ProbeResult(label, lines)
            }

            val routed = rec.routedDevice
            lines.add(
                "Entrée réellement utilisée : " +
                    if (routed != null) "${routed.productName} (${typeLabel(routed.type)})" else "non précisée par Android"
            )

            val bytes = ByteArray(14400) // multiple de 3
            val shorts = ShortArray(4800)
            val floats = FloatArray(4800)
            val start = System.nanoTime()
            var readError = 0
            while (true) {
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs >= TEST_MS) break
                val keep = elapsedMs >= WARMUP_MS
                when (actualEnc) {
                    ENC_24_PACKED -> {
                        val n = rec.read(bytes, 0, bytes.size)
                        if (n < 0) { readError = n; break }
                        if (keep) {
                            val count = n / 3
                            for (i in 0 until count) {
                                val b0 = bytes[3 * i].toInt()
                                val b1 = bytes[3 * i + 1].toInt()
                                val b2 = bytes[3 * i + 2].toInt() // signé : étend le signe
                                val v = (b2 shl 16) or ((b1 and 0xFF) shl 8) or (b0 and 0xFF)
                                stats.total++
                                if (v != 0) stats.nonZero++
                                if ((b0 and 0xFF) != 0) stats.lowBitsUsed++
                                val a = abs(v) / 8388608.0
                                if (a > stats.peak) stats.peak = a
                            }
                        }
                    }
                    AudioFormat.ENCODING_PCM_FLOAT -> {
                        val n = rec.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
                        if (n < 0) { readError = n; break }
                        if (keep) {
                            for (i in 0 until n) {
                                val x = floats[i].toDouble()
                                stats.total++
                                if (x != 0.0) stats.nonZero++
                                val scaled = x * 32768.0 // exact : multiplication par une puissance de 2
                                if (abs(scaled - Math.rint(scaled)) > 1e-9) stats.lowBitsUsed++
                                val a = abs(x)
                                if (a > stats.peak) stats.peak = a
                            }
                        }
                    }
                    else -> {
                        val n = rec.read(shorts, 0, shorts.size)
                        if (n < 0) { readError = n; break }
                        if (keep) {
                            for (i in 0 until n) {
                                val s = shorts[i].toInt()
                                stats.total++
                                if (s != 0) stats.nonZero++
                                val a = abs(s) / 32768.0
                                if (a > stats.peak) stats.peak = a
                            }
                        }
                    }
                }
            }

            if (readError != 0) {
                lines.add("Erreur de lecture (code $readError)")
            }
            lines.add("Échantillons analysés : ${stats.total}")
            lines.add("Crête mesurée : ${dbfs(stats.peak)}")
            lines.add(depthLine(actualEnc, stats))
        } catch (e: Exception) {
            lines.add("Résultat : ERREUR (${e.javaClass.simpleName} : ${e.message})")
        } finally {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            record?.release()
        }
        return ProbeResult(label, lines)
    }

    private fun depthLine(actualEnc: Int, s: Stats): String {
        if (actualEnc != ENC_24_PACKED && actualEnc != AudioFormat.ENCODING_PCM_FLOAT) {
            return "Capture native 24 bits : NON (le micro fournit du 16 bits dans ce format)"
        }
        if (s.total == 0L) {
            return "Capture native 24 bits : INDÉTERMINÉ (aucune donnée lue)"
        }
        if (s.nonZero < 200) {
            return "Capture native 24 bits : INDÉTERMINÉ (silence numérique ou signal trop faible, parlez pendant le test)"
        }
        return if (s.lowBitsUsed == 0L) {
            "Capture native 24 bits : NON (les bits de poids faible sont toujours à zéro : 16 bits habillé dans un conteneur plus grand)"
        } else {
            "Capture native 24 bits : OUI probable (les bits de poids faible varient ; c'est un indice, pas une preuve absolue)"
        }
    }

    private fun effectLine(name: String, available: Boolean, enabledProvider: () -> Boolean?): String {
        if (!available) return "$name : non disponible sur cet appareil"
        return try {
            when (enabledProvider()) {
                true -> "$name : disponible, activé par défaut sur cette session"
                false -> "$name : disponible, désactivé par défaut"
                null -> "$name : disponible, état non lisible"
            }
        } catch (e: Exception) {
            "$name : disponible, état non lisible"
        }
    }

    private fun dbfs(peak: Double): String {
        if (peak <= 0.0) return "silence numérique (0)"
        return String.format(Locale.US, "%.1f dBFS", 20.0 * log10(peak))
    }
}
