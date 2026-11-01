package exh.recs.settings

import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.domain.taste.interactor.ClearMangaTaste
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.model.MangaRating
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/**
 * The four "Reset and clear" actions on Troubleshooting. They used to be five bare text buttons at the bottom of the
 * Source evaluation screen; "Reset disliked sources" and "Clear disliked suggestions" did exactly the same thing (both cleared
 * `dislikedRecommendationSourceKeys`), so they are one action here.
 *
 * Nothing here touches the legacy seen-manga preference: Not interested titles are cleared through [ClearMangaTaste], which
 * is the one authority for ratings (see SourceEvaluationScreenModelClearSeenMangaSourceTest's history).
 */
class ResetAndClearActions(
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getMangaTaste: GetMangaTaste = Injekt.get(),
    private val clearMangaTaste: ClearMangaTaste = Injekt.get(),
) {

    enum class Action {
        /** Sources marked "don't suggest" can be suggested again. */
        FORGET_DISLIKED_SOURCES,

        /** Sources hidden from Sources to try come back. */
        SHOW_HIDDEN_SUGGESTIONS,

        /** Titles marked Not interested can appear in For You again. Ratings are untouched. */
        CLEAR_NOT_INTERESTED,

        /** Source evaluation stops recommending a re-check until ratings change by the threshold again. */
        RESET_RATINGS_BASELINE,
    }

    suspend fun perform(action: Action) {
        when (action) {
            Action.FORGET_DISLIKED_SOURCES -> sourcePreferences.dislikedRecommendationSourceKeys().set("")
            Action.SHOW_HIDDEN_SUGGESTIONS -> sourcePreferences.dismissedNonInstalledRecommendationSources().set("")
            Action.RESET_RATINGS_BASELINE -> {
                sourcePreferences.sourceEvaluationLastReassessmentRatingCount().set(0)
                sourcePreferences.sourceEvaluationLastReassessmentAt().set(0L)
            }
            Action.CLEAR_NOT_INTERESTED -> {
                val notInterested = getMangaTaste.awaitAll().filter { it.rating == MangaRating.NOT_INTERESTED.value }
                val journalEntries = exh.util.EvaluationModeJournalRecorder.buildRatingChangeFromTaste(
                    notInterested,
                    null,
                    exh.util.EvaluationJournalActionType.CLEAR_RATING,
                )
                notInterested.forEach { taste ->
                    runCatching { clearMangaTaste.await(taste.source, taste.url) }
                }
                exh.util.EvaluationModeJournalRecorder.commit(journalEntries)
            }
        }
    }
}
// KMK <--
