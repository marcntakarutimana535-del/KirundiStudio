package org.kirundi.voicestudio.domain.verdict

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Statut d'un contrôle. Les données insuffisantes ne donnent jamais « CONFORME ». */
enum class Status(val label: String) {
    OK("CONFORME"),
    CHECK("À VÉRIFIER"),
    BAD("NON CONFORME"),
    INCOMPLETE("CONTRÔLE INCOMPLET – VALIDATION MANUELLE NÉCESSAIRE")
}

/** Méthodes de mesure du bruit de fond (la méthode officielle du client est inconnue). */
enum class NoiseMethod(val label: String) {
    GLOBAL("A : RMS global du fichier (fichier de silence)"),
    WINDOW_MEDIAN("B : médiane des fenêtres calmes (100 ms)"),
    WINDOW_P95("B95 : percentile 95 des fenêtres calmes")
}

data class AnalysisSettings(
    val method: NoiseMethod = NoiseMethod.WINDOW_MEDIAN,
    val targetDb: Double = -60.0,
    val voiceMarginDb: Double = 12.0,
    val silenceMarginDb: Double = 6.0
)

fun dbText(d: Double): String = when {
    d.isNaN() -> "non disponible"
    d.isInfinite() -> "-inf dBFS"
    else -> String.format(Locale.US, "%.1f dBFS", d)
}

/** Résultat de l'évaluation. kinds : 0 = silence, 1 = intermédiaire, 2 = voix (une valeur par fenêtre). */
class NoiseAssessment(
    val noiseRefDb: Double,
    val kinds: IntArray,
    val silenceCount: Int,
    val ambiguousCount: Int,
    val voiceCount: Int,
    val silencePowerDb: Double,
    val silenceMedianDb: Double,
    val silenceP95Db: Double,
    val noisePeakDb: Double,
    val voiceRmsDb: Double,
    val voicePeakDb: Double,
    val snrDb: Double,
    val equivalentNoiseDb: Double,
    val intermittentRanges: List<Pair<Double, Double>>,
    val noiseValueDb: Double,
    val status: Status,
    val reasons: List<String>,
    val info: List<String>
)

object NoiseEvaluator {

