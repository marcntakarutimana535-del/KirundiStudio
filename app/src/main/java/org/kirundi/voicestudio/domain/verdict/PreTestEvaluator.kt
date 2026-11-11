package org.kirundi.voicestudio.domain.verdict

import java.util.Locale

/** Résultat d'une étape du test avant enregistrement. */
class StepResult(val title: String, val status: Status, val lines: List<String>)

/**
 * Règles du PRE-RECORDING AUDIO TEST. Les seuils de niveau de voix (-20 / -3 dBFS)
 * sont des critères internes de bonne pratique, pas des exigences du client.
 */
object PreTestEvaluator {

    const val PROFILE_BITS = 24
    const val VOICE_PEAK_MIN_DB = -20.0
    const val VOICE_PEAK_MAX_DB = -3.0
    const val MIN_VOICE_WINDOWS = 20

    fun worst(list: List<Status>): Status {
        if (list.contains(Status.BAD)) return Status.BAD
        if (list.contains(Status.INCOMPLETE)) return Status.INCOMPLETE
        if (list.contains(Status.CHECK)) return Status.CHECK
        return Status.OK
    }

    private fun f1(x: Double): String = String.format(Locale.US, "%.1f", x)

    /** Étape 1 : détection, connexion, formats disponibles, signal d'entrée. */
    fun micStep(
        deviceLabel: String?,
        startError: String?,
        announcedRates: List<Int>,
        announcedFormats: List<String>,
        usedBits: Int,
        plannedFormat: String,
        peakDb: Double,
        rmsDb: Double,
        accept16: Boolean
    ): StepResult {
        val lines = ArrayList<String>()
        var bad = false
        var check = false

        if (deviceLabel == null) {
            bad = true
            lines.add("Détection : aucun micro détecté par Android.")
        } else {
            lines.add("Détection : $deviceLabel")
        }
        if (startError != null) {
            bad = true
            lines.add("Connexion : ÉCHEC - $startError")
        } else {
            lines.add("Connexion : micro ouvert avec succès.")
        }
        lines.add(
            "Formats annoncés : " +
                (if (announcedFormats.isEmpty()) "non annoncés" else announcedFormats.joinToString(", "))
        )
        if (announcedRates.isEmpty()) {
            lines.add("Fréquences annoncées : non précisées (48 000 Hz accordé à l'ouverture si le test réussit).")
        } else if (!announcedRates.contains(48000)) {
            check = true
            lines.add("Fréquence : 48 000 Hz n'est pas annoncée (${announcedRates.joinToString(", ")} Hz).")
        } else {
            lines.add("Fréquence : 48 000 Hz annoncée.")
        }
        lines.add("Format d'enregistrement : $plannedFormat")

        if (startError == null) {
            if (usedBits < PROFILE_BITS) {
                if (accept16) {
                    lines.add(
                        "Profondeur : $usedBits bits natifs, accepté par votre réglage " +
                            "(le profil demande $PROFILE_BITS bits : à confirmer avec Voices.com)."
                    )
                } else {
                    check = true
                    lines.add(
                        "Profondeur : $usedBits bits natifs. Le profil Voices.com demande $PROFILE_BITS bits : " +
                            "à valider avec le client, ou utiliser un micro USB capable de 24 bits."
                    )
                }
            } else {
                lines.add(
                    "Profondeur : $usedBits bits annoncés. La profondeur réelle n'est pas vérifiée dans ce test " +
                        "(elle l'est dans le rapport d'enregistrement du Studio)."
                )
            }
            if (peakDb.isNaN() || (peakDb.isInfinite() && peakDb < 0)) {
                bad = true
                lines.add("Signal d'entrée : AUCUN (le micro ne renvoie que des zéros).")
            } else {
                lines.add("Signal d'entrée : présent (Peak ${dbText(peakDb)}, RMS ${dbText(rmsDb)}).")
            }
        }

        val status = if (bad) Status.BAD else if (check) Status.CHECK else Status.OK
        return StepResult("ÉTAPE 1 : MICROPHONE", status, lines)
    }

    /** Étape 2 : silence. L'évaluation a été faite sans exiger de voix. */
    fun silenceStep(
        a: NoiseAssessment,
        s: AnalysisSettings,
        seconds: Double,
        globalRmsDb: Double,
        globalPeakDb: Double
    ): StepResult {
        val lines = ArrayList<String>()
        lines.add("Durée enregistrée : ${f1(seconds)} s")
        lines.add("RMS global : ${dbText(globalRmsDb)}")
        lines.add("Peak maximal : ${dbText(globalPeakDb)}")
        lines.add("RMS du silence, médiane / percentile 95 : ${dbText(a.silenceMedianDb)} / ${dbText(a.silenceP95Db)}")
        lines.add("Peak du bruit : ${dbText(a.noisePeakDb)}")
        lines.add("Méthode : ${s.method.label}")
        lines.add("Valeur retenue : ${dbText(a.noiseValueDb)} (cible : ${dbText(s.targetDb)} ou moins)")
        for (r in a.reasons) lines.add("- $r")
        for (i in a.info) lines.add("* $i")
        if (a.status == Status.OK) {
            lines.add("Mesuré au niveau d'entrée actuel ; il n'est valable que si le niveau de voix est correct (étape 3).")
        }
        return StepResult("ÉTAPE 2 : SILENCE", a.status, lines)
    }

