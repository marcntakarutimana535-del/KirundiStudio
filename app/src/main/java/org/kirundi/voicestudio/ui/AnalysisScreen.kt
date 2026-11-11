package org.kirundi.voicestudio.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.R
import org.kirundi.voicestudio.audio.analysis.AudioAnalyzer
import org.kirundi.voicestudio.audio.analysis.FileAnalysis
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.MeasureEntity
import org.kirundi.voicestudio.data.db.finiteOrNull
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.domain.verdict.AnalysisSettings
import org.kirundi.voicestudio.domain.verdict.NoiseAssessment
import org.kirundi.voicestudio.domain.verdict.NoiseEvaluator
import org.kirundi.voicestudio.domain.verdict.NoiseMethod
import org.kirundi.voicestudio.domain.verdict.Status
import org.kirundi.voicestudio.domain.verdict.dbText
import java.io.File
import java.util.Locale

private class ChartSeries(val values: DoubleArray, val color: Color, val dots: Boolean)

private val COLOR_RMS = Color(0xFF7CB7FF)
private val COLOR_PEAK = Color(0xFF8E8E93)
private val COLOR_TARGET = Color(0xFFFF6B6B)
private val COLOR_GRID = Color(0xFF3A3944)
private val COLOR_BG = Color(0xFF1C1B22)

private fun statusColor(s: Status): Color = when (s) {
    Status.OK -> Color(0xFF5CD68A)
    Status.CHECK -> Color(0xFFFFC857)
    Status.BAD -> Color(0xFFFF6B6B)
    Status.INCOMPLETE -> Color(0xFFFFC857)
}

@Composable
private fun LevelChart(series: List<ChartSeries>, targetDb: Double, seconds: Double) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val minDb = -100.0
        val maxDb = 0.0
        val w = size.width
        val h = size.height
        fun yOf(db: Double): Float {
            val c = db.coerceIn(minDb, maxDb)
            return (h * (1.0 - (c - minDb) / (maxDb - minDb))).toFloat()
        }
        drawRect(color = COLOR_BG)
        for (g in listOf(-80.0, -60.0, -40.0, -20.0)) {
            drawLine(COLOR_GRID, Offset(0f, yOf(g)), Offset(w, yOf(g)), 1f)
        }
        drawLine(COLOR_TARGET, Offset(0f, yOf(targetDb)), Offset(w, yOf(targetDb)), 2f)
        for (s in series) {
            val n = s.values.size
            if (n == 0) continue
            val step = if (n > 1) w / (n - 1) else 0f
            for (i in 0 until n) {
                val v = s.values[i]
                if (v.isNaN()) continue
                val x = i * step
                if (s.dots) {
                    drawCircle(s.color, 3f, Offset(x, yOf(v)))
                } else if (i + 1 < n && !s.values[i + 1].isNaN()) {
                    drawLine(s.color, Offset(x, yOf(v)), Offset((i + 1) * step, yOf(s.values[i + 1])), 2f)
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "0 s   (axe vertical : -100 à 0 dBFS)", fontSize = 11.sp)
        Text(text = String.format(Locale.US, "%.1f s", seconds), fontSize = 11.sp)
    }
}

@Composable
private fun Stepper(label: String, value: Double, step: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label : ${String.format(Locale.US, "%.0f", value)} dB",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp
        )
        OutlinedButton(onClick = { onChange(value - step) }) { Text("-") }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange(value + step) }) { Text("+") }
    }
}

