package org.kirundi.voicestudio.data.db

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun formatDateTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

fun formatDur(sec: Double): String {
    val total = sec.toInt()
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

fun finiteOrNull(d: Double): Double? = if (d.isNaN() || d.isInfinite()) null else d

fun dbOrDash(d: Double?): String =
    if (d == null) "n/d" else String.format(Locale.US, "%.1f dBFS", d)

/** Photographie de la base, avec les libellés utiles à l'affichage. */
class DbSnapshot(
    val projects: List<ProjectEntity>,
    val participants: List<ParticipantEntity>,
    val sessions: List<SessionEntity>,
    val recordings: List<RecordingEntity>,
    val measures: List<MeasureEntity>,
    val exports: List<ExportEntity>
) {
    fun sessionLabel(sessionId: Long?): String {
        if (sessionId == null) return "non classé"
        val s = sessions.firstOrNull { it.id == sessionId } ?: return "session supprimée"
        val p = participants.firstOrNull { it.id == s.participantId }
        val j = projects.firstOrNull { it.id == p?.projectId }
        return "${j?.code ?: "?"} / ${p?.code ?: "?"} / ${formatDateTime(s.startedAt)}"
    }
}

/** Session active : les nouveaux enregistrements y sont classés automatiquement. */
object SessionPrefs {
    private const val FILE = "kvs_prefs"
    private const val KEY = "active_session"

    fun active(context: Context): Long =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY, 0L)

    fun setActive(context: Context, id: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY, id).apply()
    }
}

object SessionLabels {
    fun activeLabel(context: Context, dao: AppDao): String {
        val id = SessionPrefs.active(context)
        if (id <= 0L) {
            return "Aucune session active : les enregistrements ne seront pas classés (onglet Projets)."
        }
        val s = dao.session(id) ?: return "Session active introuvable : choisissez-en une dans l'onglet Projets."
        val p = dao.participant(s.participantId)
        val j = if (p != null) dao.project(p.projectId) else null
        return "Session active : ${j?.code ?: "?"} / ${p?.code ?: "?"} / ${formatDateTime(s.startedAt)}"
    }
}

/** Toutes les opérations sur la base se font hors du fil principal. */
object Db {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(
        context: Context,
        work: (AppDao) -> T,
        onError: (String) -> Unit = {},
        done: (T) -> Unit
    ) {
        val app = context.applicationContext
        executor.execute {
            try {
                val r = work(AppDatabase.get(app).dao())
                main.post { done(r) }
            } catch (e: Exception) {
                main.post { onError("Erreur de base de données : ${e.message}") }
            }
        }
    }

    /** Met l'index des fichiers à jour, puis charge toute la base. */
    fun loadAll(context: Context, done: (DbSnapshot) -> Unit) {
        run(
            context,
            work = { dao ->
                RecordingIndex.sync(context, dao, SessionPrefs.active(context))
                DbSnapshot(
                    dao.projects(), dao.participants(), dao.sessions(),
                    dao.recordings(), dao.measures(), dao.exports()
                )
            },
            done = done
        )
    }
}
