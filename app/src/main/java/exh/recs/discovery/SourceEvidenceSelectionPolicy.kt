package exh.recs.discovery

import tachiyomi.domain.taste.model.SourceEvaluation
import tachiyomi.domain.taste.model.SourceRecommendationFit

// KMK -->
/** Chooses one persisted evidence row when an old database contains duplicate keys. */
internal object SourceEvidenceSelectionPolicy {
    fun latestEvaluations(rows: List<SourceEvaluation>): Map<String, SourceEvaluation> =
        rows.groupBy { it.evaluationKey }
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<SourceEvaluation> { it.evaluationVersion }
                        .thenBy { it.evaluatedAt }
                        .thenBy { it.extensionVersionCode ?: Long.MIN_VALUE }
                        .thenBy { it.sourceName }
                        .thenBy { it.toString() },
                )!!
            }

    fun latestFits(rows: List<SourceRecommendationFit>): Map<String, SourceRecommendationFit> =
        rows.groupBy { it.evaluationKey }
            .mapValues { (_, candidates) ->
                candidates.maxWithOrNull(
                    compareBy<SourceRecommendationFit> { it.evaluationVersion }
                        .thenBy { it.evaluatedAt }
                        .thenBy { it.fitKey }
                        .thenBy { it.toString() },
                )!!
            }
}
// KMK <--
