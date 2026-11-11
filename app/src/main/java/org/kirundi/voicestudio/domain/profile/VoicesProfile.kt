package org.kirundi.voicestudio.domain.profile

import android.content.Context
import org.kirundi.voicestudio.data.db.MeasureEntity
import org.kirundi.voicestudio.data.db.RecordingEntity
import org.kirundi.voicestudio.data.db.dbOrDash
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.data.files.sha256Hex
import org.kirundi.voicestudio.data.settings.SettingsStore
import org.kirundi.voicestudio.domain.verdict.PreTestEvaluator
import org.kirundi.voicestudio.domain.verdict.Status
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Profil « VOICES.COM – AI0117 CLONING RUNDI » (cahier des charges, section 9). */
object VoicesProfile {
    const val NAME = "VOICES.COM – AI0117 CLONING RUNDI"
    const val SAMPLE_RATE = 48000
    const val CHANNELS = 1
    const val BITS = 24
    const val NOISE_TARGET_DB = -60.0
    const val DESCRIPTION = "WAV, PCM non compressé, 24 bits, 48 000 Hz, mono, bruit de fond ≤ -60 dBFS, aucun effet ni traitement."
}

/** Réglage : accepter une capture 16 bits natifs (à valider avec le client). */
object ProfilePrefs {
    fun accept16(context: Context): Boolean = SettingsStore.read(context).accept16

    fun setAccept16(context: Context, value: Boolean) {
        SettingsStore.save(context, SettingsStore.read(context).copy(accept16 = value))
    }
}

class ProfileCheck(val title: String, val status: Status, val detail: String)

class ProfileReport(val fileName: String, val checks: List<ProfileCheck>) {
    val overall: Status = PreTestEvaluator.worst(checks.map { it.status })

    fun asText(): String {
        val sb = StringBuilder()
        sb.appendLine("PROFIL ${VoicesProfile.NAME}")
        sb.appendLine("Fichier : $fileName")
        sb.appendLine("RÉSULTAT GLOBAL : ${overall.label}")
        sb.appendLine()
        for (c in checks) {
            sb.appendLine("${c.title} : ${c.status.label}")
            sb.appendLine("  ${c.detail}")
        }
        return sb.toString().trimEnd()
    }
}