@Composable
fun AnalysisScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val files = remember { RecordingStore.list(context) }

    var selected by remember { mutableStateOf<File?>(files.firstOrNull()) }
    var analysis by remember { mutableStateOf<FileAnalysis?>(null) }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(NoiseMethod.WINDOW_MEDIAN) }
    var target by remember { mutableStateOf(-60.0) }
    var voiceMargin by remember { mutableStateOf(12.0) }
    var silenceMargin by remember { mutableStateOf(6.0) }

    fun runAnalysis() {
        val f = selected ?: return
        if (running) return
        running = true
        error = ""
        analysis = null
        val snapSettings = AnalysisSettings(method, target, voiceMargin, silenceMargin)
        Thread {
            var result: FileAnalysis? = null
            var err = ""
            try {
                val ana = AudioAnalyzer.analyze(f)
                result = ana
                val ev = NoiseEvaluator.evaluate(
                    ana.rmsDb, ana.peakDb, ana.globalRmsDb, ana.seconds, ana.clipEvents, ana.windowSeconds, snapSettings
                )
                Db.run(context, { dao ->
                    dao.insertMeasure(
                        MeasureEntity(
                            fileName = f.name,
                            createdAt = System.currentTimeMillis(),
                            kind = "analyse",
                            method = snapSettings.method.label,
                            noiseDb = finiteOrNull(ev.noiseValueDb),
                            snrDb = finiteOrNull(ev.snrDb),
                            rmsDb = finiteOrNull(ana.globalRmsDb),
                            peakDb = finiteOrNull(ana.globalPeakDb),
                            status = ev.status.label,
                            summary = ev.reasons.joinToString(" | ")
                        )
                    )
                }) { }
            } catch (e: Exception) {
                err = e.message ?: e.javaClass.simpleName
            }
            handler.post {
                analysis = result
                error = err
                running = false
            }
        }.start()
    }

    val a = analysis
    val settings = AnalysisSettings(method, target, voiceMargin, silenceMargin)
    val assessment: NoiseAssessment? = remember(a, method, target, voiceMargin, silenceMargin) {
        if (a != null) {
            NoiseEvaluator.evaluate(
                a.rmsDb, a.peakDb, a.globalRmsDb, a.seconds, a.clipEvents, a.windowSeconds, settings
            )
        } else {
            null
        }
    }
    val report = if (a != null && assessment != null) buildAnalysisReport(a, assessment, settings) else ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = stringResource(R.string.label_pick_file), style = MaterialTheme.typography.titleMedium)
        if (files.isEmpty()) {
            Text(text = stringResource(R.string.no_recordings), fontSize = 13.sp)
        }
        for (f in files) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected = f }
            ) {
                RadioButton(selected = selected == f, onClick = { selected = f })
                Text(text = f.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { runAnalysis() }, enabled = selected != null && !running) {
            Text(stringResource(R.string.btn_analyze))
        }
        if (running) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.analyzing))
        }
        if (error.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = error, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.label_noise_method), style = MaterialTheme.typography.titleMedium)
        for (m in NoiseMethod.values()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { method = m }
            ) {
                RadioButton(selected = method == m, onClick = { method = m })
                Text(text = m.label, fontSize = 14.sp)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.label_params), style = MaterialTheme.typography.titleMedium)
        Stepper(stringResource(R.string.param_target), target, 1.0) { target = it }
        Stepper(stringResource(R.string.param_voice_margin), voiceMargin, 1.0) {
            voiceMargin = maxOf(it, silenceMargin + 1.0)
        }
        Stepper(stringResource(R.string.param_silence_margin), silenceMargin, 1.0) {
            silenceMargin = it.coerceIn(1.0, voiceMargin - 1.0)
        }

        if (a != null && assessment != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = assessment.status.label,
                color = statusColor(assessment.status),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = stringResource(R.string.legend_chart1), fontSize = 12.sp)
            LevelChart(
                listOf(ChartSeries(a.peakDb, COLOR_PEAK, false), ChartSeries(a.rmsDb, COLOR_RMS, false)),
                target,
                a.seconds
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.legend_chart2), fontSize = 12.sp)
            val noiseOnly = DoubleArray(a.rmsDb.size) { i ->
                if (assessment.kinds[i] == 0) a.rmsDb[i] else Double.NaN
            }
            LevelChart(listOf(ChartSeries(noiseOnly, COLOR_RMS, true)), target, a.seconds)

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                Text(stringResource(R.string.btn_copy))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = report, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun timesText(times: List<Double>): String =
    if (times.isEmpty()) "" else " (à " + times.take(6).joinToString(", ") {
        String.format(Locale.US, "%.1f s", it)
    } + (if (times.size > 6) ", ..." else "") + ")"

