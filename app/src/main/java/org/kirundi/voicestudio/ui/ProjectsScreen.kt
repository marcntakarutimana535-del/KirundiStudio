package org.kirundi.voicestudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.data.db.Db
import org.kirundi.voicestudio.data.db.DbSnapshot
import org.kirundi.voicestudio.data.db.ParticipantEntity
import org.kirundi.voicestudio.data.db.ProjectEntity
import org.kirundi.voicestudio.data.db.SessionEntity
import org.kirundi.voicestudio.data.db.SessionPrefs
import org.kirundi.voicestudio.data.db.formatDateTime

private enum class Form { NONE, PROJECT, PARTICIPANT }

@Composable
fun ProjectsScreen() {
    val context = LocalContext.current
    var snap by remember { mutableStateOf<DbSnapshot?>(null) }
    var message by remember { mutableStateOf("") }
    var activeId by remember { mutableStateOf(SessionPrefs.active(context)) }
    var form by remember { mutableStateOf(Form.NONE) }
    var formProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var field1 by remember { mutableStateOf("") }
    var field2 by remember { mutableStateOf("") }
    var deleteMsg by remember { mutableStateOf("") }
    var deleteAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun reload() {
        Db.loadAll(context) { snap = it }
    }
    LaunchedEffect(Unit) { reload() }

    val data = snap
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Projets", style = MaterialTheme.typography.titleLarge)
        Text(
            "Projet → participant → session. Les nouveaux enregistrements sont classés dans la session active.",
            fontSize = 13.sp
        )
        if (message.isNotEmpty()) Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)

        Button(onClick = {
            field1 = "AI0117"
            field2 = "Voices.com Cloning Rundi"
            form = Form.PROJECT
        }) { Text("Nouveau projet") }

        if (data == null) {
            Text("Chargement...")
        } else {
            if (data.projects.isEmpty()) Text("Aucun projet. Créez-en un (champs pré-remplis pour AI0117).")
            for (p in data.projects) {
                val parts = data.participants.filter { it.projectId == p.id }
                Text("Projet ${p.code} : ${p.name}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        field1 = ""
                        field2 = ""
                        formProject = p
                        form = Form.PARTICIPANT
                    }) { Text("Nouveau participant") }
                    TextButton(onClick = {
                        deleteMsg = "Supprimer le projet ${p.code} ?"
                        deleteAction = {
                            if (parts.isNotEmpty()) {
                                message = "Suppression refusée : le projet contient ${parts.size} participant(s). Supprimez-les d'abord."
                            } else {
                                Db.run(context, { dao -> dao.deleteProject(p) }) { reload() }
                            }
                        }
                    }) { Text("Supprimer") }
                }
                for (pa in parts) {
                    val sess = data.sessions.filter { it.participantId == pa.id }
                    Text("   Participant ${pa.code}", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            Db.run(context, { dao ->
                                dao.insertSession(
                                    SessionEntity(participantId = pa.id, startedAt = System.currentTimeMillis())
                                )
                            }) { id ->
                                SessionPrefs.setActive(context, id)
                                activeId = id
                                message = "Nouvelle session créée et activée."
                                reload()
                            }
                        }) { Text("Nouvelle session") }
                        TextButton(onClick = {
                            deleteMsg = "Supprimer le participant ${pa.code} ?"
                            deleteAction = {
                                if (sess.isNotEmpty()) {
                                    message = "Suppression refusée : le participant a ${sess.size} session(s)."
                                } else {
                                    Db.run(context, { dao -> dao.deleteParticipant(pa) }) { reload() }
                                }
                            }
                        }) { Text("Supprimer") }
                    }
                    for (s in sess) {
                        val count = data.recordings.count { it.sessionId == s.id }
                        val isActive = s.id == activeId
                        Text(
                            "      Session ${formatDateTime(s.startedAt)}  ($count enregistrement(s))" +
                                if (isActive) "  [ACTIVE]" else "",
                            fontSize = 12.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isActive) {
                                TextButton(onClick = {
                                    SessionPrefs.setActive(context, s.id)
                                    activeId = s.id
                                    message = "Session activée."
                                }) { Text("Activer") }
                            }
                            TextButton(onClick = {
                                deleteMsg = "Supprimer cette session ? Les $count enregistrement(s) seront conservés mais non classés."
                                deleteAction = {
                                    Db.run(context, { dao ->
                                        for (r in dao.recordings().filter { it.sessionId == s.id }) {
                                            dao.updateRecording(r.copy(sessionId = null))
                                        }
                                        dao.deleteSession(s)
                                    }) {
                                        if (SessionPrefs.active(context) == s.id) {
                                            SessionPrefs.setActive(context, 0L)
                                            activeId = 0L
                                        }
                                        reload()
                                    }
                                }
                            }) { Text("Supprimer") }
                        }
                    }
                }
            }
        }
    }

    if (form != Form.NONE) {
        val isProject = form == Form.PROJECT
        AlertDialog(
            onDismissRequest = { form = Form.NONE },
            title = { Text(if (isProject) "Nouveau projet" else "Nouveau participant") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = field1,
                        onValueChange = { field1 = it },
                        label = { Text(if (isProject) "Code du projet" else "Nom ou identifiant") },
                        singleLine = true
                    )
                    if (isProject) {
                        OutlinedTextField(
                            value = field2,
                            onValueChange = { field2 = it },
                            label = { Text("Nom du projet") },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val a = field1.trim()
                    val b = field2.trim()
                    if (a.isEmpty()) {
                        message = "Le champ est vide : rien n'a été créé."
                    } else if (isProject) {
                        Db.run(context, { dao ->
                            dao.insertProject(ProjectEntity(code = a, name = b, createdAt = System.currentTimeMillis()))
                        }) { reload() }
                    } else {
                        val proj = formProject
                        if (proj != null) {
                            Db.run(context, { dao ->
                                dao.insertParticipant(
                                    ParticipantEntity(projectId = proj.id, code = a, createdAt = System.currentTimeMillis())
                                )
                            }) { reload() }
                        }
                    }
                    form = Form.NONE
                }) { Text("Créer") }
            },
            dismissButton = { TextButton(onClick = { form = Form.NONE }) { Text("Annuler") } }
        )
    }

    val act = deleteAction
    if (act != null) {
        AlertDialog(
            onDismissRequest = { deleteAction = null },
            title = { Text("Confirmation") },
            text = { Text(deleteMsg) },
            confirmButton = {
                TextButton(onClick = {
                    act()
                    deleteAction = null
                }) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { deleteAction = null }) { Text("Annuler") } }
        )
    }
}