/** Contrôles avant exportation. Lecture seule : le fichier n'est jamais modifié. À appeler hors du fil principal. */
object ProfileChecker {

    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)

    private class Header(
        val riff: Boolean,
        val audioFormat: Int,
        val channels: Int,
        val sampleRate: Int,
        val bits: Int,
        val dataSize: Long
    )

    private fun readHeader(file: File): Header? {
        if (file.length() < 44) return null
        val h = ByteArray(44)
        try {
            RandomAccessFile(file, "r").use { it.readFully(h) }
        } catch (e: Exception) {
            return null
        }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val riff = String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(h, 8, 4, Charsets.US_ASCII) == "WAVE" &&
            String(h, 12, 4, Charsets.US_ASCII) == "fmt " &&
            String(h, 36, 4, Charsets.US_ASCII) == "data"
        return Header(
            riff = riff,
            audioFormat = bb.getShort(20).toInt() and 0xFFFF,
            channels = bb.getShort(22).toInt(),
            sampleRate = bb.getInt(24),
            bits = bb.getShort(34).toInt(),
            dataSize = bb.getInt(40).toLong() and 0xFFFFFFFFL
        )
    }

    /**
     * @param delivered24 true si le fichier livré sera la version convertie en 24 bits.
     * @param accept16 réglage : les 16 bits natifs sont « à valider avec le client » au lieu de « non conformes ».
     */
    fun check(
        context: Context,
        rec: RecordingEntity,
        measure: MeasureEntity?,
        delivered24: Boolean,
        accept16: Boolean
    ): ProfileReport {
        val file = File(RecordingStore.dir(context), rec.fileName)
        val checks = ArrayList<ProfileCheck>()
        val h = if (file.exists()) readHeader(file) else null

        if (h == null || !h.riff) {
            checks.add(ProfileCheck("Encodage", Status.INCOMPLETE, "En-tête WAV illisible ou fichier introuvable : vérification impossible."))
            return ProfileReport(rec.fileName, checks)
        }

        // 1. Encodage
        checks.add(
            if (h.audioFormat == 1) ProfileCheck("Encodage", Status.OK, "WAV PCM non compressé.")
            else ProfileCheck("Encodage", Status.BAD, "Format WAV n° ${h.audioFormat} : le profil demande du PCM non compressé (code 1).")
        )

        // 2. Fréquence
        checks.add(
            if (h.sampleRate == VoicesProfile.SAMPLE_RATE) ProfileCheck("Fréquence", Status.OK, "${h.sampleRate} Hz.")
            else ProfileCheck("Fréquence", Status.BAD, "${h.sampleRate} Hz : le profil demande ${VoicesProfile.SAMPLE_RATE} Hz.")
        )

        // 3. Canaux
        checks.add(
            if (h.channels == VoicesProfile.CHANNELS) ProfileCheck("Canaux", Status.OK, "Mono.")
            else ProfileCheck("Canaux", Status.BAD, "${h.channels} canaux : le profil demande mono.")
        )

        // 4. Profondeur : conteneur du fichier livré et profondeur réellement capturée
        val container = if (delivered24) 24 else h.bits
        val nat = rec.nativeDepth
        val nativeDepth: Int? = when {
            nat.startsWith("16 bits natifs") -> 16
            nat.startsWith("NON") -> 16
            else -> null
        }
        val depth: ProfileCheck = when {
            nat.startsWith("INDÉTERMIN") || (nativeDepth == null && !nat.startsWith("OUI")) ->
                ProfileCheck(
                    "Profondeur réelle", Status.INCOMPLETE,
                    "Conteneur $container bits ; profondeur réellement capturée INDÉTERMINÉE ($nat)."
                )
            nat.startsWith("OUI") ->
                ProfileCheck(
                    "Profondeur réelle", Status.CHECK,
                    "Conteneur $container bits ; 24 bits natifs probables (les bits de poids faible varient). C'est un indice, pas une preuve absolue."
                )
            else -> {
                val why = if (delivered24) {
                    "Conteneur 24 bits obtenu par CONVERSION d'une capture 16 bits : ce n'est pas du 24 bits natif."
                } else {
                    "Conteneur $container bits, capture 16 bits natifs : le profil demande du 24 bits."
                }
                if (accept16) {
                    ProfileCheck("Profondeur réelle", Status.CHECK, "$why À valider avec le client (réglage « 16 bits acceptés » activé).")
                } else {
                    ProfileCheck("Profondeur réelle", Status.BAD, "$why Ne pas la présenter comme du 24 bits natif.")
                }
            }
        }
        checks.add(depth)

        // 5. Intégrité
        val actual = file.length() - 44
        val integrity: ProfileCheck = if (h.dataSize != actual || h.dataSize <= 0L) {
            ProfileCheck(
                "Intégrité", Status.CHECK,
                "Taille déclarée dans l'en-tête (${h.dataSize} octets) différente de la taille réelle ($actual octets)."
            )
        } else {
            val recorded = rec.sha256
            if (recorded == null) {
                ProfileCheck("Intégrité", Status.CHECK, "En-tête cohérent, mais aucun SHA-256 d'origine enregistré pour comparer.")
            } else {
                val now = try { file.inputStream().use { sha256Hex(it) } } catch (e: Exception) { null }
                when {
                    now == null -> ProfileCheck("Intégrité", Status.INCOMPLETE, "Fichier illisible pour le calcul du SHA-256.")
                    now == recorded -> ProfileCheck("Intégrité", Status.OK, "En-tête cohérent ; SHA-256 identique à celui calculé à la fin de l'enregistrement (${now.take(16)}...).")
                    else -> ProfileCheck("Intégrité", Status.BAD, "SHA-256 différent de celui enregistré à la création : le fichier a changé depuis l'enregistrement.")
                }
            }
        }
        checks.add(integrity)

        // 6. Saturation
        checks.add(
            if (rec.clipEvents == 0) ProfileCheck("Saturation (clipping)", Status.OK, "Aucune saturation détectée pendant l'enregistrement.")
            else ProfileCheck("Saturation (clipping)", Status.BAD, "${rec.clipEvents} événement(s) de saturation détecté(s).")
        )

        // 7. Bruit de fond : méthode précisée, RMS et Peak séparés
        if (measure == null) {
            checks.add(
                ProfileCheck(
                    "Bruit de fond", Status.INCOMPLETE,
                    "Aucune analyse enregistrée pour ce fichier : lancez l'onglet Analyse."
                )
            )
        } else {
            val st = Status.values().firstOrNull { it.label == measure.status } ?: Status.INCOMPLETE
            checks.add(
                ProfileCheck(
                    "Bruit de fond", st,
                    "Analyse du ${org.kirundi.voicestudio.data.db.formatDateTime(measure.createdAt)}. " +
                        "Méthode : ${measure.method}. Bruit (RMS) retenu : ${dbOrDash(measure.noiseDb)} " +
                        "(cible ≤ ${f1(VoicesProfile.NOISE_TARGET_DB)} dBFS). " +
                        "RSB : ${measure.snrDb?.let { f1(it) + " dB" } ?: "n/d"}. " +
                        "La méthode de mesure du client n'est pas connue."
                )
            )
        }

        // 8. Traitements audio
        val src = rec.source
        checks.add(
            if (src == "UNPROCESSED") {
                ProfileCheck(
                    "Effets / traitements", Status.OK,
                    "Source UNPROCESSED demandée (aucun effet logiciel d'Android). Les traitements internes du pilote du téléphone ne sont pas détectables par l'application."
                )
            } else {
                ProfileCheck(
                    "Effets / traitements", Status.CHECK,
                    "Source utilisée : $src. Elle peut appliquer des traitements système ; la source UNPROCESSED est recommandée."
                )
            }
        )

        return ProfileReport(rec.fileName, checks)
    }
}
