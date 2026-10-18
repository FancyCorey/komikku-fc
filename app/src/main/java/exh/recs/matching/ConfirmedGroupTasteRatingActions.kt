package exh.recs.matching

import eu.kanade.domain.source.service.SourcePreferences
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.interactor.ClearMangaTaste
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.interactor.InsertMangaTasteIfAbsent
import tachiyomi.domain.taste.interactor.SetMangaTasteBatch
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface ConfirmedGroupTasteRatingActions {
    suspend fun propagateToConfirmedMember(origin: Manga, member: Manga)
    suspend fun currentRating(origin: Manga): MangaRating?
    suspend fun setGroupRating(origin: Manga, rating: MangaRating?)
}

object NoOpConfirmedGroupTasteRatingActions : ConfirmedGroupTasteRatingActions {
    override suspend fun propagateToConfirmedMember(origin: Manga, member: Manga) = Unit
    override suspend fun currentRating(origin: Manga): MangaRating? = null
    override suspend fun setGroupRating(origin: Manga, rating: MangaRating?) = Unit
}

class ConfirmedGroupTasteRatingManager(
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getCrossSourceMangaLinks: GetCrossSourceMangaLinks = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getMangaTaste: GetMangaTaste = Injekt.get(),
    private val setMangaTasteBatch: SetMangaTasteBatch = Injekt.get(),
    private val insertMangaTasteIfAbsent: InsertMangaTasteIfAbsent = Injekt.get(),
    private val clearMangaTaste: ClearMangaTaste = Injekt.get(),
    private val localTrackerRepository: LocalTrackerRepository = Injekt.get(),
    private val identityResolver: CrossSourceIdentityAuthorizationResolver = CrossSourceIdentityAuthorizationResolver(),
) : ConfirmedGroupTasteRatingActions {
    private val trackedTargets = ConfirmedTrackedMangaTasteTargets(
        getCrossSourceMangaLinks = getCrossSourceMangaLinks,
        getManga = getManga,
        identityResolver = identityResolver,
        localTrackerRepository = localTrackerRepository,
    )

    override suspend fun propagateToConfirmedMember(origin: Manga, member: Manga) {
        if (!sourcePreferences.confirmedTrackedVersionRatingPropagationEnabled().get()) return

        val confirmedMembers = confirmedGroupLinks(origin)
        if (confirmedMembers.none { it.source == member.source && it.url == member.url }) return
        val trackedMembers = trackedTargets.await(origin)
        if (trackedMembers.none { it.sameRecord(member) }) return

        val groupTastes = confirmedMembers
            .filterNot { it.source == member.source && it.url == member.url }
            .mapNotNull { getMangaTaste.await(it.source, it.url) }
        // Read the destination after the group scan so a pre-existing manual rating wins.
        val destinationTaste = getMangaTaste.await(member.source, member.url)
        val rating = ConfirmedGroupTasteRatingPolicy.joiningRating(
            enabled = sourcePreferences.confirmedTrackedVersionRatingPropagationEnabled().get(),
            destinationTaste = destinationTaste,
            existingGroupTastes = groupTastes,
        ) ?: return
        val journalEntries = exh.util.EvaluationModeJournalRecorder.buildRatingChange(
            getMangaTaste = getMangaTaste,
            manga = listOf(member),
            newRating = rating.value,
            actionType = ratingActionType(rating),
        )
        if (!insertMangaTasteIfAbsent.await(member, rating)) return
        exh.util.EvaluationModeJournalRecorder.commit(journalEntries)
    }

    override suspend fun currentRating(origin: Manga): MangaRating? {
        val tastes = confirmedGroupLinks(origin).mapNotNull { link ->
            getMangaTaste.await(link.source, link.url)
        }
        return ConfirmedGroupTasteRatingPolicy.effectiveRating(tastes)
    }

    override suspend fun setGroupRating(origin: Manga, rating: MangaRating?) {
        val targets = trackedTargets.await(origin).filter { manga ->
            localTrackerRepository.getWorkIdBySourceUrl(manga.source, manga.url) != null
        }
        if (targets.isEmpty()) return
        val journalEntries = exh.util.EvaluationModeJournalRecorder.buildRatingChange(
            getMangaTaste = getMangaTaste,
            manga = targets,
            newRating = rating?.value,
            actionType = ratingActionType(rating),
        )
        if (rating == null) {
            targets.forEach { manga -> clearMangaTaste.await(manga.source, manga.url) }
        } else {
            setMangaTasteBatch.await(targets, rating)
        }
        exh.util.EvaluationModeJournalRecorder.commit(journalEntries)
    }

    private fun ratingActionType(rating: MangaRating?): exh.util.EvaluationJournalActionType = when (rating) {
        MangaRating.LOVE -> exh.util.EvaluationJournalActionType.RATE_LOVE
        MangaRating.LIKE -> exh.util.EvaluationJournalActionType.RATE_LIKE
        MangaRating.DISLIKE -> exh.util.EvaluationJournalActionType.RATE_DISLIKE
        MangaRating.NOT_INTERESTED -> exh.util.EvaluationJournalActionType.NOT_INTERESTED
        null -> exh.util.EvaluationJournalActionType.CLEAR_RATING
    }

    private suspend fun confirmedGroupLinks(origin: Manga): List<CrossSourceMangaLink> {
        val originLink = getCrossSourceMangaLinks.awaitBySourceUrl(origin.source, origin.url)
            ?: return listOf(
                CrossSourceMangaLink(
                    source = origin.source,
                    url = origin.url,
                    groupId = "",
                    title = origin.title,
                    createdAt = 0L,
                    updatedAt = 0L,
                ),
            )
        return identityResolver.confirmedGroupMembers(
            origin.source,
            origin.url,
            getCrossSourceMangaLinks.awaitByGroupId(originLink.groupId),
        )
    }

    private fun Manga.sameRecord(other: Manga): Boolean = source == other.source && url == other.url
}