    /** Étape 3 : voix (niveau, saturation, coupures) et rapport signal/bruit avec l'étape 2. */
    fun voiceStep(
        a: NoiseAssessment,
        s: AnalysisSettings,
        seconds: Double,
        globalRmsDb: Double,
        globalPeakDb: Double,
        clipEvents: Int,
        dropoutsAfterStart: Int,
        silenceGlobalRmsDb: Double
    ): StepResult {
        val lines = ArrayList<String>()
        var bad = false
        var incomplete = false
        var check = false
        var lowLevel = false

        lines.add("Durée enregistrée : ${f1(seconds)} s")
        lines.add("RMS global : ${dbText(globalRmsDb)}")
        lines.add("Peak maximal : ${dbText(globalPeakDb)}")
        lines.add("Fenêtres de voix détectées : ${a.voiceCount} (RMS de la voix : ${dbText(a.voiceRmsDb)})")

        if (a.voiceCount < PreTestEvaluator.MIN_VOICE_WINDOWS) {
            incomplete = true
            lines.add(
                "- Pas assez de parole détectée : ${a.voiceCount} fenêtre(s) de voix, $MIN_VOICE_WINDOWS requises (2 s). " +
                    "Parlez sans interruption pendant tout le test."
            )
        }
        if (clipEvents > 0) {
            bad = true
            lines.add("- Saturation : $clipEvents événement(s). Éloignez le micro ou parlez moins fort.")
        } else if (!incomplete) {
            if (globalPeakDb < VOICE_PEAK_MIN_DB) {
                check = true
                lowLevel = true
                lines.add(
                    "- Niveau trop bas : crête ${dbText(globalPeakDb)} (visé : entre ${VOICE_PEAK_MIN_DB.toInt()} et " +
                        "${VOICE_PEAK_MAX_DB.toInt()} dBFS). Rapprochez le micro (environ 5 à 10 cm, légèrement de côté)."
                )
            } else if (globalPeakDb > VOICE_PEAK_MAX_DB) {
                check = true
                lines.add("- Niveau très haut : crête ${dbText(globalPeakDb)}, marge de sécurité faible avant la saturation.")
            } else {
                lines.add("Niveau correct : crête entre ${VOICE_PEAK_MIN_DB.toInt()} et ${VOICE_PEAK_MAX_DB.toInt()} dBFS.")
            }
        }
        if (dropoutsAfterStart > 0) {
            check = true
            lines.add("- $dropoutsAfterStart coupure(s) détectée(s) après le démarrage (zéros exacts de 10 ms ou plus).")
        }

        if (!a.voiceRmsDb.isNaN() && !silenceGlobalRmsDb.isNaN() && !silenceGlobalRmsDb.isInfinite()) {
            val snr = a.voiceRmsDb - silenceGlobalRmsDb
            val eq = NoiseEvaluator.REF_VOICE_RMS_DB - snr
            lines.add("Rapport signal/bruit (voix et silence mesurés) : ${f1(snr)} dB")
            lines.add(
                "* Estimation : avec une voix réglée à ${dbText(NoiseEvaluator.REF_VOICE_RMS_DB)} (RMS), le bruit serait " +
                    "d'environ ${dbText(eq)}."
            )
            if (lowLevel && eq > s.targetDb) {
                lines.add("- Cette estimation dépasse la cible : même avec un bon niveau, le bruit risque d'être supérieur à ${dbText(s.targetDb)}.")
            }
        }

        val status = when {
            bad -> Status.BAD
            incomplete -> Status.INCOMPLETE
            check -> Status.CHECK
            else -> Status.OK
        }
        return StepResult("ÉTAPE 3 : VOIX", status, lines)
    }

    fun buildReport(
        dateText: String,
        deviceText: String,
        sourceName: String,
        accept16: Boolean,
        silenceSeconds: Int,
        steps: List<StepResult>,
        overall: Status,
        files: List<String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("KIRUNDI VOICE STUDIO - PRE-RECORDING AUDIO TEST")
        sb.appendLine("Date : $dateText")
        sb.appendLine("Appareil : $deviceText")
        sb.appendLine("Source audio demandée : $sourceName")
        sb.appendLine("Profil visé : Voices.com AI0117 (WAV PCM $PROFILE_BITS bits, 48 kHz, mono, bruit de fond -60 dBFS ou moins)")
        sb.appendLine("16 bits natifs acceptés (réglage) : ${if (accept16) "oui" else "non"}")
        sb.appendLine("Durée du test de silence : $silenceSeconds s")
        sb.appendLine()
        sb.appendLine("RÉSULTAT GLOBAL : ${overall.label}")
        for (st in steps) {
            sb.appendLine()
            sb.appendLine("${st.title} : ${st.status.label}")
            for (l in st.lines) sb.appendLine("  $l")
        }
        sb.appendLine()
        sb.appendLine("ÉTAPE 4 : RAPPORT")
        sb.appendLine("  Le résultat global est le plus défavorable des étapes (NON CONFORME, puis INCOMPLET, puis À VÉRIFIER).")
        if (files.isNotEmpty()) {
            sb.appendLine("  Fichiers du test (visibles dans l'onglet Analyse) : ${files.joinToString(", ")}")
        }
        sb.appendLine()
        sb.appendLine("NOTES : la méthode officielle de mesure du bruit de Voices.com n'est pas connue.")
        sb.appendLine("Les seuils de niveau de voix (-20 / -3 dBFS) sont des critères internes, pas des exigences du client.")
        sb.appendLine("Les traitements internes du pilote du téléphone ne sont pas détectables par l'application.")
        return sb.toString()
    }
}
