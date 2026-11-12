package exh.recs.matching

import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.tracker.repository.LocalTrackerRepository

/**
 * The versions a rating reaches: every member of the origin's link group that is confirmed as the same manga, tracked locally or
 * not (v0.8.25; before, only locally tracked ones, so a confirmed version outside local tracking kept an old rating). Unconfirmed
 * or merely discovered candidates must remain explicit user selections. [localTrackerRepository] is kept for the existing
 * construction sites.
 */
class ConfirmedTrackedMangaTasteTargets(
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks,
    private val getManga: GetManga,
    private val identityResolver: CrossSourceIdentityAuthorizationResolver,
    private val localTrackerRepository: LocalTrackerRepository,
) {
    suspend fun await(origin: Manga): List<Manga> {
        val originLink = getCrossSourceMangaLinks.awaitBySourceUrl(origin.source, origin.url)
            ?: return listOf(origin)
        val members = identityResolver.confirmedGroupMembers(
            origin.source,
            origin.url,
            getCrossSourceMangaLinks.awaitByGroupId(originLink.groupId),
        )
        val targets = buildList {
            for (member in members) {
                val manga = getManga.await(member.url, member.source)
                    ?: Manga.create().copy(source = member.source, url = member.url, ogTitle = member.title)
                add(manga)
            }
        }
        return targets.distinctBy { it.source to it.url }.ifEmpty { listOf(origin) }
    }
}
