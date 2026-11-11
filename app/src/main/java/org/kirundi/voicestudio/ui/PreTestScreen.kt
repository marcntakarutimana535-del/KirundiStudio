package org.kirundi.voicestudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.kirundi.voicestudio.R
import org.kirundi.voicestudio.audio.analysis.AudioAnalyzer
import org.kirundi.voicestudio.audio.capture.LevelInfo
import org.kirundi.voicestudio.audio.capture.Recorder
import org.kirundi.voicestudio.audio.capture.formatDb
import org.kirundi.voicestudio.audio.pretest.PreTestRunner
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.MeasureEntity
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.domain.verdict.AnalysisSettings
import org.kirundi.voicestudio.domain.verdict.NoiseEvaluator
import org.kirundi.voicestudio.domain.verdict.NoiseMethod
import org.kirundi.voicestudio.domain.verdict.PreTestEvaluator
import org.kirundi.voicestudio.domain.verdict.Status
import org.kirundi.voicestudio.domain.verdict.StepResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private enum class PreTestPhase {
    INTRO, MIC_CHECK, SILENCE_READY, SILENCE_RUNNING, VOICE_READY, VOICE_RUNNING, REPORT
}

private const val VOICE_TEST_SECONDS = 10

private fun stColor(s: Status): Color = when (s) {
    Status.OK -> Color(0xFF5CD68A)
    Status.CHECK -> Color(0xFFFFC857)
    Status.BAD -> Color(0xFFFF6B6B)
    Status.INCOMPLETE -> Color(0xFFFFC857)
}

private fun meter(db: Double): Float {
    if (db.isInfinite() || db.isNaN()) return 0f
    return ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
}

@Composable
private fun StepLines(step: StepResult) {
    Text(
        text = "${step.title} : ${step.status.label}",
        color = stColor(step.status),
        style = MaterialTheme.typography.titleSmall
    )
    for (l in step.lines) {
        Text(text = l, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun RunningPanel(title: String, text: String, totalSeconds: Int, level: LevelInfo, onCancel: () -> Unit) {
    Text(text = title, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(4.dp))
    Text(text = text, fontSize = 14.sp)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.progress_text, level.seconds, totalSeconds.toDouble()),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.headlineSmall
    )
    LinearProgressIndicator(
        progress = { (level.seconds / totalSeconds).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(10.dp)
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = "RMS : ${formatDb(level.rmsDb)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    LinearProgressIndicator(
        progress = { meter(level.rmsDb) },
        modifier = Modifier.fillMaxWidth().height(10.dp)
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = "Peak : ${formatDb(level.peakDb)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    LinearProgressIndicator(
        progress = { meter(level.peakDb) },
        modifier = Modifier.fillMaxWidth().height(10.dp)
    )
    Spacer(modifier = Modifier.height(4.dp))
    if (level.clipEvents > 0) {
        Text(text = stringResource(R.string.clip_detected, level.clipEvents), color = MaterialTheme.colorScheme.error)
    }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.btn_cancel)) }
}

