package org.kirundi.voicestudio.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.kvsDataStore by preferencesDataStore(name = "kvs_settings")

/** Réglages de l'application (enregistrés avec DataStore). */
data class AppSettings(
    val accept16: Boolean = false,
    val defaultSource: Int = 0,
    val noiseMethod: Int = 1,
    val targetDb: Double = -60.0,
    val voiceMarginDb: Double = 12.0,
    val silenceMarginDb: Double = 6.0,
    val nameTemplate: String = DEFAULT_TEMPLATE
) {
    companion object {
        const val DEFAULT_TEMPLATE = "{projet}_{participant}_{date}_{n}"
    }
}

object SettingsStore {
    private val ACCEPT16 = booleanPreferencesKey("accept16")
    private val SOURCE = intPreferencesKey("default_source")
    private val METHOD = intPreferencesKey("noise_method")
    private val TARGET = doublePreferencesKey("target_db")
    private val VOICE_MARGIN = doublePreferencesKey("voice_margin_db")
    private val SILENCE_MARGIN = doublePreferencesKey("silence_margin_db")
    private val TEMPLATE = stringPreferencesKey("name_template")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: AppSettings? = null

    private fun legacyAccept16(context: Context): Boolean =
        context.getSharedPreferences("kvs_prefs", Context.MODE_PRIVATE).getBoolean("accept16", false)

    private fun fromPrefs(context: Context, p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            accept16 = p[ACCEPT16] ?: legacyAccept16(context),
            defaultSource = (p[SOURCE] ?: d.defaultSource).coerceIn(0, 2),
            noiseMethod = (p[METHOD] ?: d.noiseMethod).coerceIn(0, 2),
            targetDb = (p[TARGET] ?: d.targetDb).coerceIn(-90.0, -20.0),
            voiceMarginDb = (p[VOICE_MARGIN] ?: d.voiceMarginDb).coerceIn(6.0, 30.0),
            silenceMarginDb = (p[SILENCE_MARGIN] ?: d.silenceMarginDb).coerceIn(1.0, 12.0),
            nameTemplate = p[TEMPLATE] ?: d.nameTemplate
        )
    }

    /** Lecture rapide (petit fichier) : la première lecture se fait sur le fil appelant. */
    fun read(context: Context): AppSettings {
        val c = cache
        if (c != null) return c
        val app = context.applicationContext
        val s = try {
            runBlocking { fromPrefs(app, app.kvsDataStore.data.first()) }
        } catch (e: Exception) {
            AppSettings(accept16 = legacyAccept16(app))
        }
        cache = s
        return s
    }

    fun save(context: Context, s: AppSettings) {
        cache = s
        val app = context.applicationContext
        scope.launch {
            try {
                app.kvsDataStore.edit { p ->
                    p[ACCEPT16] = s.accept16
                    p[SOURCE] = s.defaultSource
                    p[METHOD] = s.noiseMethod
                    p[TARGET] = s.targetDb
                    p[VOICE_MARGIN] = s.voiceMarginDb
                    p[SILENCE_MARGIN] = s.silenceMarginDb
                    p[TEMPLATE] = s.nameTemplate
                }
            } catch (e: Exception) {
                // l'écriture a échoué : les réglages en mémoire restent valables jusqu'à la fermeture
            }
        }
    }
}