    const val REF_VOICE_RMS_DB = -20.0
    const val MIN_SILENCE_WINDOWS = 20
    const val MIN_GLOBAL_SECONDS = 10.0
    private const val LOW_VOICE_PEAK_DB = -20.0

    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val idx = ceil(p / 100.0 * s.size).toInt() - 1
        return s[idx.coerceIn(0, s.size - 1)]
    }

    fun powerAverageDb(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        var sum = 0.0
        for (v in values) sum += 10.0.pow(v / 10.0)
        return 10.0 * log10(sum / values.size)
    }

    fun evaluate(
        rmsDb: DoubleArray,
        peakDb: DoubleArray,
        globalRmsDb: Double,
        seconds: Double,
        clipEvents: Int,
        windowSeconds: Double,
        settings: AnalysisSettings
    ): NoiseAssessment {
        val n = rmsDb.size
        val ref = if (n == 0) Double.NaN else max(percentile(rmsDb.toList(), 10.0), -120.0)
        val kinds = IntArray(n)
        val sil = ArrayList<Double>()
        val voiceRmsList = ArrayList<Double>()
        var amb = 0
        var voice = 0
        var noisePeak = Double.NEGATIVE_INFINITY
        var voicePeak = Double.NEGATIVE_INFINITY

        for (i in 0 until n) {
            val d = rmsDb[i]
            val k = if (d > ref + settings.voiceMarginDb) 2 else if (d > ref + settings.silenceMarginDb) 1 else 0
            kinds[i] = k
            when (k) {
                0 -> {
                    sil.add(d)
                    if (peakDb[i] > noisePeak) noisePeak = peakDb[i]
                }
                1 -> amb++
                else -> {
                    voice++
                    voiceRmsList.add(d)
                    if (peakDb[i] > voicePeak) voicePeak = peakDb[i]
                }
            }
        }
        if (sil.isEmpty()) noisePeak = Double.NaN
        if (voice == 0) voicePeak = Double.NaN

        // Plages de temps des fenêtres intermédiaires (bruits intermittents possibles)
        val ranges = ArrayList<Pair<Double, Double>>()
        var start = -1
        for (i in 0..n) {
            val inAmb = i < n && kinds[i] == 1
            if (inAmb && start < 0) start = i
            if (!inAmb && start >= 0) {
                ranges.add(Pair(start * windowSeconds, i * windowSeconds))
                start = -1
            }
        }

        val silPower = powerAverageDb(sil)
        val silMedian = percentile(sil, 50.0)
        val silP95 = percentile(sil, 95.0)
        val voicePower = powerAverageDb(voiceRmsList)
        val snr = if (sil.isEmpty() || voiceRmsList.isEmpty()) Double.NaN else voicePower - silPower
        val eq = if (snr.isNaN()) Double.NaN else REF_VOICE_RMS_DB - snr

        val reasons = ArrayList<String>()
        val info = ArrayList<String>()
        var incomplete = false
        var bad = false
        var check = false
        var noiseValue = Double.NaN

        if (n < 10) {
            incomplete = true
            reasons.add("Fichier trop court : moins d'1 seconde analysable.")
        } else if (ref <= -110.0) {
            incomplete = true
            reasons.add(
                "Silence numérique : des fenêtres sont à zéro exact. Un vrai micro produit toujours un peu de bruit ; " +
                    "cela indique une coupure ou un traitement du pilote (porte de bruit). La mesure n'est pas fiable."
            )
        } else {
            when (settings.method) {
                NoiseMethod.GLOBAL -> {
                    if (seconds < MIN_GLOBAL_SECONDS) {
                        incomplete = true
                        reasons.add("Méthode A : il faut au moins 10 s de silence (ici ${"%.1f".format(Locale.US, seconds)} s).")
                    } else if (voice > 0) {
                        incomplete = true
                        reasons.add(
                            "Méthode A non applicable : de la voix ou un son fort est présent dans le fichier " +
                                "($voice fenêtre(s)). Utilisez un fichier de silence pur, ou la méthode B."
                        )
                    } else {
                        noiseValue = globalRmsDb
                    }
                }
                NoiseMethod.WINDOW_MEDIAN, NoiseMethod.WINDOW_P95 -> {
                    if (sil.size < MIN_SILENCE_WINDOWS) {
                        incomplete = true
                        reasons.add(
                            "Pas assez de silence : ${sil.size} fenêtre(s) calme(s), il en faut au moins " +
                                "$MIN_SILENCE_WINDOWS (2 s)."
                        )
                    } else {
                        noiseValue = if (settings.method == NoiseMethod.WINDOW_MEDIAN) silMedian else silP95
                    }
                }
            }
        }

        if (!incomplete) {
            if (noiseValue > settings.targetDb) {
                bad = true
                reasons.add(
                    "Bruit mesuré ${dbText(noiseValue)}, au-dessus de la cible ${dbText(settings.targetDb)}."
                )
            } else {
                if (voice == 0) {
                    check = true
                    reasons.add(
                        "Aucune voix détectée dans ce fichier : le niveau d'enregistrement de la voix n'a pas été vérifié."
                    )
                } else if (voicePeak < LOW_VOICE_PEAK_DB) {
                    check = true
                    reasons.add(
                        "Niveau de voix trop bas (crête de la voix ${dbText(voicePeak)}). Le bruit mesuré à ce niveau " +
                            "n'est pas représentatif d'un enregistrement correctement réglé."
                    )
                }
            }
        }
        if (clipEvents > 0) {
            check = true
            reasons.add("Saturation détectée : $clipEvents événement(s).")
        }
        if (ranges.isNotEmpty()) {
            info.add(
                "${ranges.size} plage(s) de bruit intermittent possible (niveau entre silence et voix), " +
                    "exclue(s) de la mesure du bruit."
            )
        }
        if (!eq.isNaN()) {
            info.add(
                "Estimation : avec une voix réglée à ${dbText(REF_VOICE_RMS_DB)} (RMS), le même bruit serait d'environ " +
                    "${dbText(eq)}. Estimation valable seulement si le rapport signal/bruit reste identique."
            )
        }

        val status = when {
            incomplete -> Status.INCOMPLETE
            bad -> Status.BAD
            check -> Status.CHECK
            else -> Status.OK
        }

        return NoiseAssessment(
            noiseRefDb = ref,
            kinds = kinds,
            silenceCount = sil.size,
            ambiguousCount = amb,
            voiceCount = voice,
            silencePowerDb = silPower,
            silenceMedianDb = silMedian,
            silenceP95Db = silP95,
            noisePeakDb = noisePeak,
            voiceRmsDb = voicePower,
            voicePeakDb = voicePeak,
            snrDb = snr,
            equivalentNoiseDb = eq,
            intermittentRanges = ranges,
            noiseValueDb = noiseValue,
            status = status,
            reasons = reasons,
            info = info
        )
    }
}
