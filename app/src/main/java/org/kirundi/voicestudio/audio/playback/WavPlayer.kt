package org.kirundi.voicestudio.audio.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import org.kirundi.voicestudio.data.files.WavInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Lecteur de fichiers WAV de l'application (16 ou 24 bits, mono ou stéréo).
 * La lecture ne modifie jamais le fichier. Aucun traitement n'est appliqué au son :
 * seul le volume du téléphone agit. Un fichier 24 bits est envoyé en 16 bits à la sortie
 * audio (pour l'écoute seulement).
 */
class WavPlayer(private val file: File, val info: WavInfo) {

    private val frameBytes = info.channels * (info.bits / 8)
    val totalFrames: Long = min(info.dataBytes, max(0L, file.length() - 44)) / frameBytes
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var gen = 0
    @Volatile private var track: AudioTrack? = null
    @Volatile private var startFrame = 0L
    @Volatile private var pausedFrame = 0L

    @Volatile var playing: Boolean = false
        private set

    @Volatile var levelDb: Double = -120.0
        private set

    @Volatile var error: String = ""
        private set

    var onEnded: (() -> Unit)? = null

    companion object {
        fun supported(info: WavInfo): Boolean =
            (info.bits == 16 || info.bits == 24) && (info.channels == 1 || info.channels == 2) && info.sampleRate > 0
    }

    fun positionFrames(): Long {
        val t = track
        if (!playing || t == null) return pausedFrame
        val head = try {
            t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        } catch (e: Exception) {
            0L
        }
        return min(totalFrames, startFrame + head)
    }

    @Synchronized
    fun play(fromFrame: Long? = null) {
        stopInternal()
        error = ""
        var from = fromFrame ?: pausedFrame
        if (from >= totalFrames || from < 0) from = 0
        startFrame = from

        val mask = if (info.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(info.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            error = "Lecture impossible : format audio non accepté par le téléphone."
            return
        }
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(info.sampleRate)
                        .setChannelMask(mask)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            error = "Lecture impossible : ${e.message}"
            return
        }
        gen += 1
        val myGen = gen
        track = t
        playing = true
        try {
            t.play()
        } catch (e: Exception) {
            error = "Lecture impossible : ${e.message}"
            stopInternal()
            return
        }
        val begin = from
        Thread {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(44L + begin * frameBytes)
                    val chunkFrames = 2400
                    val bytes = ByteArray(chunkFrames * frameBytes)
                    val shorts = ShortArray(chunkFrames * info.channels)
                    var frame = begin
                    while (myGen == gen && frame < totalFrames) {
                        val n = min(chunkFrames.toLong(), totalFrames - frame).toInt()
                        raf.readFully(bytes, 0, n * frameBytes)
                        val samples = n * info.channels
                        var peak = 0
                        if (info.bits == 16) {
                            for (i in 0 until samples) {
                                val v = (bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)
                                shorts[i] = v.toShort()
                                peak = max(peak, abs(v))
                            }
                        } else {
                            for (i in 0 until samples) {
                                val v = (bytes[3 * i + 2].toInt() shl 8) or (bytes[3 * i + 1].toInt() and 0xFF)
                                shorts[i] = v.toShort()
                                peak = max(peak, abs(v))
                            }
                        }
                        levelDb = if (peak > 0) 20.0 * log10(peak / 32768.0) else -120.0
                        t.write(shorts, 0, samples)
                        frame += n
                    }
                }
                if (myGen == gen) {
                    val expected = totalFrames - begin
                    var waited = 0
                    while (myGen == gen && (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < expected && waited < 2500) {
                        Thread.sleep(30)
                        waited += 30
                    }
                    synchronized(this@WavPlayer) {
                        if (myGen == gen) {
                            stopInternal()
                            pausedFrame = 0
                            levelDb = -120.0
                            main.post { onEnded?.invoke() }
                        }
                    }
                }
            } catch (e: Exception) {
                if (myGen == gen) {
                    error = "Erreur de lecture : ${e.message}"
                    synchronized(this@WavPlayer) {
                        if (myGen == gen) {
                            stopInternal()
                            main.post { onEnded?.invoke() }
                        }
                    }
                }
            }
        }.start()
    }

    private fun stopInternal() {
        gen += 1
        val t = track
        track = null
        playing = false
        if (t != null) {
            try { t.stop() } catch (e: Exception) { }
            try { t.release() } catch (e: Exception) { }
        }
    }

    @Synchronized
    fun pause() {
        if (playing) {
            pausedFrame = positionFrames()
            stopInternal()
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
        pausedFrame = 0
        levelDb = -120.0
    }

    @Synchronized
    fun seek(frame: Long) {
        val f = frame.coerceIn(0L, totalFrames)
        if (playing) play(f) else pausedFrame = f
    }

    fun release() {
        stop()
    }
}

/** Enveloppe de crête (en dBFS) d'un fichier, pour dessiner la forme d'onde. */
object WaveformReader {
    fun read(file: File, info: WavInfo, buckets: Int): DoubleArray {
        val frameBytes = info.channels * (info.bits / 8)
        val total = min(info.dataBytes, max(0L, file.length() - 44)) / frameBytes
        val peaks = IntArray(buckets)
        if (total <= 0L) return DoubleArray(buckets) { -120.0 }
        val bucketFrames = max(1L, (total + buckets - 1) / buckets)
        val bytesPerSample = info.bits / 8
        BufferedInputStream(file.inputStream(), 65536).use { ins ->
            var toSkip = 44L
            while (toSkip > 0) {
                val s = ins.skip(toSkip)
                if (s <= 0) break
                toSkip -= s
            }
            val chunkFrames = 8192
            val buf = ByteArray(chunkFrames * frameBytes)
            var frame = 0L
            while (frame < total) {
                val n = min(chunkFrames.toLong(), total - frame).toInt()
                var got = 0
                val need = n * frameBytes
                while (got < need) {
                    val r = ins.read(buf, got, need - got)
                    if (r < 0) break
                    got += r
                }
                val frames = got / frameBytes
                if (frames <= 0) break
                for (f in 0 until frames) {
                    var p = 0
                    for (c in 0 until info.channels) {
                        val idx = (f * info.channels + c) * bytesPerSample
                        val v = (buf[idx + bytesPerSample - 1].toInt() shl 8) or
                            (buf[idx + bytesPerSample - 2].toInt() and 0xFF)
                        p = max(p, abs(v))
                    }
                    val b = min(buckets - 1L, (frame + f) / bucketFrames).toInt()
                    if (p > peaks[b]) peaks[b] = p
                }
                frame += frames
            }
        }
        return DoubleArray(buckets) { i ->
            if (peaks[i] > 0) 20.0 * log10(peaks[i] / 32768.0) else -120.0
        }
    }
}
