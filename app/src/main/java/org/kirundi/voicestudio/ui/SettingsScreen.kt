package org.kirundi.voicestudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.data.settings.AppSettings
import org.kirundi.voicestudio.data.settings.SettingsStore
import org.kirundi.voicestudio.domain.verdict.NoiseMethod
import java.util.Locale

@Composable
private fun SettingStepper(label: String, value: Double, step: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label : ${String.format(Locale.US, "%.0f", value)} dB",
            modifier = Modifier.weight(1f),
            fontSize = 14.sp
        )
        OutlinedButton(onClick = { onChange((value - step).coerceIn(min, max)) }) { Text("-") }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(onClick = { onChange((value + step).coerceIn(min, max)) }) { Text("+") }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.read(context)) }
    var confirmReset by remember { mutableStateOf(false) }

    fun change(n: AppSettings) {
        s = n
        SettingsStore.save(context, n)
    }

    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
    val files = RecordingStore.list(context)
    val usedMb = files.sumOf { it.length() } / (1024.0 * 1024.0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Réglages", style = MaterialTheme.typography.titleLarge)
        Text("Ces valeurs servent de réglages de départ dans les autres onglets.", fontSize = 13.sp)

        Text("Enregistrement", style = MaterialTheme.typography.titleSmall)
        val sources = listOf(
            "UNPROCESSED (recommandée : aucun traitement logiciel)",
            "VOICE_RECOGNITION (peut appliquer des traitements)",
            "MIC (peut appliquer des traitements)"
        )
        for (i in sources.indices) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { change(s.copy(defaultSource = i)) }
            ) {
                RadioButton(selected = s.defaultSource == i, onClick = { change(s.copy(defaultSource = i)) })
                Text(sources[i], fontSize = 13.sp)
            }
        }

        Text("Contrôle du bruit de fond", style = MaterialTheme.typography.titleSmall)
        for (m in NoiseMethod.values()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { change(s.copy(noiseMethod = m.ordinal)) }
            ) {
                RadioButton(selected = s.noiseMethod == m.ordinal, onClick = { change(s.copy(noiseMethod = m.ordinal)) })
                Text(m.label, fontSize = 13.sp)
            }
        }
        SettingStepper("Cible de bruit (au plus)", s.targetDb, 1.0, -90.0, -20.0) { change(s.copy(targetDb = it)) }
        SettingStepper("Marge « voix » au-dessus du bruit", s.voiceMarginDb, 1.0, 6.0, 30.0) { change(s.copy(voiceMarginDb = it)) }
        SettingStepper("Marge « silence » au-dessus du bruit", s.silenceMarginDb, 1.0, 1.0, 12.0) { change(s.copy(silenceMarginDb = it)) }
        Text(
            "La méthode de mesure du client n'est pas connue : ne changez la cible que si Voices.com vous donne une autre valeur.",
            fontSize = 11.sp
        )

        Text("Profil Voices.com", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.accept16, onCheckedChange = { change(s.copy(accept16 = it)) })
            Text(
                "  16 bits natifs acceptés (à valider avec le client)",
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
        }

        Text("Export", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = s.nameTemplate,
            onValueChange = { change(s.copy(nameTemplate = it)) },
            label = { Text("Modèle de nom des fichiers") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("Mots utilisables : {projet} {participant} {date} {n} {original}", fontSize = 11.sp)

        OutlinedButton(onClick = { confirmReset = true }) { Text("Rétablir les réglages par défaut") }

        Text("À propos", style = MaterialTheme.typography.titleSmall)
        Text(
            "KIRUNDI VOICE STUDIO, version $version\n" +
                "Fonctionne entièrement hors ligne : aucune donnée n'est envoyée.\n" +
                "Enregistrements : ${files.size} fichier(s), ${String.format(Locale.US, "%.1f", usedMb)} Mo, " +
                "stockés dans l'espace privé de l'application (ils disparaissent si vous la désinstallez : exportez-les).\n" +
                "Le micro intégré du téléphone ne fournit que du 16 bits natif : l'application ne présente jamais du 16 bits converti comme du 24 bits natif.",
            fontSize = 12.sp
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Rétablir les réglages ?") },
            text = { Text("Tous les réglages reviennent à leurs valeurs par défaut. Vos enregistrements ne sont pas touchés.") },
            confirmButton = {
                TextButton(onClick = {
                    change(AppSettings())
                    confirmReset = false
                }) { Text("Rétablir") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } }
        )
    }
}
