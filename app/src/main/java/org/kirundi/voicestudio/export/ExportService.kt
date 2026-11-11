package org.kirundi.voicestudio.export

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import org.kirundi.voicestudio.audio.wav.WavConverter
import org.kirundi.voicestudio.data.db.DbSnapshot
import org.kirundi.voicestudio.data.db.ExportEntity
import org.kirundi.voicestudio.data.db.RecordingEntity
import org.kirundi.voicestudio.data.db.dbOrDash
import org.kirundi.voicestudio.data.db.formatDateTime
import org.kirundi.voicestudio.data.db.formatDur
import org.kirundi.voicestudio.data.files.RecordingStore
import org.kirundi.voicestudio.data.files.sha256Hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExportRun(val messages: List<String>, val exports: List<ExportEntity>, val allOk: Boolean)

/**
 * Export final d'une série d'enregistrements dans un dossier choisi par l'utilisateur :
 * sous-dossier nommé, fichiers renommés, vérification SHA-256, liste SHA256SUMS.txt et rapport PDF.
 * Les originaux ne sont jamais modifiés. À appeler hors du fil principal.
 */
object ExportService {

    private fun clean(s: String): String {
        val c = s.trim().replace(Regex("[^\\p{L}\\p{N}_.-]"), "_").trim('_', '.')
        return if (c.isEmpty()) "x" else c
    }

    private fun day(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    fun run(
        context: Context,
        tree: Uri,
        snap: DbSnapshot,
        selected: List<RecordingEntity>,
        sessionId: Long?,
        template: String,
        convert24: Boolean,
        withPdf: Boolean,
        onProgress: (String) -> Unit
    ): ExportRun {
        val messages = ArrayList<String>()
        val exports = ArrayList<ExportEntity>()
        var allOk = true
        val resolver = context.contentResolver

        val session = snap.sessions.firstOrNull { it.id == sessionId }
        val participant = snap.participants.firstOrNull { it.id == session?.participantId }
        val project = snap.projects.firstOrNull { it.id == participant?.projectId }
        val projCode = clean(project?.code ?: "SANS-PROJET")
        val partCode = clean(participant?.code ?: "SANS-PARTICIPANT")
        val folderDay = day(session?.startedAt ?: System.currentTimeMillis())
        val fmtTag = if (convert24) "24bit" else "original"
        val folderName = "${projCode}_${partCode}_${folderDay}_$fmtTag"

        val parent = try {
            DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        } catch (e: Exception) {
            return ExportRun(listOf("Dossier de destination invalide : ${e.message}"), exports, false)
        }
        val folder = try {
            DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, folderName)
        } catch (e: Exception) {
            null
        }
        if (folder == null) {
            return ExportRun(listOf("Impossible de créer le dossier « $folderName » à cet endroit."), exports, false)
        }
        messages.add("Dossier créé : $folderName")

        class Done(val rec: RecordingEntity, val name: String, val hash: String?, val ok: Boolean, val msg: String)
        val done = ArrayList<Done>()
        val usedNames = HashSet<String>()
        val ordered = selected.sortedBy { it.createdAt }
        var index = 0

        for (rec in ordered) {
            index += 1
            onProgress("Export $index / ${ordered.size} : ${rec.fileName}")
            val src = File(RecordingStore.dir(context), rec.fileName)
            if (!src.exists()) {
                allOk = false
                messages.add("${rec.fileName} : fichier introuvable, ignoré.")
                continue
            }
            if (convert24 && rec.bits != 16) {
                allOk = false
                messages.add("${rec.fileName} : ignoré (la conversion 24 bits ne concerne que le 16 bits ; ce fichier est en ${rec.bits} bits).")
                continue
            }
            var base = template
                .replace("{projet}", projCode)
                .replace("{participant}", partCode)
                .replace("{date}", day(rec.createdAt))
                .replace("{n}", String.format(Locale.US, "%03d", index))
                .replace("{original}", rec.fileName.removeSuffix(".wav"))
            base = clean(base).removeSuffix(".wav")
            var finalName = "$base.wav"
            var k = 2
            while (!usedNames.add(finalName.lowercase(Locale.ROOT))) {
                finalName = "${base}_$k.wav"
                k += 1
            }

            val target = try {
                DocumentsContract.createDocument(resolver, folder, "audio/wav", finalName)
            } catch (e: Exception) {
                null
            }
            if (target == null) {
                allOk = false
                messages.add("$finalName : création du fichier impossible.")
                continue
            }

            var ok: Boolean
            var hash: String?
            var msg: String
            if (convert24) {
                val r = WavConverter.exportTo24(context, src, target)
                ok = r.ok
                hash = r.sha256
                msg = r.message
            } else {
                msg = RecordingStore.exportTo(context, src, target)
                ok = msg.startsWith("Export réussi")
                hash = if (ok) {
                    try { src.inputStream().use { sha256Hex(it) } } catch (e: Exception) { null }
                } else null
            }
            if (!ok) {
                allOk = false
                try { DocumentsContract.deleteDocument(resolver, target) } catch (e: Exception) { }
                msg += " Le fichier exporté défectueux a été supprimé du dossier de destination ; l'original est intact."
            }
            messages.add("$finalName : $msg")
            done.add(Done(rec, finalName, hash, ok, msg))
            exports.add(
                ExportEntity(
                    fileName = rec.fileName,
                    exportedAt = System.currentTimeMillis(),
                    format = if (convert24) "WAV 24 bits (converti depuis 16 bits) → $finalName" else "WAV original → $finalName",
                    sha256 = hash,
                    verified = ok,
                    message = msg
                )
            )
        }

        val good = done.filter { it.ok && it.hash != null }

        // Liste des empreintes, au format de la commande sha256sum.
        if (good.isNotEmpty()) {
            try {
                val m = DocumentsContract.createDocument(resolver, folder, "text/plain", "SHA256SUMS.txt")
                if (m != null) {
                    resolver.openOutputStream(m)?.use { o ->
                        val text = good.joinToString("\n") { "${it.hash}  ${it.name}" } + "\n"
                        o.write(text.toByteArray(Charsets.UTF_8))
                    }
                    messages.add("SHA256SUMS.txt écrit (${good.size} fichier(s)).")
                }
            } catch (e: Exception) {
                allOk = false
                messages.add("SHA256SUMS.txt non écrit : ${e.message}")
            }
        }

        if (withPdf) {
            onProgress("Création du rapport PDF...")
            try {
                val pdf = DocumentsContract.createDocument(resolver, folder, "application/pdf", "RAPPORT_$folderName.pdf")
                if (pdf != null) {
                    val out = resolver.openOutputStream(pdf)
                    if (out != null) {
                        out.use { buildReport(snap, project?.code, project?.name, participant?.code, session?.startedAt, convert24, done.map { Triple(it.rec, it.name, it.hash) to (it.ok) }).save(it) }
                        messages.add("Rapport PDF écrit : RAPPORT_$folderName.pdf")
                    } else {
                        allOk = false
                        messages.add("Rapport PDF non écrit : destination illisible.")
                    }
                }
            } catch (e: Exception) {
                allOk = false
                messages.add("Rapport PDF non écrit : ${e.message}")
            }
        }
        return ExportRun(messages, exports, allOk)
    }

