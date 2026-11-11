package org.kirundi.voicestudio.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.audio.wav.WavConverter
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.DbSnapshot
import org.kirundi.voicestudio.data.db.ExportEntity
import org.kirundi.voicestudio.data.db.RecordingEntity
import org.kirundi.voicestudio.data.db.dbOrDash
import org.kirundi.voicestudio.data.db.formatDateTime
import org.kirundi.voicestudio.data.db.formatDur
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.data.files.sha256Hex
import java.io.File

private enum class ExportKind { ORIGINAL, CONVERTED_24 }

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    var snap by remember { mutableStateOf<DbSnapshot?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<RecordingEntity?>(null) }
    var pendingKind by remember { mutableStateOf(ExportKind.ORIGINAL) }
    var deleteTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var assignTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var notesTarget by remember { mutableStateOf<RecordingEntity?>(null) }
    var notesText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    fun reload() {
        Db.loadAll(context) { snap = it }
    }

    LaunchedEffect(Unit) { reload() }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri: Uri? ->
        val rec = pendingFile
        val kind = pendingKind
        pendingFile = null
        if (uri == null || rec == null) {
            message = "Export annulé."
        } else {
            busy = true
            message = "Export en cours..."
            val src = File(RecordingStore.dir(context), rec.fileName)
            val handler = Handler(Looper.getMainLooper())
            Thread {
                var text: String
                var hash: String? = null
                var ok: Boolean
                if (kind == ExportKind.ORIGINAL) {
                    text = RecordingStore.exportTo(context, src, uri)
                    ok = text.startsWith("Export réussi")
                    if (ok) hash = try { src.inputStream().use { sha256Hex(it) } } catch (e: Exception) { null }
                } else {
                    val r = WavConverter.exportTo24(context, src, uri)
                    text = r.message
                    ok = r.ok
                    hash = r.sha256
                }
                val label = if (kind == ExportKind.ORIGINAL) "WAV original" else "WAV 24 bits (converti depuis 16 bits)"
                handler.post {
                    message = text
                    busy = false
                    Db.run(context, { dao ->
                        dao.insertExport(
                            ExportEntity(
                                fileName = rec.fileName,
                                exportedAt = System.currentTimeMillis(),
                                format = label,
                                sha256 = hash,
                                verified = ok,
                                message = text
                            )
                        )
                    }) { reload() }
                }
            }.start()
        }
    }

    fun startExport(rec: RecordingEntity, kind: ExportKind) {
        pendingFile = rec
        pendingKind = kind
        val base = rec.fileName.removeSuffix(".wav")
        launcher.launch(if (kind == ExportKind.ORIGINAL) rec.fileName else base + "_24bit.wav")
    }

    val data = snap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Bibliothèque", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tous les enregistrements de l'application. Les originaux ne sont jamais modifiés.",
            fontSize = 13.sp
        )
        if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

        if (data == null) {
            Text("Chargement...")
        } else if (data.recordings.isEmpty()) {
            Text("Aucun enregistrement pour le moment.")
        } else {
            for (rec in data.recordings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(rec.fileName, style = MaterialTheme.typography.titleSmall)
                    val detail = buildString {
                        append("Date : ${formatDateTime(rec.createdAt)}   Durée : ${formatDur(rec.durationSec)}\n")
                        append("Format WAV : ${rec.sampleRate} Hz, ${rec.bits} bits   Profondeur native : ${rec.nativeDepth}\n")
                        append("Source : ${rec.source}   Entrée : ${rec.device}\n")
                        append("RMS global : ${dbOrDash(rec.rmsDb)}   Crête max : ${dbOrDash(rec.peakDb)}\n")
                        append("Saturations : ${rec.clipEvents}\n")
                        append("Session : ${data.sessionLabel(rec.sessionId)}\n")
                        append("SHA-256 : ${rec.sha256?.take(16)?.plus("...") ?: "non disponible"}")
                        if (rec.notes.isNotBlank()) append("\nNotes : ${rec.notes}")
                    }
                    Text(detail, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { assignTarget = rec }) { Text("Session") }
                        OutlinedButton(onClick = {
                            notesText = rec.notes
                            notesTarget = rec
                        }) { Text("Notes") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, onClick = { startExport(rec, ExportKind.ORIGINAL) }) {
                            Text("Export original")
                        }
                        Button(
                            enabled = !busy && rec.bits == 16,
                            onClick = { startExport(rec, ExportKind.CONVERTED_24) }
                        ) { Text("Export 24 bits") }
                    }
                    if (rec.bits == 16) {
                        Text(
                            "L'export 24 bits est une conversion sans perte depuis du 16 bits natif (comme Audacity) : " +
                                "le fichier est un WAV 24 bits valide, mais ce n'est pas une capture native en 24 bits. " +
                                "À indiquer au client.",
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = { deleteTarget = rec }) { Text("Supprimer") }
                }
            }
        }

        OutlinedButton(onClick = { showHistory = !showHistory }) {
            Text(if (showHistory) "Masquer l'historique" else "Historique des mesures et exports")
        }
        if (showHistory && data != null) {
            Text("Mesures et contrôles", style = MaterialTheme.typography.titleSmall)
            if (data.measures.isEmpty()) Text("Aucune mesure enregistrée.", fontSize = 12.sp)
            for (m in data.measures.take(40)) {
                Text(
                    "${formatDateTime(m.createdAt)}  ${m.kind}  ${m.fileName}\n" +
                        "  statut : ${m.status}   bruit : ${dbOrDash(m.noiseDb)}   RSB : ${m.snrDb?.let { String.format("%.1f dB", it) } ?: "n/d"}\n" +
                        "  RMS : ${dbOrDash(m.rmsDb)}   crête : ${dbOrDash(m.peakDb)}   méthode : ${m.method}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Exports", style = MaterialTheme.typography.titleSmall)
            if (data.exports.isEmpty()) Text("Aucun export.", fontSize = 12.sp)
            for (e in data.exports.take(40)) {
                Text(
                    "${formatDateTime(e.exportedAt)}  ${e.format}  ${e.fileName}\n" +
                        "  ${if (e.verified) "vérifié" else "NON VÉRIFIÉ / ÉCHEC"}   SHA-256 : ${e.sha256?.take(16) ?: "n/d"}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp
                )
            }
        }
    }

    val del = deleteTarget
    if (del != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Supprimer cet enregistrement ?") },
            text = {
                Text(
                    "${del.fileName}\n\nLe fichier WAV et son fichier .json seront supprimés définitivement de l'application. " +
                        "Si vous ne l'avez pas exporté, il sera perdu."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RecordingStore.delete(File(RecordingStore.dir(context), del.fileName))
                    deleteTarget = null
                    message = "Supprimé : ${del.fileName}"
                    reload()
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Annuler") } }
        )
    }

    val asg = assignTarget
    if (asg != null && data != null) {
        AlertDialog(
            onDismissRequest = { assignTarget = null },
            title = { Text("Classer dans une session") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = {
                        Db.run(context, { dao -> dao.updateRecording(asg.copy(sessionId = null)) }) { reload() }
                        assignTarget = null
                    }) { Text("Non classé") }
                    if (data.sessions.isEmpty()) Text("Aucune session. Créez-en une dans l'onglet Projets.")
                    for (s in data.sessions) {
                        TextButton(onClick = {
                            Db.run(context, { dao -> dao.updateRecording(asg.copy(sessionId = s.id)) }) { reload() }
                            assignTarget = null
                        }) { Text(data.sessionLabel(s.id), fontSize = 12.sp) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { assignTarget = null }) { Text("Fermer") } }
        )
    }

    val nt = notesTarget
    if (nt != null) {
        AlertDialog(
            onDismissRequest = { notesTarget = null },
            title = { Text("Notes") },
            text = {
                OutlinedTextField(value = notesText, onValueChange = { notesText = it })
            },
            confirmButton = {
                TextButton(onClick = {
                    val txt = notesText
                    Db.run(context, { dao -> dao.updateRecording(nt.copy(notes = txt)) }) { reload() }
                    notesTarget = null
                }) { Text("Enregistrer") }
            },
            dismissButton = { TextButton(onClick = { notesTarget = null }) { Text("Annuler") } }
        )
    }
}
