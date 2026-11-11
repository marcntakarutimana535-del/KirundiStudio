package org.kirundi.voicestudio.ui

import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.DbSnapshot
import org.kirundi.voicestudio.data.db.MeasureEntity
import org.kirundi.voicestudio.domain.profile.ProfileChecker
import org.kirundi.voicestudio.domain.profile.ProfilePrefs
import org.kirundi.voicestudio.domain.profile.ProfileReport
import org.kirundi.voicestudio.domain.profile.VoicesProfile
import org.kirundi.voicestudio.domain.verdict.Status

fun profileColor(s: Status): Color = when (s) {
    Status.OK -> Color(0xFF5CD68A)
    Status.CHECK -> Color(0xFFFFC857)
    Status.BAD -> Color(0xFFFF6B6B)
    Status.INCOMPLETE -> Color(0xFFFFC857)
}

@Composable
fun ProfileScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val handler = remember { Handler(Looper.getMainLooper()) }
    var snap by remember { mutableStateOf<DbSnapshot?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var delivered24 by remember { mutableStateOf(false) }
    var accept16 by remember { mutableStateOf(ProfilePrefs.accept16(context)) }
    var report by remember { mutableStateOf<ProfileReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        Db.loadAll(context) {
            snap = it
            if (selected == null) selected = it.recordings.firstOrNull()?.fileName
        }
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
        Text("Profil Voices.com", style = MaterialTheme.typography.titleLarge)
        Text(VoicesProfile.NAME, style = MaterialTheme.typography.titleSmall)
        Text(VoicesProfile.DESCRIPTION, fontSize = 13.sp)
        Text(
            "Contrôle d'un fichier avant l'export. Si un critère ne peut pas être vérifié de façon fiable, " +
                "l'application affiche « CONTRÔLE INCOMPLET – VALIDATION MANUELLE NÉCESSAIRE ».",
            fontSize = 12.sp
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = accept16, onCheckedChange = {
                accept16 = it
                ProfilePrefs.setAccept16(context, it)
            })
            Text(
                "  16 bits natifs acceptés (à valider avec le client) : " + if (accept16) "oui" else "non",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = delivered24, onCheckedChange = { delivered24 = it })
            Text(
                "  Contrôler la version convertie en 24 bits : " + if (delivered24) "oui" else "non",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
        }

        if (data == null) {
            Text("Chargement...")
        } else if (data.recordings.isEmpty()) {
            Text("Aucun enregistrement à contrôler.")
        } else {
            for (rec in data.recordings) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { selected = rec.fileName; report = null }
                ) {
                    RadioButton(selected = selected == rec.fileName, onClick = { selected = rec.fileName; report = null })
                    Text(rec.fileName, fontSize = 14.sp)
                }
            }
            Button(
                enabled = !running && selected != null,
                onClick = {
                    val rec = data.recordings.firstOrNull { it.fileName == selected }
                    if (rec != null) {
                        running = true
                        message = "Contrôle en cours..."
                        report = null
                        val m = data.measures.firstOrNull { it.kind == "analyse" && it.fileName == rec.fileName }
                        val d24 = delivered24
                        val a16 = accept16
                        Thread {
                            val r = ProfileChecker.check(context, rec, m, d24, a16)
                            handler.post {
                                report = r
                                running = false
                                message = ""
                            }
                        }.start()
                    }
                }
            ) { Text("Contrôler le profil") }
        }

        if (message.isNotEmpty()) Text(message, fontSize = 13.sp)

        val r = report
        if (r != null) {
            Text(
                "RÉSULTAT GLOBAL : ${r.overall.label}",
                style = MaterialTheme.typography.titleSmall,
                color = profileColor(r.overall)
            )
            for (c in r.checks) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text("${c.title} : ${c.status.label}", fontSize = 14.sp, color = profileColor(c.status))
                    Text(c.detail, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(r.asText())) }) { Text("Copier le rapport") }
                OutlinedButton(onClick = {
                    Db.run(context, { dao ->
                        dao.insertMeasure(
                            MeasureEntity(
                                fileName = r.fileName,
                                createdAt = System.currentTimeMillis(),
                                kind = "profil",
                                method = VoicesProfile.NAME,
                                noiseDb = null,
                                snrDb = null,
                                rmsDb = null,
                                peakDb = null,
                                status = r.overall.label,
                                summary = r.asText()
                            )
                        )
                    }) { message = "Contrôle enregistré dans l'historique (Bibliothèque)." }
                }) { Text("Enregistrer") }
            }
        }
    }
}