private fun buildAnalysisReport(a: FileAnalysis, r: NoiseAssessment, s: AnalysisSettings): String {
    val sb = StringBuilder()
    sb.appendLine("ANALYSE : ${a.fileName}")
    sb.appendLine(
        "Format : WAV PCM ${a.bits} bits, ${a.sampleRate} Hz, mono, " +
            "${String.format(Locale.US, "%.1f", a.seconds)} s (${a.rmsDb.size} fenêtres de 100 ms)"
    )
    sb.appendLine()
    sb.appendLine("NIVEAUX GLOBAUX")
    sb.appendLine("RMS global : ${dbText(a.globalRmsDb)}")
    sb.appendLine("Peak maximal : ${dbText(a.globalPeakDb)}")
    sb.appendLine("Décalage continu : ${dbText(a.dcOffsetDb)}")
    sb.appendLine("Saturations : ${a.clipEvents}${timesText(a.clipTimes)}")
    sb.appendLine("Coupures (10 ms de zéros exacts ou plus) : ${a.dropouts}${timesText(a.dropoutTimes)}")
    sb.appendLine("Variations brusques (25 dB ou plus entre 2 fenêtres) : ${a.jumps}")
    sb.appendLine()
    sb.appendLine("CLASSIFICATION DES FENÊTRES")
    sb.appendLine("Référence de bruit (10 % de fenêtres les plus calmes) : ${dbText(r.noiseRefDb)}")
    sb.appendLine(
        "Silence : ${r.silenceCount} | Intermédiaires : ${r.ambiguousCount} | Voix : ${r.voiceCount}"
    )
    sb.appendLine("(silence = jusqu'à +${s.silenceMarginDb.toInt()} dB, voix = au-delà de +${s.voiceMarginDb.toInt()} dB)")
    sb.appendLine()
    sb.appendLine("SILENCE (RMS et Peak séparés)")
    sb.appendLine("RMS du silence (moyenne en puissance) : ${dbText(r.silencePowerDb)}")
    sb.appendLine("RMS du silence, médiane : ${dbText(r.silenceMedianDb)}")
    sb.appendLine("RMS du silence, percentile 95 : ${dbText(r.silenceP95Db)}")
    sb.appendLine("Peak du bruit : ${dbText(r.noisePeakDb)}")
    sb.appendLine()
    sb.appendLine("VOIX")
    sb.appendLine("RMS de la voix : ${dbText(r.voiceRmsDb)}")
    sb.appendLine("Peak de la voix : ${dbText(r.voicePeakDb)}")
    sb.appendLine(
        "Rapport signal/bruit : " +
            (if (r.snrDb.isNaN()) "non disponible" else String.format(Locale.US, "%.1f dB", r.snrDb))
    )
    sb.appendLine()
    sb.appendLine("CONTRÔLE DU BRUIT")
    sb.appendLine("Méthode : ${s.method.label}")
    sb.appendLine("Cible : ${dbText(s.targetDb)} ou moins")
    sb.appendLine("Valeur retenue : ${dbText(r.noiseValueDb)}")
    sb.appendLine("STATUT : ${r.status.label}")
    for (line in r.reasons) sb.appendLine("- $line")
    for (line in r.info) sb.appendLine("* $line")
    if (r.intermittentRanges.isNotEmpty()) {
        sb.appendLine(
            "Bruits intermittents possibles : " + r.intermittentRanges.take(8).joinToString(", ") {
                String.format(Locale.US, "%.1f-%.1f s", it.first, it.second)
            } + (if (r.intermittentRanges.size > 8) ", ..." else "")
        )
    }
    sb.appendLine()
    sb.appendLine("NOTES : la méthode officielle de Voices.com n'est pas connue. La pondération A n'est pas encore disponible.")
    sb.appendLine("L'historique des mesures sera conservé avec la base de données (étape 5).")
    return sb.toString()
}
