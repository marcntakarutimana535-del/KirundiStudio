package org.kirundi.voicestudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.kirundi.voicestudio.R
import org.kirundi.voicestudio.audio.capture.LevelInfo
import org.kirundi.voicestudio.audio.capture.RecState
import org.kirundi.voicestudio.audio.capture.Recorder
import org.kirundi.voicestudio.audio.capture.formatDb
import org.kirundi.voicestudio.data.files.RecordingStore
import java.io.File
import java.util.Locale

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt()
    val tenth = ((seconds - total) * 10).toInt()
    return String.format(Locale.US, "%02d:%02d.%d", total / 60, total % 60, tenth)
}

private fun meterValue(db: Double): Float {
    if (db.isInfinite() || db.isNaN()) return 0f
    return ((db + 60.0) / 60.0).toFloat().coerceIn(0f, 1f)
}

@Composable
fun StudioScreen(onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    val recorder = remember { Recorder(context) }

    var recState by remember { mutableStateOf(RecState.IDLE) }
    var level by remember { mutableStateOf(LevelInfo.EMPTY) }
    var fileName by remember { mutableStateOf("") }
    var sourceIndex by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(RecordingStore.list(context)) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    var toExport by remember { mutableStateOf<File?>(null) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val plannedFormat = remember { Recorder.plannedFormat(context) }

    LaunchedEffect(Unit) {
        val n = RecordingStore.repairAll(context)
        if (n > 0) {
            message = "$n enregistrement(s) interrompu(s) récupéré(s) et réparé(s)."
            files = RecordingStore.list(context)
        }
    }
    LaunchedEffect(recState) { onBusyChange(recState != RecState.IDLE) }
    SideEffect { view.keepScreenOn = recState != RecState.IDLE }
    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            view.keepScreenOn = false
        }
    }

    fun beginRecording() {
        val file = RecordingStore.newFile(context, fileName)
        val err = recorder.start(
            file = file,
            sourceChoice = sourceIndex,
            onLevel = { info -> handler.post { level = info } },
            onFinished = { summary ->
                handler.post {
                    recState = RecState.IDLE
                    message = summary.message
                    files = RecordingStore.list(context)
                }
            }
        )
        if (err != null) {
            message = err
        } else {
            level = LevelInfo.EMPTY
            message = ""
            recState = RecState.RECORDING
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) beginRecording()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri: Uri? ->
        val src = toExport
        if (uri != null && src != null) {
            message = "Export en cours..."
            Thread {
                val msg = RecordingStore.exportTo(context, src, uri)
                handler.post { message = msg }
            }.start()
        }
        toExport = null
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_text, del.name)) },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.delete(del)
                    files = RecordingStore.list(context)
                    toDelete = null
                }) { Text(stringResource(R.string.btn_confirm_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.btn_cancel)) }
            }
        )
    }

    val idle = recState == RecState.IDLE

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(text = stringResource(R.string.label_format), style = MaterialTheme.typography.labelLarge)
        Text(text = plannedFormat, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Text(text = formatTime(level.seconds), style = MaterialTheme.typography.displayMedium, fontFamily = FontFamily.Monospace)
        if (recState == RecState.PAUSED) {
            Text(text = stringResource(R.string.state_paused), color = MaterialTheme.colorScheme.tertiary)
        } else if (recState == RecState.RECORDING) {
            Text(text = stringResource(R.string.state_recording), color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "RMS : ${formatDb(level.rmsDb)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { meterValue(level.rmsDb) },
            modifier = Modifier.fillMaxWidth().height(10.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = "Peak : ${formatDb(level.peakDb)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { meterValue(level.peakDb) },
            modifier = Modifier.fillMaxWidth().height(10.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = "Peak max : ${formatDb(level.maxPeakDb)}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        if (level.clipEvents > 0) {
            Text(
                text = stringResource(R.string.clip_detected, level.clipEvents),
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text(text = stringResource(R.string.clip_none), fontSize = 13.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (hasPermission) beginRecording()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = idle,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.btn_record)) }
            Button(
                onClick = {
                    recorder.stop()
                },
                enabled = !idle,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.btn_stop)) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    recorder.pause()
                    recState = RecState.PAUSED
                },
                enabled = recState == RecState.RECORDING,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.btn_pause)) }
            OutlinedButton(
                onClick = {
                    recorder.resume()
                    recState = RecState.RECORDING
                },
                enabled = recState == RecState.PAUSED,
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.btn_resume)) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text(stringResource(R.string.label_filename)) },
            singleLine = true,
            enabled = idle,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Text(text = stringResource(R.string.label_source), style = MaterialTheme.typography.labelLarge)
        val sourceLabels = listOf(
            stringResource(R.string.src_unprocessed),
            stringResource(R.string.src_voicerec),
            stringResource(R.string.src_mic)
        )
        for (i in sourceLabels.indices) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = idle) { sourceIndex = i }
            ) {
                RadioButton(selected = sourceIndex == i, onClick = { sourceIndex = i }, enabled = idle)
                Text(text = sourceLabels[i], fontSize = 14.sp)
            }
        }

        if (message.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = stringResource(R.string.label_recordings), style = MaterialTheme.typography.titleMedium)
        if (files.isEmpty()) {
            Text(text = stringResource(R.string.none_yet), fontSize = 13.sp)
        }
        for (f in files) {
            val info = RecordingStore.info(f)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = f.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            val details = if (info != null) {
                "${formatTime(info.seconds)} - ${info.bits} bits - ${info.sampleRate} Hz - " +
                    "${info.channels} canal - ${f.length() / 1024} Ko"
            } else {
                "en-tête illisible - ${f.length() / 1024} Ko"
            }
            Text(text = details, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        toExport = f
                        exportLauncher.launch(f.name)
                    },
                    enabled = idle
                ) { Text(stringResource(R.string.btn_export)) }
                OutlinedButton(
                    onClick = { toDelete = f },
                    enabled = idle
                ) { Text(stringResource(R.string.btn_delete)) }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
