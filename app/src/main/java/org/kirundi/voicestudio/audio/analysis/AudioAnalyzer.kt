package org.kirundi.voicestudio.audio.analysis

import org.kirundi.voicestudio.data.files.RecordingStore
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Mesures brutes d'un fichier WAV : une valeur RMS et Peak par fenêtre de 100 ms. */
class FileAnalysis(
    val fileName: String,
    val sampleRate: Int,
    val bits: Int,
    val seconds: Double,
    val windowSeconds: Double,
    val rmsDb: DoubleArray,
    val peakDb: DoubleArray,
    val globalRmsDb: Double,
    val globalPeakDb: Double,
    val dcOffsetDb: Double,
    val clipEvents: Int,
    val clipTimes: List<Double>,
    val dropouts: Int,
    val dropoutTimes: List<Double>,
    val jumps: Int
)

/** Lit le fichier en flux, par fenêtres : aucune donnée n'est modifiée, le fichier reste intact. */
object AudioAnalyzer {

    private const val CLIP_THRESHOLD = 0.999
    private const val JUMP_DB = 25.0

    private fun db(x: Double): Double = if (x <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(x)

    fun analyze(file: File): FileAnalysis {
        val info = RecordingStore.info(file)
            ?: throw IllegalArgumentException("En-tête WAV illisible.")
        if (info.channels != 1) {
            throw IllegalArgumentException("Seuls les fichiers mono sont analysés (ici : ${info.channels} canaux).")
        }
        if (info.bits != 16 && info.bits != 24) {
            throw IllegalArgumentException("Profondeur non prise en charge : ${info.bits} bits (16 ou 24 attendus).")
        }
        val sr = info.sampleRate
        if (sr < 8000) throw IllegalArgumentException("Fréquence d'échantillonnage invalide : $sr Hz.")

        val bps = info.bits / 8
        val window = sr / 10
        val windowBytes = window * bps
        val dataBytes = minOf(info.dataBytes, file.length() - 44)
        val maxWindows = (dataBytes / windowBytes).toInt()
        if (maxWindows <= 0) {
            throw IllegalArgumentException("Fichier trop court pour être analysé (moins de 100 ms).")
        }

        val rms = DoubleArray(maxWindows)
        val peak = DoubleArray(maxWindows)
        val buf = ByteArray(windowBytes)
        val fullScale = if (info.bits == 16) 32768.0 else 8388608.0
        val zeroRunMin = sr / 100 // 10 ms

        var count = 0
        var totalSamples = 0L
        var sumSqAll = 0.0
        var sumAll = 0.0
        var peakAll = 0.0
        var clipRun = 0
        var clipEvents = 0
        val clipTimes = ArrayList<Double>()
        var zeroRun = 0
        var dropouts = 0
        val dropTimes = ArrayList<Double>()
        var jumps = 0
        var prev = Double.NaN

        BufferedInputStream(FileInputStream(file), 65536).use { ins ->
            var toSkip = 44L
            while (toSkip > 0) {
                val s = ins.skip(toSkip)
                if (s <= 0) break
                toSkip -= s
            }
            for (w in 0 until maxWindows) {
                var filled = 0
                while (filled < windowBytes) {
                    val n = ins.read(buf, filled, windowBytes - filled)
                    if (n < 0) break
                    filled += n
                }
                if (filled < windowBytes) break

                var sumSq = 0.0
                var pk = 0.0
                for (i in 0 until window) {
                    val v: Int
                    if (bps == 2) {
                        v = (buf[2 * i].toInt() and 0xFF) or (buf[2 * i + 1].toInt() shl 8)
                    } else {
                        val b0 = buf[3 * i].toInt()
                        val b1 = buf[3 * i + 1].toInt()
                        val b2 = buf[3 * i + 2].toInt()
                        v = (b2 shl 16) or ((b1 and 0xFF) shl 8) or (b0 and 0xFF)
                    }
                    val a = v / fullScale
                    val mag = abs(a)
                    sumSq += a * a
                    sumAll += a
                    if (mag > pk) pk = mag
                    val idx = totalSamples + i

                    if (mag >= CLIP_THRESHOLD) {
                        clipRun++
                        if (clipRun == 3) {
                            clipEvents++
                            if (clipTimes.size < 50) clipTimes.add(idx.toDouble() / sr)
                        }
                    } else {
                        clipRun = 0
                    }

                    if (v == 0) {
                        zeroRun++
                        if (zeroRun == zeroRunMin) {
                            dropouts++
                            if (dropTimes.size < 50) dropTimes.add((idx - zeroRunMin + 1).toDouble() / sr)
                        }
                    } else {
                        zeroRun = 0
                    }
                }

                totalSamples += window
                sumSqAll += sumSq
                if (pk > peakAll) peakAll = pk
                rms[w] = db(sqrt(sumSq / window))
                peak[w] = db(pk)
                if (!prev.isNaN() && !prev.isInfinite() && !rms[w].isInfinite()) {
                    if (abs(rms[w] - prev) >= JUMP_DB) jumps++
                }
                prev = rms[w]
                count++
            }
        }

        if (count == 0) throw IllegalArgumentException("Aucune donnée lue dans le fichier.")

        val meanAll = sumAll / totalSamples
        return FileAnalysis(
            fileName = file.name,
            sampleRate = sr,
            bits = info.bits,
            seconds = totalSamples.toDouble() / sr,
            windowSeconds = window.toDouble() / sr,
            rmsDb = rms.copyOf(count),
            peakDb = peak.copyOf(count),
            globalRmsDb = db(sqrt(sumSqAll / totalSamples)),
            globalPeakDb = db(peakAll),
            dcOffsetDb = db(abs(meanAll)),
            clipEvents = clipEvents,
            clipTimes = clipTimes,
            dropouts = dropouts,
            dropoutTimes = dropTimes,
            jumps = jumps
        )
    }
}
