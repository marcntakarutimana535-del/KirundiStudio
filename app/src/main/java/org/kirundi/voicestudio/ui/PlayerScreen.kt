package org.kirundi.voicestudio.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.kirundi.voicestudio.audio.playback.WavPlayer
import org.kirundi.voicestudio.audio.playback.WaveformReader
import org.kirundi.voicestudio.data.files.RecordingStore
import java.io.File
import java.util.Locale

private class PlayerHolder {
    var player: WavPlayer? = null
}

private fun clock(seconds: Double): String {
    val s = if (seconds < 0) 0.0 else seconds
    val m = (s / 60).toInt()
    return String.format(Locale.US, "%02d:%04.1f", m, s - m * 60)
}

@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val holder = remember { PlayerHolder() }
    val files = remember { RecordingStore.list(context) }

    var selected by remember { mutableStateOf<File?>(files.firstOrNull()) }
    var player by remember { mutableStateOf<WavPlayer?>(null) }
    var envelope by remember { mutableStateOf<DoubleArray?>(null) }
    var posFrames by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var levelDb by remember { mutableStateOf(-120.0) }
    var message by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    LaunchedEffect(selected) {
        holder.player?.release()
        holder.player = null
        player = null
        envelope = null
        posFrames = 0L
        isPlaying = false
        levelDb = -120.0
        message = ""
        val f = selected
        if (f != null) {
            val info = RecordingStore.info(f)
            if (info == null) {
                message = "Fichier illisible : en-tête WAV invalide."
            } else if (!WavPlayer.supported(info)) {
                message = "Lecture non prise en charge pour ce format (${info.bits} bits, ${info.channels} canal(aux))."
            } else {
                val p = WavPlayer(f, info)
                p.onEnded = {
                    isPlaying = false
                    posFrames = 0L
                    if (p.error.isNotEmpty()) message = p.error
                }
                holder.player = p
                player = p
                Thread {
                    val e = try { WaveformReader.read(f, info, 600) } catch (ex: Exception) { null }
                    handler.post { if (holder.player === p) envelope = e }
                }.start()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            val p = holder.player
            if (p != null) {
                posFrames = p.positionFrames()
                isPlaying = p.playing
                levelDb = p.levelDb
                if (p.error.isNotEmpty()) message = p.error
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holder.player?.release()
            holder.player = null
        }
    }

    val p = player
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Lecteur", style = MaterialTheme.typography.titleLarge)
        Text(
            "Écoute des enregistrements. Aucun traitement n'est appliqué : seul le volume du téléphone agit. " +
                "Le fichier n'est jamais modifié.",
            fontSize = 13.sp
        )

        if (files.isEmpty()) {
            Text("Aucun enregistrement à écouter.")
        }
        for (f in files) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (selected != f) selected = f
                    }
            ) {
                RadioButton(selected = selected == f, onClick = { if (selected != f) selected = f })
                Text(f.name, fontSize = 14.sp)
            }
        }

        if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)

        if (p != null) {
            val total = p.totalFrames
            val rate = p.info.sampleRate.toDouble()
            val totalSec = total / rate
            val shownFrames = if (dragging) (dragValue * total).toLong() else posFrames
            val posSec = shownFrames / rate

            Text(
                "Format : ${p.info.sampleRate} Hz, ${p.info.bits} bits, " +
                    (if (p.info.channels == 1) "mono" else "stéréo") +
                    (if (p.info.bits == 24) "\nÉcoute en 16 bits (conversion pour l'écoute seulement, le fichier reste en 24 bits)." else ""),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            )

            val env = envelope
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .pointerInput(p) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            p.seek((frac * p.totalFrames).toLong())
                            posFrames = p.positionFrames()
                        }
                    }
            ) {
                val w = size.width
                val h = size.height
                drawRect(color = Color(0xFF1C1B22))
                drawLine(Color(0xFF3A3944), Offset(0f, h / 2), Offset(w, h / 2), 1f)
                if (env != null && env.isNotEmpty()) {
                    val n = env.size
                    for (i in 0 until n) {
                        val db = env[i].coerceIn(-90.0, 0.0)
                        val amp = ((db + 90.0) / 90.0).toFloat() * (h / 2)
                        val x = (i + 0.5f) * w / n
                        drawLine(Color(0xFF7CB7FF), Offset(x, h / 2 - amp), Offset(x, h / 2 + amp), 2f)
                    }
                }
                if (total > 0) {
                    val cx = (shownFrames.toFloat() / total.toFloat()) * w
                    drawLine(Color.White, Offset(cx, 0f), Offset(cx, h), 3f)
                }
            }
            Text(
                if (env == null) "Calcul de la forme d'onde..." else "Forme d'onde : crête par tranche, échelle -90 à 0 dBFS. Touchez pour aller à un endroit.",
                fontSize = 11.sp
            )

            Slider(
                value = if (dragging) dragValue else (if (total > 0) posFrames.toFloat() / total.toFloat() else 0f),
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    p.seek((dragValue * total).toLong())
                    posFrames = p.positionFrames()
                    dragging = false
                }
            )

            Text("${clock(posSec)} / ${clock(totalSec)}", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Text(
                if (isPlaying && levelDb > -119.0) String.format(Locale.US, "Niveau en lecture : %.1f dBFS (crête)", levelDb)
                else "Niveau en lecture : -",
                fontSize = 12.sp
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (isPlaying) {
                        p.pause()
                        isPlaying = false
                    } else {
                        p.play()
                        isPlaying = p.playing
                        if (p.error.isNotEmpty()) message = p.error
                    }
                }) { Text(if (isPlaying) "Pause" else "Lecture") }
                OutlinedButton(onClick = {
                    p.stop()
                    isPlaying = false
                    posFrames = 0L
                }) { Text("Stop") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    p.seek(p.positionFrames() - (5 * rate).toLong())
                    posFrames = p.positionFrames()
                }) { Text("-5 s") }
                OutlinedButton(onClick = {
                    p.seek(p.positionFrames() + (5 * rate).toLong())
                    posFrames = p.positionFrames()
                }) { Text("+5 s") }
            }
        }
    }
}