    private fun buildReport(
        snap: DbSnapshot,
        projectCode: String?,
        projectName: String?,
        participantCode: String?,
        sessionStart: Long?,
        convert24: Boolean,
        items: List<Pair<Triple<RecordingEntity, String, String?>, Boolean>>
    ): PdfReport {
        val r = PdfReport()
        r.title("KIRUNDI VOICE STUDIO - Rapport d'export")
        r.line("Projet : ${projectCode ?: "non renseigné"}  ${projectName ?: ""}")
        r.line("Participant : ${participantCode ?: "non renseigné"}")
        r.line("Session : ${if (sessionStart != null) formatDateTime(sessionStart) else "non classé"}")
        r.line("Date de l'export : ${formatDateTime(System.currentTimeMillis())}")
        r.line("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}   Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        r.line("Nombre de fichiers : ${items.size}")

        r.heading("Format des fichiers livrés")
        if (convert24) {
            r.warning("WAV 24 bits CONVERTI depuis une capture 16 bits natives.")
            r.line("Chaque échantillon 16 bits a été multiplié par 256 (sans perte, sans ajout de détail). " +
                "Le fichier est un WAV 24 bits valide, mais ce n'est PAS une capture native en 24 bits.")
        } else {
            r.line("Fichiers WAV originaux, copiés sans modification (profondeur indiquée pour chaque fichier).")
        }

        r.heading("Fichiers")
        var i = 0
        for ((t, ok) in items) {
            i += 1
            val rec = t.first
            r.space(4f)
            r.boldLine("$i. ${t.second}${if (ok) "" else "   [ÉCHEC D'EXPORT]"}")
            r.line("Fichier source : ${rec.fileName}   Enregistré le : ${formatDateTime(rec.createdAt)}   Durée : ${formatDur(rec.durationSec)}")
            r.line("WAV d'origine : ${rec.sampleRate} Hz, ${rec.bits} bits, mono   Profondeur native : ${rec.nativeDepth}")
            r.line("Source audio : ${rec.source}   Entrée : ${rec.device}")
            r.line("RMS global : ${dbOrDash(rec.rmsDb)}   Crête max : ${dbOrDash(rec.peakDb)}   Saturations : ${rec.clipEvents}")
            val m = snap.measures.firstOrNull { it.kind == "analyse" && it.fileName == rec.fileName }
            if (m == null) {
                r.warning("CONTRÔLE INCOMPLET - VALIDATION MANUELLE NÉCESSAIRE (aucune analyse enregistrée pour ce fichier).")
            } else {
                r.line("Analyse du ${formatDateTime(m.createdAt)} : statut ${m.status}")
                r.line("Bruit de fond : ${dbOrDash(m.noiseDb)}   RSB : ${m.snrDb?.let { String.format(Locale.US, "%.1f dB", it) } ?: "n/d"}   Méthode : ${m.method}")
            }
            r.line("SHA-256 du fichier livré :")
            r.monoLine(t.third ?: "non disponible")
        }

        r.heading("Remarques")
        r.line("Les niveaux sont mesurés par cette application (dBFS). La méthode de mesure du bruit utilisée par le client " +
            "n'est pas connue : ce rapport n'est pas une validation par le client.")
        r.line("Les enregistrements originaux de l'application ne sont jamais modifiés par l'export.")
        return r
    }
}
