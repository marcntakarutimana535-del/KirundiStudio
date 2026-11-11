package org.kirundi.voicestudio.data.db

import android.content.Context
import org.json.JSONObject
import org.kirundi.voicestudio.data.files.RecordingStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Garde la base en accord avec les fichiers WAV de l'application :
 * les nouveaux fichiers sont ajoutés (avec les infos de leur fichier .json),
 * les fichiers disparus sont retirés de la liste.
 */
object RecordingIndex {

    fun sync(context: Context, dao: AppDao, activeSessionId: Long) {
        val files = RecordingStore.list(context)
        val rows = dao.recordings()
        val known = rows.map { it.fileName }.toSet()
        val names = files.map { it.name }.toSet()

        for (r in rows) {
            if (r.fileName !in names) dao.deleteRecording(r)
        }
        for (f in files) {
            if (f.name in known) continue
            val session = if (!f.name.startsWith("pretest_") && activeSessionId > 0L) activeSessionId else null
            dao.insertRecording(build(f, session))
        }
    }

    private fun parseDb(text: String): Double? {
        val m = Regex("-?\\d+(\\.\\d+)?").find(text) ?: return null
        return m.value.toDoubleOrNull()
    }

    private fun build(f: File, sessionId: Long?): RecordingEntity {
        val info = RecordingStore.info(f)
        var created = f.lastModified()
        var duration = info?.seconds ?: 0.0
        var bits = info?.bits ?: 0
        val sampleRate = info?.sampleRate ?: 0
        var nativeDepth = "non renseignée"
        var source = "inconnue"
        var device = "inconnu"
        var rms: Double? = null
        var peak: Double? = null
        var clip = 0
        var sha: String? = null

        val sc = RecordingStore.sidecar(f)
        if (sc.exists()) {
            try {
                val j = JSONObject(sc.readText())
                val dateText = j.optString("date_debut", "")
                if (dateText.isNotEmpty()) {
                    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(dateText)
                    if (parsed != null) created = parsed.time
                }
                if (j.has("duree_s")) duration = j.getDouble("duree_s")
                if (j.has("profondeur_wav_bits")) bits = j.getInt("profondeur_wav_bits")
                nativeDepth = j.optString("profondeur_native", nativeDepth)
                source = j.optString("source_demandee", source)
                device = j.optString("peripherique_route", device)
                rms = if (j.has("rms_global_db")) j.getDouble("rms_global_db") else parseDb(j.optString("rms_global", ""))
                peak = if (j.has("peak_max_db")) j.getDouble("peak_max_db") else parseDb(j.optString("peak_max", ""))
                clip = j.optInt("saturations_evenements", 0)
                val h = j.optString("sha256", "")
                if (h.isNotEmpty() && h != "indisponible") sha = h
            } catch (e: Exception) {
                // fichier .json illisible : on garde les informations de l'en-tête WAV
            }
        }

        return RecordingEntity(
            fileName = f.name,
            sessionId = sessionId,
            createdAt = created,
            durationSec = duration,
            sampleRate = sampleRate,
            bits = bits,
            nativeDepth = nativeDepth,
            source = source,
            device = device,
            rmsDb = rms,
            peakDb = peak,
            clipEvents = clip,
            sha256 = sha
        )
    }
}