@Composable
fun PreTestScreen(onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val clipboard = LocalClipboardManager.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val runner = remember { PreTestRunner(context) }
    val cancelled = remember { AtomicBoolean(false) }

    var phase by remember { mutableStateOf(PreTestPhase.INTRO) }
    var silenceSeconds by remember { mutableStateOf(20) }
    var sourceIndex by remember { mutableStateOf(0) }
    var method by remember { mutableStateOf(NoiseMethod.GLOBAL) }
    var accept16 by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(LevelInfo.EMPTY) }
    var message by remember { mutableStateOf("") }
    var micRes by remember { mutableStateOf<StepResult?>(null) }
    var silRes by remember { mutableStateOf<StepResult?>(null) }
    var silenceRms by remember { mutableStateOf(Double.NaN) }
    var silenceFile by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("") }
    var overall by remember { mutableStateOf(Status.INCOMPLETE) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val busy = phase == PreTestPhase.MIC_CHECK ||
        phase == PreTestPhase.SILENCE_RUNNING ||
        phase == PreTestPhase.VOICE_RUNNING
    LaunchedEffect(busy) { onBusyChange(busy) }
    SideEffect { view.keepScreenOn = busy }
    DisposableEffect(Unit) {
        onDispose {
            cancelled.set(true)
            runner.cancel()
            view.keepScreenOn = false
        }
    }

    fun finishWithSteps(steps: List<StepResult>, files: List<String>) {
        val ov = PreTestEvaluator.worst(steps.map { it.status })
        val text = PreTestEvaluator.buildReport(
            dateText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
            deviceText = "${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            sourceName = Recorder.sourceName(sourceIndex),
            accept16 = accept16,
            silenceSeconds = silenceSeconds,
            steps = steps,
            overall = ov,
            files = files
        )
        overall = ov
        report = text
        phase = PreTestPhase.REPORT
        val methodLabel = method.label
        val measureFile = files.lastOrNull() ?: "pretest"
        Db.run(context, { dao ->
            dao.insertMeasure(
                MeasureEntity(
                    fileName = measureFile,
                    createdAt = System.currentTimeMillis(),
                    kind = "pretest",
                    method = methodLabel,
                    noiseDb = null,
                    snrDb = null,
                    rmsDb = null,
                    peakDb = null,
                    status = ov.label,
                    summary = text
                )
            )
        }) { }
        try {
            val dir = File(context.filesDir, "reports")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            File(dir, "pretest_$stamp.txt").writeText(text)
        } catch (e: Exception) {
            message = "Le rapport n'a pas pu être enregistré : ${e.message}"
        }
    }

    fun beginVoice() {
        cancelled.set(false)
        level = LevelInfo.EMPTY
        message = ""
        val file = RecordingStore.newFile(context, "pretest_voice")
        val st = AnalysisSettings(method = method)
        val silenceSnapshot = silenceRms
        val sFile = silenceFile
        phase = PreTestPhase.VOICE_RUNNING
        val err = runner.record(
            file, sourceIndex, VOICE_TEST_SECONDS.toDouble(),
            { info -> handler.post { level = info } },
            { _ ->
                if (cancelled.get()) {
                    RecordingStore.delete(file)
                    handler.post { phase = PreTestPhase.INTRO }
                } else {
                    try {
                        val a = AudioAnalyzer.analyze(file)
                        val vs = AnalysisSettings(method = NoiseMethod.WINDOW_MEDIAN)
                        val r = NoiseEvaluator.evaluate(
                            a.rmsDb, a.peakDb, a.globalRmsDb, a.seconds, a.clipEvents, a.windowSeconds, vs
                        )
                        val after = a.dropoutTimes.count { it >= 0.2 } +
                            maxOf(0, a.dropouts - a.dropoutTimes.size)
                        val step = PreTestEvaluator.voiceStep(
                            r, st, a.seconds, a.globalRmsDb, a.globalPeakDb, a.clipEvents, after, silenceSnapshot
                        )
                        handler.post {
                            val m = micRes
                            val s = silRes
                            val all = ArrayList<StepResult>()
                            if (m != null) all.add(m)
                            if (s != null) all.add(s)
                            all.add(step)
                            finishWithSteps(all, listOf(sFile, file.name))
                        }
                    } catch (e: Exception) {
                        handler.post {
                            message = "Analyse impossible : ${e.message}"
                            phase = PreTestPhase.VOICE_READY
                        }
                    }
                }
            }
        )
        if (err != null) {
            message = err
            phase = PreTestPhase.VOICE_READY
        }
    }

    fun beginSilence() {
        cancelled.set(false)
        level = LevelInfo.EMPTY
        message = ""
        val file = RecordingStore.newFile(context, "pretest_silence")
        val secs = silenceSeconds
        val st = AnalysisSettings(method = method)
        phase = PreTestPhase.SILENCE_RUNNING
        val err = runner.record(
            file, sourceIndex, secs.toDouble(),
            { info -> handler.post { level = info } },
            { _ ->
                if (cancelled.get()) {
                    RecordingStore.delete(file)
                    handler.post { phase = PreTestPhase.INTRO }
                } else {
                    try {
                        val a = AudioAnalyzer.analyze(file)
                        val r = NoiseEvaluator.evaluate(
                            a.rmsDb, a.peakDb, a.globalRmsDb, a.seconds, a.clipEvents, a.windowSeconds, st, false
                        )
                        val step = PreTestEvaluator.silenceStep(r, st, a.seconds, a.globalRmsDb, a.globalPeakDb)
                        handler.post {
                            silRes = step
                            silenceRms = a.globalRmsDb
                            silenceFile = file.name
                            phase = PreTestPhase.VOICE_READY
                        }
                    } catch (e: Exception) {
                        handler.post {
                            message = "Analyse impossible : ${e.message}"
                            phase = PreTestPhase.SILENCE_READY
                        }
                    }
                }
            }
        )
        if (err != null) {
            message = err
            phase = PreTestPhase.SILENCE_READY
        }
    }

    fun beginMicCheck() {
        cancelled.set(false)
        level = LevelInfo.EMPTY
        message = ""
        micRes = null
        silRes = null
        silenceRms = Double.NaN
        phase = PreTestPhase.MIC_CHECK
        val desc = PreTestRunner.describeInput(context)
        val bits = Recorder.plannedBits(context)
        val planned = Recorder.plannedFormat(context)
        val acc = accept16
        val tmp = File(RecordingStore.dir(context), "pretest_micro_tmp.wav")
        tmp.delete()
        val err = runner.record(
            tmp, sourceIndex, 2.0,
            { info -> handler.post { level = info } },
            { _ ->
                var peak = Double.NEGATIVE_INFINITY
                var rms = Double.NEGATIVE_INFINITY
                try {
                    val a = AudioAnalyzer.analyze(tmp)
                    peak = a.globalPeakDb
                    rms = a.globalRmsDb
                } catch (e: Exception) {
                    // fichier vide ou illisible : le signal sera jugé absent
                }
                RecordingStore.delete(tmp)
                if (cancelled.get()) {
                    handler.post { phase = PreTestPhase.INTRO }
                } else {
                    val step = PreTestEvaluator.micStep(
                        desc?.label, null, desc?.rates ?: emptyList(), desc?.formats ?: emptyList(),
                        bits, planned, peak, rms, acc
                    )
                    handler.post {
                        micRes = step
                        if (step.status == Status.BAD) {
                            finishWithSteps(listOf(step), emptyList())
                        } else {
                            phase = PreTestPhase.SILENCE_READY
                        }
                    }
                }
            }
        )
        if (err != null) {
            val step = PreTestEvaluator.micStep(
                desc?.label, err, desc?.rates ?: emptyList(), desc?.formats ?: emptyList(),
                bits, planned, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, acc
            )
            micRes = step
            finishWithSteps(listOf(step), emptyList())
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) beginMicCheck()
    }

    fun cancelTest() {
        cancelled.set(true)
        runner.cancel()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = stringResource(R.string.pretest_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))

        when (phase) {
            PreTestPhase.INTRO -> {
                Text(text = stringResource(R.string.pretest_intro), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(12.dp))

                Text(text = stringResource(R.string.pretest_silence_len), style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (s in listOf(10, 20, 30)) {
                        RadioButton(selected = silenceSeconds == s, onClick = { silenceSeconds = s })
                        Text(text = "$s s", modifier = Modifier.clickable { silenceSeconds = s })
                    }
                }

                Text(text = stringResource(R.string.label_source), style = MaterialTheme.typography.titleSmall)
                val srcLabels = listOf(
                    stringResource(R.string.src_unprocessed),
                    stringResource(R.string.src_voicerec),
                    stringResource(R.string.src_mic)
                )
                for (i in srcLabels.indices) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { sourceIndex = i }
                    ) {
                        RadioButton(selected = sourceIndex == i, onClick = { sourceIndex = i })
                        Text(text = srcLabels[i], fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(text = stringResource(R.string.label_noise_method), style = MaterialTheme.typography.titleSmall)
                for (m in NoiseMethod.values()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { method = m }
                    ) {
                        RadioButton(selected = method == m, onClick = { method = m })
                        Text(text = m.label, fontSize = 14.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = accept16, onCheckedChange = { accept16 = it })
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text(text = stringResource(R.string.pretest_accept16), fontSize = 14.sp, modifier = Modifier.weight(1f))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    if (hasPermission) beginMicCheck()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) { Text(stringResource(R.string.btn_start_test)) }
                if (message.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
            }

            PreTestPhase.MIC_CHECK -> {
                Text(text = stringResource(R.string.step1_checking), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(onClick = { cancelTest() }) { Text(stringResource(R.string.btn_cancel)) }
            }

            PreTestPhase.SILENCE_READY -> {
                val m = micRes
                if (m != null) StepLines(m)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.step2_title), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.step2_text), fontSize = 14.sp)
                if (message.isNotEmpty()) {
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { beginSilence() }) { Text(stringResource(R.string.btn_start_silence)) }
            }

            PreTestPhase.SILENCE_RUNNING -> {
                RunningPanel(
                    stringResource(R.string.step2_title),
                    stringResource(R.string.step2_text),
                    silenceSeconds,
                    level
                ) { cancelTest() }
            }

            PreTestPhase.VOICE_READY -> {
                val s = silRes
                if (s != null) StepLines(s)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = stringResource(R.string.step3_title), style = MaterialTheme.typography.titleMedium)
                Text(text = stringResource(R.string.step3_text), fontSize = 14.sp)
                if (message.isNotEmpty()) {
                    Text(text = message, color = MaterialTheme.colorScheme.error)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { beginVoice() }) { Text(stringResource(R.string.btn_start_voice)) }
            }

            PreTestPhase.VOICE_RUNNING -> {
                RunningPanel(
                    stringResource(R.string.step3_title),
                    stringResource(R.string.step3_text),
                    VOICE_TEST_SECONDS,
                    level
                ) { cancelTest() }
            }

            PreTestPhase.REPORT -> {
                Text(text = stringResource(R.string.step4_title), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = overall.label,
                    color = stColor(overall),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                        Text(stringResource(R.string.btn_copy))
                    }
                    Button(onClick = { phase = PreTestPhase.INTRO }) {
                        Text(stringResource(R.string.btn_redo))
                    }
                }
                if (message.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = report, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
