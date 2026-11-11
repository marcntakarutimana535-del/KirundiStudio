package org.kirundi.voicestudio.audio.pretest

import android.content.Context
import org.kirundi.voicestudio.audio.capture.LevelInfo
import org.kirundi.voicestudio.audio.capture.Recorder
import org.kirundi.voicestudio.audio.capture.RecordingSummary
import org.kirundi.voicestudio.audio.device.encodingLabel
import org.kirundi.voicestudio.audio.device.typeLabel
import java.io.File

/** Ce que le micro choisi annonce à Android. */
class InputDesc(val label: String, val rates: List<Int>, val formats: List<String>)

/** Enregistre un fichier d'une durée fixe (arrêt automatique) avec le même moteur que le Studio. */
class PreTestRunner(context: Context) {

    private val recorder = Recorder(context)

    /** Retourne un message d'erreur, ou null si l'enregistrement a démarré. */
    fun record(
        file: File,
        source: Int,
        seconds: Double,
        onLevel: (LevelInfo) -> Unit,
        onDone: (RecordingSummary) -> Unit
    ): String? {
        var stopSent = false
        return recorder.start(
            file = file,
            sourceChoice = source,
            onLevel = { info ->
                onLevel(info)
                if (!stopSent && info.seconds >= seconds) {
                    stopSent = true
                    recorder.stop()
                }
            },
            onFinished = onDone
        )
    }

    fun cancel() {
        recorder.stop()
    }

    companion object {
        fun describeInput(context: Context): InputDesc? {
            val d = Recorder.pickInput(context) ?: return null
            return InputDesc(
                label = "${d.productName} (${typeLabel(d.type)})",
                rates = d.sampleRates.toList(),
                formats = d.encodings.map { encodingLabel(it) }
            )
        }
    }
}
