package exh.recs

import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.repository.RecentReadingRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.TasteProfile
import tachiyomi.domain.taste.model.normalizeTag

// KMK -->
/**
 * v0.8.24 recent-reading profile: turns what the person actually read in the recent window into positive per-tag
 * weights that the For You scorer adds next to the long-term (rating-derived) weights.
 *
 * Rules (see [RecentReadingPolicy] for the numbers):
 *  - Off when "Use recent reading" is off; fails open to no recent weights on any non-cancellation error.
 *  - Titles rated Dislike or Not interested are not counted as recent interest.
 *  - A confirmed linked group counts once: the strongest member represents it (no summing across versions).
 *  - A tag group with any explicit negative tag preference (Dislike or Block) receives no recent weight, so the
 *    person's direct controls always win; Block remains a hard rejection in the scorer.
 *  - Weights are rounded and based on whole days, so they only change when reading changes or a day passes.
 *
 * Nothing is persisted: the result is computed from the existing history, ratings and confirmed-group data.
 */
class GetRecentReadingTagWeights(
    private val recentReadingRepository: RecentReadingRepository,
    private val mangaRepository: MangaRepository,
    private val getMangaTaste: GetMangaTaste,
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks,
    private val sourcePreferences: SourcePreferences,
    private val identityAuthorizationResolver: CrossSourceIdentityAuthorizationResolver =
        CrossSourceIdentityAuthorizationResolver(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Entry(val groupKey: String, val strength: Double, val genres: List<String>)

    /**
     * @param profile the long-term profile; its explicit tag preferences and blocked groups decide which groups
     * may not receive recent weight.
     * @param aliasMap normalized tag alias -> group key, the same map the scorer resolves candidate tags with.
     */
    suspend fun await(profile: TasteProfile, aliasMap: Map<String, String>): Map<String, Double> {
        if (!sourcePreferences.recommendationRecentReadingEnabled().get()) return emptyMap()
        return try {
            compute(profile, aliasMap)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Recent-reading profile failed, continuing without it" }
            emptyMap()
        }
    }

    private suspend fun compute(profile: TasteProfile, aliasMap: Map<String, String>): Map<String, Double> {
        val windowDays = RecentReadingPolicy.resolveWindowDays(
            sourcePreferences.recommendationRecentReadingWindowDays().get(),
        )
        val minChapters = RecentReadingPolicy.resolveMinChapters(
            sourcePreferences.recommendationRecentReadingMinChapters().get(),
        )
        val now = clock()
        val activity = recentReadingRepository.getRecentReadingActivity(
            RecentReadingPolicy.windowStartMillis(now, windowDays),
        )
        if (activity.isEmpty()) return emptyMap()

        val excludedMangaIds = getMangaTaste.awaitAll()
            .filter { it.rating == MangaRating.DISLIKE.value || it.rating == MangaRating.NOT_INTERESTED.value }
            .map { it.mangaId }
            .toSet()

        val entries = activity.mapNotNull { row ->
            if (row.mangaId in excludedMangaIds) return@mapNotNull null
            val strength = RecentReadingPolicy.strength(row, windowDays, minChapters, now)
            if (strength <= 0.0) return@mapNotNull null
            val manga = try {
                mangaRepository.getMangaById(row.mangaId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val genres = manga.genre.orEmpty()
            if (genres.isEmpty()) return@mapNotNull null
            Entry(groupKey = identityKey(manga), strength = strength, genres = genres)
        }
        if (entries.isEmpty()) return emptyMap()

        // One representative per confirmed group: the strongest member, never a sum across versions.
        val representatives = entries.groupBy { it.groupKey }.values.map { group -> group.maxBy { it.strength } }

        val sums = mutableMapOf<String, Double>()
        for (entry in representatives) {
            val weight = RecentReadingPolicy.perGenreWeight(entry.strength)
            entry.genres
                .map { genre -> genre.normalizeTag().let { aliasMap[it] ?: it } }
                .distinct()
                .forEach { group -> sums[group] = (sums[group] ?: 0.0) + weight }
        }

        val vetoed = profile.blockedGroups +
            profile.explicitTagPreferences.filterValues { it < 0 }.keys
        return sums
            .filterKeys { it !in vetoed }
            .mapValues { (_, sum) -> RecentReadingPolicy.finalGroupWeight(sum) }
            .filterValues { it > 0.0 }
    }

    /** Confirmed siblings share one key; an unlinked or unconfirmed manga is its own group. */
    private suspend fun identityKey(manga: Manga): String {
        val link = getCrossSourceMangaLinks.awaitBySourceUrl(manga.source, manga.url)
            ?: return "manga:${manga.id}"
        val confirmed = identityAuthorizationResolver.confirmedGroupMembers(
            originSource = manga.source,
            originUrl = manga.url,
            members = getCrossSourceMangaLinks.awaitByGroupId(link.groupId),
        )
        return if (confirmed.size > 1) "group:${link.groupId}" else "manga:${manga.id}"
    }
}
// KMK <--
