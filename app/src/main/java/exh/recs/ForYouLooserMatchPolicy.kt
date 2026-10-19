package exh.recs

import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.TasteProfile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// KMK -->
/**
 * v0.8.24 looser matches. When a source returned manga but none of them matched the user's taste (the relevance gate:
 * at least one matching tag), For You showed an empty row, most often for poorly tagged sources. With the setting on,
 * such a source shows a few of the manga it already returned instead, labelled as looser matches.
 *
 * Only the relevance gate is relaxed. The candidates have already passed the user's own rules (blocked and rated
 * titles, hide-known, seen, minimum chapters), and the scorer still drops blocked tags and any negative taste evidence.
 * No extra request is made: the pool is what this source's searches already fetched in this refresh. Looser matches never
 * reach Top Picks, the candidate memory or the results cache, and the source still counts as "Filtered out".
 */
internal object ForYouLooserMatchPolicy {

    /** Provisional: at most this many looser matches per source. */
    const val MAX_LOOSER_MATCHES = 4

    fun pick(pool: List<Manga>, profile: TasteProfile, aliasMap: Map<String, String>): List<PersonalRecommendation> =
        PersonalRecommendationScorer.rankCandidates(
            candidates = pool.distinctBy { it.id },
            profile = profile,
            aliasMap = aliasMap,
            limit = MAX_LOOSER_MATCHES,
            allowNeutralCandidates = true,
        ).map { PersonalRecommendation(it.manga, it.score, matchedGroups = emptyList(), looser = true) }
}

/**
 * Collects, for one source's search, the candidates that passed the user's rules. Present in the coroutine context only
 * while looser matches are enabled, so the existing search code needs no new parameters.
 */
internal class LooserCandidatePool : AbstractCoroutineContextElement(LooserCandidatePool) {
    companion object Key : CoroutineContext.Key<LooserCandidatePool>

    private val candidates = mutableListOf<Manga>()

    fun add(manga: List<Manga>) {
        synchronized(candidates) { candidates += manga }
    }

    fun snapshot(): List<Manga> = synchronized(candidates) { candidates.toList() }
}
// KMK <--
