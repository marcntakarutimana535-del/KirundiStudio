package org.kirundi.voicestudio.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.DbSnapshot
import org.kirundi.voicestudio.data.db.SessionPrefs
import org.kirundi.voicestudio.data.db.formatDateTime
import org.kirundi.voicestudio.data.db.formatDur
import org.kirundi.voicestudio.export.ExportService

private const val UNCLASSIFIED = -1L

@Composable
fun ExportScreen() {
    val context = LocalContext.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var snap by remember { mutableStateOf<DbSnapshot?>(null) }
    var scope by remember { mutableStateOf(UNCLASSIFIED) }
    var excluded by remember { mutableStateOf(setOf<String>()) }
    var template by remember { mutableStateOf("{projet}_{participant}_{date}_{n}") }
    var convert24 by remember { mutableStateOf(false) }
    var withPdf by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    var finalOk by remember { mutableStateOf<Boolean?>(null) }

    fun reload(initial: Boolean) {
        Db.loadAll(context) {
            snap = it
            if (initial) {
                val active = SessionPrefs.active(context)
                scope = if (active > 0L && it.sessions.any { s -> s.id == active }) active else UNCLASSIFIED
            }
        }
    }
    LaunchedEffect(Unit) { reload(true) }

    val data = snap
    val inScope = data?.recordings?.filter {
        if (scope == UNCLASSIFIED) it.sessionId == null else it.sessionId == scope
    } ?: emptyList()
    val chosen = inScope.filter { it.fileName !in excluded }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val d = snap
        if (uri == null || d == null) {
            progress = "Export annulé : aucun dossier choisi."
        } else {
            running = true
            finalOk = null
            result = emptyList()
            val sessionId = if (scope == UNCLASSIFIED) null else scope
            val list = chosen
            val tpl = template
            val c24 = convert24
            val pdf = withPdf
            Thread {
                val run = try {
                    ExportService.run(context, uri, d, list, sessionId, tpl, c24, pdf) { p ->
                        handler.post { progress = p }
                    }
                } catch (e: Exception) {
                    null
                }
                handler.post {
                    running = false
                    if (run == null) {
                        progress = "Export échoué."
                        result = listOf("Erreur inattendue pendant l'export. Rien n'a été modifié dans l'application.")
                        finalOk = false
                    } else {
                        progress = ""
                        result = run.messages
                        finalOk = run.allOk
                        Db.run(context, { dao ->
                            for (e in run.exports) dao.insertExport(e)
                        }) { reload(false) }
                    }
                }
            }.start()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Export", style = MaterialTheme.typography.titleLarge)
        Text(
            "Exporte les enregistrements d'une session dans un dossier de votre choix : fichiers renommés, " +
                "SHA-256 vérifié, liste SHA256SUMS.txt et rapport PDF. Les originaux ne sont jamais modifiés.",
            fontSize = 13.sp
        )

        if (data == null) {
            Text("Chargement...")
        } else {
            Text("1. Que faut-il exporter ?", style = MaterialTheme.typography.titleSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { scope = UNCLASSIFIED; excluded = emptySet() }
            ) {
                RadioButton(selected = scope == UNCLASSIFIED, onClick = { scope = UNCLASSIFIED; excluded = emptySet() })
                Text("Enregistrements non classés", fontSize = 13.sp)
            }
            for (s in data.sessions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { scope = s.id; excluded = emptySet() }
                ) {
                    RadioButton(selected = scope == s.id, onClick = { scope = s.id; excluded = emptySet() })
                    Text(data.sessionLabel(s.id), fontSize = 13.sp)
                }
            }

            if (inScope.isEmpty()) {
                Text("Aucun enregistrement dans cette sélection.", fontSize = 13.sp)
            }
            for (rec in inScope) {
                val m = data.measures.firstOrNull { it.kind == "analyse" && it.fileName == rec.fileName }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = rec.fileName !in excluded,
                        onCheckedChange = { on ->
                            excluded = if (on) excluded - rec.fileName else excluded + rec.fileName
                        }
                    )
                    Column {
                        Text(rec.fileName, fontSize = 13.sp)
                        Text(
                            "${formatDateTime(rec.createdAt)}  ${formatDur(rec.durationSec)}  ${rec.bits} bits  " +
                                (if (m == null) "NON ANALYSÉ (contrôle incomplet)" else "analyse : ${m.status}"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            Text("2. Format", style = MaterialTheme.typography.titleSmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { convert24 = false }
            ) {
                RadioButton(selected = !convert24, onClick = { convert24 = false })
                Text("WAV original, tel qu'enregistré", fontSize = 13.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { convert24 = true }
            ) {
                RadioButton(selected = convert24, onClick = { convert24 = true })
                Text("WAV 24 bits converti depuis 16 bits (comme Audacity)", fontSize = 13.sp)
            }
            if (convert24) {
                Text(
                    "Le fichier sera un WAV 24 bits valide, mais son contenu reste celui d'une capture 16 bits : " +
                        "ce n'est pas du 24 bits natif. Le rapport PDF le précise. À indiquer à Voices.com.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text("3. Nom des fichiers", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = template,
                onValueChange = { template = it },
                label = { Text("Modèle de nom") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Mots utilisables : {projet} {participant} {date} {n} {original}. " +
                    "Exemple avec le modèle actuel : " + exampleName(template, data, inScope.firstOrNull()),
                fontSize = 11.sp
            )
            Text(
                "La convention de nommage de Voices.com n'est pas connue : adaptez le modèle si le client en impose une.",
                fontSize = 11.sp
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withPdf, onCheckedChange = { withPdf = it })
                Text("Créer le rapport PDF", fontSize = 13.sp)
            }

            Button(
                enabled = !running && chosen.isNotEmpty() && template.isNotBlank(),
                onClick = { launcher.launch(null) }
            ) { Text("Choisir le dossier et exporter (${chosen.size})") }
        }

        if (progress.isNotEmpty()) Text(progress, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        val ok = finalOk
        if (ok != null) {
            Text(
                if (ok) "Export terminé : tout est vérifié." else "Export terminé AVEC PROBLÈMES : lisez les messages.",
                style = MaterialTheme.typography.titleSmall,
                color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        for (line in result) Text(line, fontSize = 12.sp)
    }
}

private fun exampleName(template: String, data: DbSnapshot, first: org.kirundi.voicestudio.data.db.RecordingEntity?): String {
    val session = data.sessions.firstOrNull { it.id == first?.sessionId }
    val participant = data.participants.firstOrNull { it.id == session?.participantId }
    val project = data.projects.firstOrNull { it.id == participant?.projectId }
    fun c(s: String) = s.trim().replace(Regex("[^\\p{L}\\p{N}_.-]"), "_").trim('_', '.')
    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        .format(java.util.Date(first?.createdAt ?: System.currentTimeMillis()))
    val name = template
        .replace("{projet}", c(project?.code ?: "SANS-PROJET"))
        .replace("{participant}", c(participant?.code ?: "SANS-PARTICIPANT"))
        .replace("{date}", day)
        .replace("{n}", "001")
        .replace("{original}", (first?.fileName ?: "rec").removeSuffix(".wav"))
    return c(name).removeSuffix(".wav") + ".wav"
}
