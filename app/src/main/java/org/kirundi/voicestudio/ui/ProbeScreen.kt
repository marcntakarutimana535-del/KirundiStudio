package org.kirundi.voicestudio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.kirundi.voicestudio.R
import org.kirundi.voicestudio.audio.capture.CaptureProbe
import org.kirundi.voicestudio.audio.capture.ProbeResult
import org.kirundi.voicestudio.audio.device.AudioDeviceScanner
import org.kirundi.voicestudio.audio.device.InputDevice

@Composable
fun ProbeScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val handler = remember { Handler(Looper.getMainLooper()) }

    var devices by remember { mutableStateOf(AudioDeviceScanner.scan(context)) }
    var results by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    fun startProbe() {
        if (running) return
        running = true
        results = emptyList()
        status = "préparation"
        Thread {
            val r = try {
                CaptureProbe.runAll(context) { msg -> handler.post { status = msg } }
            } catch (e: Exception) {
                listOf(ProbeResult("Erreur", listOf(e.toString())))
            }
            handler.post {
                results = r
                devices = AudioDeviceScanner.scan(context)
                running = false
                status = ""
            }
        }.start()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) startProbe()
    }

    val report = buildReport(context, devices, results, hasPermission)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(text = stringResource(R.string.step1_title))
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.step1_hint))
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (hasPermission) startProbe()
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = !running
        ) {
            Text(stringResource(R.string.btn_probe))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { devices = AudioDeviceScanner.scan(context) },
            enabled = !running
        ) {
            Text(stringResource(R.string.btn_scan))
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report)) }) {
            Text(stringResource(R.string.btn_copy))
        }

        if (running) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.status_running, status))
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = report,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}

private fun buildReport(
    context: Context,
    devices: List<InputDevice>,
    results: List<ProbeResult>,
    hasPermission: Boolean
): String {
    val sb = StringBuilder()
    sb.appendLine("KIRUNDI VOICE STUDIO - rapport micro (étape 1)")
    sb.appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}")
    sb.appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    sb.appendLine("Source sans traitement (UNPROCESSED) annoncée : ${CaptureProbe.unprocessedSupport(context)}")
    sb.appendLine()
    sb.appendLine("ENTRÉES AUDIO DÉTECTÉES : ${devices.size}")
    if (devices.isEmpty()) {
        sb.appendLine("  aucune entrée listée par Android")
    }
    for (d in devices) {
        sb.appendLine("[${d.id}] ${d.name} - ${d.typeLabel}")
        sb.appendLine("  Fréquences : ${listOrNone(d.sampleRates.map { "$it Hz" })}")
        sb.appendLine("  Canaux : ${listOrNone(d.channelCounts.map { it.toString() })}")
        sb.appendLine("  Formats : ${listOrNone(d.encodings)}")
    }
    sb.appendLine()
    sb.appendLine("TEST RÉEL (48 kHz, mono, 3 s par format)")
    if (results.isEmpty()) {
        sb.appendLine(
            if (hasPermission) "  pas encore lancé"
            else "  pas encore lancé (la permission du micro sera demandée)"
        )
    }
    for (r in results) {
        sb.appendLine("--- ${r.title}")
        for (line in r.lines) sb.appendLine("  $line")
    }
    sb.appendLine()
    sb.appendLine("NOTE : « non annoncé » signifie qu'Android ne dit rien, pas que c'est faux.")
    sb.appendLine("Les traitements internes du pilote ne sont pas détectables par l'application.")
    return sb.toString()
}

private fun listOrNone(items: List<String>): String =
    if (items.isEmpty()) "non annoncé(s)" else items.joinToString(", ")
