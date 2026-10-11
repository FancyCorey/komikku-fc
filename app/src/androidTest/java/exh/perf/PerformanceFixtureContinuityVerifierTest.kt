package exh.perf

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.repository.TasteRepository
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Read-only comparison of the already-installed realistic fixture to its deterministic generator. */
@RunWith(AndroidJUnit4::class)
class PerformanceFixtureContinuityVerifierTest {

    @Test
    fun installedRealisticFixtureHasCompleteScalarAndRelationshipContinuity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals("app.komikku.benchmark", target.packageName)
        assertEquals(0, target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)

        val expected = PerformanceFixtureGenerator.generate(PerformanceFixtureSpec.REALISTIC)
        // The seed receipt fixes these generator totals. Never regenerate or insert fixture rows here.
        val expectedChapterTotal = expected.manga.sumOf { it.chapters.size }
        val expectedHistoryTotal = expected.manga.sumOf { it.historyChapterIndices.size }
        val mismatches = linkedMapOf<String, Int>()
        val expectedIdentity = expected.manga.map { FixtureIdentity(it.source, it.url) }
        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val categoryRepository = Injekt.get<CategoryRepository>()
        val historyRepository = Injekt.get<HistoryRepository>()
        val tasteRepository = Injekt.get<TasteRepository>()
        val localTrackerRepository = Injekt.get<LocalTrackerRepository>()

        val allManga = mangaRepository.getAll()
        val fixtureManga = allManga.filter { PerformanceFixtureSelectors.isFixtureMangaUrl(it.url) }
        val mangaByIdentity = fixtureManga.groupBy { FixtureIdentity(it.source, it.url) }
        recordSetDiff("manga_identity", expectedIdentity, mangaByIdentity.keys, mismatches)
        recordCount("manga_identity_duplicate", mangaByIdentity.values.sumOf { (it.size - 1).coerceAtLeast(0) }, mismatches)

        val expectedMangaShape = expected.manga.map { manga ->
            MangaShape(
                FixtureIdentity(manga.source, manga.url), manga.title, manga.author, manga.artist,
                manga.description, manga.genres, manga.thumbnailUrl, manga.favorite, initialized = true,
            )
        }
        val actualMangaShape = fixtureManga.map { manga ->
            MangaShape(
                FixtureIdentity(manga.source, manga.url), manga.ogTitle, manga.ogAuthor, manga.ogArtist,
                manga.ogDescription, manga.ogGenre.orEmpty(), manga.ogThumbnailUrl, manga.favorite, manga.initialized,
            )
        }
        recordSetDiff("manga_scalar", expectedMangaShape, actualMangaShape, mismatches)

        val expectedCategoryNames = expected.categoryNames
        val actualCategoryRows = categoryRepository.getAll()
            .filter { PerformanceFixtureSelectors.isFixtureCategoryName(it.name) }
        recordSetDiff("category_identity", expectedCategoryNames, actualCategoryRows.map { it.name }, mismatches)
        recordCount("category_identity_duplicate", actualCategoryRows.size - actualCategoryRows.map { it.name }.toSet().size, mismatches)
        recordSetDiff(
            "category_scalar",
            expectedCategoryNames.map { CategoryShape(it, order = 0L, flags = 0L, hidden = false) },
            actualCategoryRows.map { CategoryShape(it.name, it.order, it.flags, it.hidden) },
            mismatches,
        )

        val expectedChapterShape = expected.manga.flatMap { manga ->
            val identity = FixtureIdentity(manga.source, manga.url)
            manga.chapters.map { chapter ->
                ChapterShape(
                    identity,
                    chapter.url,
                    chapter.name,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                )
            }
        }
        val actualChapterShape = mutableListOf<ChapterShape>()
        val actualChapters = mutableListOf<tachiyomi.domain.chapter.model.Chapter>()
        val actualHistories = mutableListOf<tachiyomi.domain.history.model.History>()
        val chapterIdsByMangaId = mutableMapOf<Long, Map<Long, String>>()
        val expectedCategoryLinks = mutableListOf<CategoryLink>()
        val actualCategoryLinks = mutableListOf<CategoryLink>()
        val expectedHistoryLinks = mutableListOf<HistoryLink>()
        val actualHistoryLinks = mutableListOf<HistoryLink>()
        var mangaDateAddedPositive = 0
        var chapterDateFetchPositive = 0
        var chapterDateUploadPositive = 0
        var nonNullHistoryReadAt = 0
        var historyDurationMatches = 0

        fixtureManga.forEach { manga ->
            val identity = FixtureIdentity(manga.source, manga.url)
            if (manga.dateAdded > 0L) mangaDateAddedPositive++
            val chapters = chapterRepository.getChapterByMangaId(manga.id)
            actualChapters += chapters
            chapterIdsByMangaId[manga.id] = chapters.associate { it.id to it.url }
            chapters.forEach { chapter ->
                if (chapter.dateFetch > 0L) chapterDateFetchPositive++
                if (chapter.dateUpload > 0L) chapterDateUploadPositive++
                actualChapterShape += ChapterShape(
                    identity,
                    chapter.url,
                    chapter.name,
                    chapter.chapterNumber,
                    chapter.sourceOrder,
                    chapter.read,
                    chapter.bookmark,
                    chapter.lastPageRead,
                )
            }

            val categories = categoryRepository.getCategoriesByMangaId(manga.id)
            categories.forEach { actualCategoryLinks += CategoryLink(identity, it.name) }

            val histories = historyRepository.getHistoryByMangaId(manga.id)
            actualHistories += histories
            val chapterUrlById = chapterIdsByMangaId[manga.id].orEmpty()
            histories.forEach { history ->
                if (history.readAt != null) nonNullHistoryReadAt++
                if (history.readDuration == FIXTURE_HISTORY_DURATION_MS) historyDurationMatches++
                val url = chapterUrlById[history.chapterId] ?: UNKNOWN_CHAPTER_URL
                actualHistoryLinks += HistoryLink(identity, url)
            }
        }
        expected.manga.forEach { manga ->
            val identity = FixtureIdentity(manga.source, manga.url)
            manga.categoryIndices.forEach { index ->
                expectedCategoryLinks += CategoryLink(identity, expected.categoryNames[index])
            }
            manga.historyChapterIndices.forEach { index ->
                val chapter = manga.chapters.getOrNull(index)
                expectedHistoryLinks += HistoryLink(identity, chapter?.url ?: UNKNOWN_CHAPTER_URL)
            }
        }
        recordSetDiff("chapter_shape", expectedChapterShape, actualChapterShape, mismatches)
        recordSetDiff("manga_category_relationship", expectedCategoryLinks, actualCategoryLinks, mismatches)
        recordSetDiff("history_chapter_relationship", expectedHistoryLinks, actualHistoryLinks, mismatches)
        recordCount("history_unknown_chapter", actualHistoryLinks.count { it.chapterUrl == UNKNOWN_CHAPTER_URL }, mismatches)
        recordCount("history_read_at_missing", actualHistoryLinks.size - nonNullHistoryReadAt, mismatches)
        recordCount("history_read_duration_mismatch", actualHistoryLinks.size - historyDurationMatches, mismatches)

        // Persisted database ids are resolved from stable source+URL identity; generated order is
        // never treated as a database id.
        val mangaIdByIdentity = mangaByIdentity.mapNotNull { (identity, rows) ->
            rows.singleOrNull()?.let { identity to it.id }
        }.toMap()
        val fixtureIdsToIdentity = fixtureManga.associate { it.id to FixtureIdentity(it.source, it.url) }
        val expectedIdentitySet = expectedIdentity.toSet()
        val actualTastes = tasteRepository.getAllMangaTastes()
            .filter { FixtureIdentity(it.source, it.url) in expectedIdentitySet }
        val expectedTastes = expected.manga.mapNotNull { manga ->
            manga.rating?.let { TasteShape(FixtureIdentity(manga.source, manga.url), manga.title, it.value) }
        }
        val actualTasteShapes = actualTastes.map { TasteShape(FixtureIdentity(it.source, it.url), it.title, it.rating) }
        recordSetDiff("rating_membership_and_value", expectedTastes, actualTasteShapes, mismatches)
        recordCount(
            "rating_manga_id_mismatch",
            actualTastes.count { taste ->
                mangaIdByIdentity[FixtureIdentity(taste.source, taste.url)] != taste.mangaId
            },
            mismatches,
        )
        recordCount("rating_timestamp_missing", actualTastes.count { it.createdAt <= 0L || it.updatedAt <= 0L }, mismatches)

        val expectedCrossLinks = expected.manga.mapNotNull { manga ->
            manga.crossSourceGroupId?.let {
                CrossLinkShape(it, FixtureIdentity(manga.source, manga.url), manga.title)
            }
        }
        val actualCrossLinkRows = tasteRepository.getAllCrossSourceMangaLinks()
            .filter { it.groupId.startsWith(FIXTURE_GROUP_PREFIX) }
        val actualCrossLinks = actualCrossLinkRows
            .map { CrossLinkShape(it.groupId, FixtureIdentity(it.source, it.url), it.title) }
        recordSetDiff("cross_source_member_relationship", expectedCrossLinks, actualCrossLinks, mismatches)

        val expectedPrimaries = expected.manga.filter { it.crossSourceGroupId != null && it.crossSourceGroupPrimary }
            .map { PrimaryShape(it.crossSourceGroupId!!, FixtureIdentity(it.source, it.url)) }
        val actualPrimaryRows = tasteRepository.getAllCrossSourceGroupPrimaries()
            .filter { it.groupId.startsWith(FIXTURE_GROUP_PREFIX) }
        val actualPrimaries = actualPrimaryRows
            .map { PrimaryShape(it.groupId, FixtureIdentity(it.source, it.url)) }
        recordSetDiff("cross_source_primary_relationship", expectedPrimaries, actualPrimaries, mismatches)

        // The index here reproduces the seeder's deterministic status choice only. Work ids below
        // are still derived through the installed source+URL -> database-id mapping.
        val expectedLocalWorks = expected.manga.withIndex().filter { it.value.hasLocalTracking }.map { (index, manga) ->
            LocalWorkShape(
                FixtureIdentity(manga.source, manga.url), manga.title, manga.title.lowercase(),
                LocalTrackedWorkStatus.entries[index % LocalTrackedWorkStatus.entries.size].name,
                lastChapterSource = null,
                lastChapterNumber = null,
                lastChapterUrl = null,
                lastChapterLabel = null,
                lastProgressAt = null,
                score = null,
                startDate = null,
                finishDate = null,
            )
        }
        val actualLocalWorks = localTrackerRepository.getAllWorksAsFlow().first()
            .filter { it.id.startsWith(FIXTURE_WORK_PREFIX) }
        val actualLocalWorkShapes = actualLocalWorks.map { work ->
            val mangaId = work.id.removePrefix(FIXTURE_WORK_PREFIX).toLongOrNull()
            val identity = mangaId?.let { fixtureIdsToIdentity[it] } ?: ORPHAN_LOCAL_WORK
            LocalWorkShape(
                identity,
                work.title,
                work.normalizedTitle,
                work.status.name,
                work.lastChapterSource,
                work.lastChapterNumber,
                work.lastChapterUrl,
                work.lastChapterLabel,
                work.lastProgressAt,
                work.score,
                work.startDate,
                work.finishDate,
            )
        }
        recordSetDiff("local_tracking_work", expectedLocalWorks, actualLocalWorkShapes, mismatches)
        val expectedLocalWorkIds = expected.manga.filter { it.hasLocalTracking }.mapNotNull { manga ->
            mangaIdByIdentity[FixtureIdentity(manga.source, manga.url)]?.let { "$FIXTURE_WORK_PREFIX$it" }
        }
        recordSetDiff("local_tracking_work_id_to_manga_id", expectedLocalWorkIds, actualLocalWorks.map { it.id }, mismatches)

        // The current seeder only inserts work rows; it does not seed source links. Keep the
        // expected baseline truthful and report this as a coverage gap, not as accepted coverage.
        val expectedLocalSources = emptyList<LocalSourceShape>()
        val actualLocalSources = actualLocalWorks.flatMap { work ->
            val mangaId = work.id.removePrefix(FIXTURE_WORK_PREFIX).toLongOrNull()
            val identity = mangaId?.let { fixtureIdsToIdentity[it] } ?: ORPHAN_LOCAL_WORK
            localTrackerRepository.getSources(work.id).map { source ->
                LocalSourceShape(identity, source.source, source.url, source.title)
            }
        }
        recordSetDiff("local_tracking_source_relationship", expectedLocalSources, actualLocalSources, mismatches)

        val expectedTags = expected.tagTastes.map { TagTasteShape(it.normalizedTag, it.displayName, it.preference) }
        val actualAllTags = tasteRepository.getAllTagTastes()
        val actualTags = actualAllTags
            .filter { tag -> expectedTags.any { it.normalizedTag == tag.normalizedTag } }
            .map { TagTasteShape(it.normalizedTag, it.displayName, it.preference) }
        recordSetDiff("tag_taste_identity_and_value", expectedTags, actualTags, mismatches)
        recordCount(
            "tag_taste_timestamp_missing",
            actualAllTags.filter { tag -> expectedTags.any { it.normalizedTag == tag.normalizedTag } }
                .count { it.createdAt <= 0L || it.updatedAt <= 0L },
            mismatches,
        )
        val expectedAliases = expected.tagAliases.map { TagAliasShape(it.normalizedAlias, it.alias, it.groupKey, it.displayName) }
        val actualAliases = tasteRepository.getAllTagAliases()
            .filter { alias -> expectedAliases.any { it.normalizedAlias == alias.normalizedAlias } }
            .map { TagAliasShape(it.normalizedAlias, it.alias, it.groupKey, it.displayName) }
        recordSetDiff("tag_alias_identity_and_value", expectedAliases, actualAliases, mismatches)

        val expectedCounts = JSONObject()
            .put("manga", expected.manga.size)
            .put("categories", expected.categoryNames.size)
            .put("chapters", expectedChapterShape.size)
            .put("history", expectedHistoryLinks.size)
            .put("rated", expectedTastes.size)
            .put("crossSourceLinks", expectedCrossLinks.size)
            .put("crossSourcePrimaries", expectedPrimaries.size)
            .put("localTrackingWorks", expectedLocalWorks.size)
            .put("localTrackingSources", expectedLocalSources.size)
            .put("tagTastes", expectedTags.size)
            .put("tagAliases", expectedAliases.size)
        val actualCounts = JSONObject()
            .put("manga", fixtureManga.size)
            .put("categories", actualCategoryRows.size)
            .put("chapters", actualChapterShape.size)
            .put("history", actualHistoryLinks.size)
            .put("rated", actualTastes.size)
            .put("crossSourceLinks", actualCrossLinks.size)
            .put("crossSourcePrimaries", actualPrimaries.size)
            .put("localTrackingWorks", actualLocalWorks.size)
            .put("localTrackingSources", actualLocalSources.size)
            .put("tagTastes", actualTags.size)
            .put("tagAliases", actualAliases.size)
        val timestampDisposition = JSONObject()
            .put("manga.dateAdded", "retained as aggregate range; generator has no seed-time value")
            .put("manga.lastUpdate,lastModifiedAt,favoriteModifiedAt", "retained as aggregate ranges; app lifecycle may update them")
            .put("chapter.dateFetch,dateUpload,lastModifiedAt", "retained as aggregate ranges; seed/update times are not generator fields")
            .put("history.readAt", "retained as aggregate range; seeder writes Date()")
            .put("taste.createdAt,updatedAt", "retained as aggregate ranges; seeder writes wall-clock values")
            .put("localWork.createdAt,updatedAt", "retained as aggregate ranges; seeder writes wall-clock values")
            .put("crossSourceLink.createdAt,updatedAt", "retained as aggregate ranges; seeder writes wall-clock values")
            .put("crossSourcePrimary.updatedAt", "retained as aggregate range; seeder writes wall-clock value")
        val timestampSummary = JSONObject()
            .put("manga.dateAdded", timestampRange(fixtureManga.map { it.dateAdded }))
            .put("manga.lastUpdate", timestampRange(fixtureManga.map { it.lastUpdate }))
            .put("manga.lastModifiedAt", timestampRange(fixtureManga.map { it.lastModifiedAt }))
            .put("manga.favoriteModifiedAt", timestampRange(fixtureManga.map { it.favoriteModifiedAt }))
            .put("chapter.dateFetch", timestampRange(actualChapters.map { it.dateFetch }))
            .put("chapter.dateUpload", timestampRange(actualChapters.map { it.dateUpload }))
            .put("chapter.lastModifiedAt", timestampRange(actualChapters.map { it.lastModifiedAt }))
            .put("history.readAt", timestampRange(actualHistories.map { it.readAt?.time }))
            .put("taste.createdAt", timestampRange(actualTastes.map { it.createdAt }))
            .put("taste.updatedAt", timestampRange(actualTastes.map { it.updatedAt }))
            .put("localWork.createdAt", timestampRange(actualLocalWorks.map { it.createdAt }))
            .put("localWork.updatedAt", timestampRange(actualLocalWorks.map { it.updatedAt }))
            .put("crossSourceLink.createdAt", timestampRange(actualCrossLinkRows.map { it.createdAt }))
            .put("crossSourceLink.updatedAt", timestampRange(actualCrossLinkRows.map { it.updatedAt }))
            .put("crossSourcePrimary.updatedAt", timestampRange(actualPrimaryRows.map { it.updatedAt }))
            .put("tagTaste.createdAt", timestampRange(actualAllTags.filter { tag -> expectedTags.any { it.normalizedTag == tag.normalizedTag } }.map { it.createdAt }))
            .put("tagTaste.updatedAt", timestampRange(actualAllTags.filter { tag -> expectedTags.any { it.normalizedTag == tag.normalizedTag } }.map { it.updatedAt }))
        recordCount("manga_dateAdded_missing", fixtureManga.size - mangaDateAddedPositive, mismatches)
        recordCount("chapter_dateFetch_missing", actualChapterShape.size - chapterDateFetchPositive, mismatches)
        recordCount("chapter_dateUpload_missing", actualChapterShape.size - chapterDateUploadPositive, mismatches)
        recordCount("local_work_timestamp_missing", actualLocalWorks.count { it.createdAt <= 0L || it.updatedAt <= 0L }, mismatches)
        recordCount(
            "cross_source_link_timestamp_missing",
            tasteRepository.getAllCrossSourceMangaLinks()
                .filter { it.groupId.startsWith(FIXTURE_GROUP_PREFIX) }
                .count { it.createdAt <= 0L || it.updatedAt <= 0L },
            mismatches,
        )
        recordCount(
            "cross_source_primary_timestamp_missing",
            tasteRepository.getAllCrossSourceGroupPrimaries()
                .filter { it.groupId.startsWith(FIXTURE_GROUP_PREFIX) }
                .count { it.updatedAt <= 0L },
            mismatches,
        )
        val receiptCardinalities = listOf(
            expected.manga.size to 1_000,
            expected.categoryNames.size to 14,
            expectedChapterTotal to 40_667,
            expectedHistoryTotal to 241,
            expectedTastes.size to 350,
            expectedCrossLinks.size to 150,
            expectedLocalWorks.size to 116,
        )
        recordCount("generator_seed_receipt_count_mismatch", receiptCardinalities.count { (actual, recorded) -> actual != recorded }, mismatches)

        val mismatchJson = JSONObject()
        mismatches.toSortedMap().forEach { (category, count) -> mismatchJson.put(category, count) }
        val mismatchTotal = mismatches.values.sum()
        val report = JSONObject()
            .put("schema", "kmk-performance-fixture-continuity-v1")
            .put("seedReceiptSha256", SEED_RECEIPT_SHA256)
            .put("targetPackage", target.packageName)
            .put("targetDebuggable", target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("readOnly", true)
            .put("status", if (mismatchTotal == 0) "PASS" else "MISMATCH")
            .put("expected", expectedCounts)
            .put("actual", actualCounts)
            .put("mismatchCategories", mismatchJson)
            .put("coverageGaps", listOf("LOCAL_SOURCE_RELATIONSHIP_NOT_SEEDED"))
            .put("timestampDisposition", timestampDisposition)
            .put("timestampSummary", timestampSummary)
        val serialized = report.toString()
        Log.i(TAG, "CONTINUITY_SUMMARY_JSON:$serialized")
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, serialized) },
        )
        assertTrue("Synthetic-only continuity summary: $serialized", mismatchTotal == 0)
    }

    private fun <T> recordSetDiff(name: String, expected: Collection<T>, actual: Collection<T>, mismatches: MutableMap<String, Int>) {
        val expectedSet = expected.toSet()
        val actualSet = actual.toSet()
        recordCount("${name}_missing", (expectedSet - actualSet).size, mismatches)
        recordCount("${name}_unexpected", (actualSet - expectedSet).size, mismatches)
        recordCount("${name}_duplicate", expected.size - expectedSet.size + actual.size - actualSet.size, mismatches)
    }

    private fun recordCount(name: String, count: Int, mismatches: MutableMap<String, Int>) {
        if (count > 0) mismatches[name] = (mismatches[name] ?: 0) + count
    }

    private fun timestampRange(values: Collection<Long?>): JSONObject {
        val present = values.filterNotNull()
        return JSONObject()
            .put("rows", values.size)
            .put("present", present.size)
            .put("null", values.size - present.size)
            .put("zero", present.count { it == 0L })
            .put("minimum", present.minOrNull())
            .put("maximum", present.maxOrNull())
    }

    private data class FixtureIdentity(val source: Long, val url: String)
    private data class MangaShape(
        val identity: FixtureIdentity,
        val title: String,
        val author: String?,
        val artist: String?,
        val description: String?,
        val genres: List<String>,
        val thumbnailUrl: String?,
        val favorite: Boolean,
        val initialized: Boolean,
    )
    private data class ChapterShape(
        val manga: FixtureIdentity,
        val url: String,
        val name: String,
        val number: Double,
        val sourceOrder: Long,
        val read: Boolean,
        val bookmark: Boolean,
        val lastPageRead: Long,
    )
    private data class CategoryLink(val manga: FixtureIdentity, val category: String)
    private data class CategoryShape(val name: String, val order: Long, val flags: Long, val hidden: Boolean)
    private data class HistoryLink(val manga: FixtureIdentity, val chapterUrl: String)
    private data class TasteShape(val manga: FixtureIdentity, val title: String, val rating: Int)
    private data class CrossLinkShape(val groupId: String, val manga: FixtureIdentity, val title: String)
    private data class PrimaryShape(val groupId: String, val manga: FixtureIdentity)
    private data class LocalWorkShape(
        val manga: FixtureIdentity,
        val title: String,
        val normalizedTitle: String,
        val status: String,
        val lastChapterSource: Long?,
        val lastChapterNumber: Double?,
        val lastChapterUrl: String?,
        val lastChapterLabel: String?,
        val lastProgressAt: Long?,
        val score: Double?,
        val startDate: Long?,
        val finishDate: Long?,
    )
    private data class LocalSourceShape(
        val workManga: FixtureIdentity,
        val source: Long,
        val url: String,
        val title: String,
    )
    private data class TagTasteShape(val normalizedTag: String, val displayName: String, val preference: Int)
    private data class TagAliasShape(val normalizedAlias: String, val alias: String, val groupKey: String, val displayName: String)

    private companion object {
        private const val TAG = "PerfFixtureContinuity"
        private const val FIXTURE_WORK_PREFIX = "perf-fixture-work-"
        private const val FIXTURE_GROUP_PREFIX = "perf-group-"
        private const val FIXTURE_HISTORY_DURATION_MS = 600_000L
        private const val UNKNOWN_CHAPTER_URL = "<unknown-fixture-chapter>"
        private const val SEED_RECEIPT_SHA256 = "4F2B20137EC49E67F518DFA31B5B31FC40949B2E0F53FA6B85C884C0FD66B371"
        private val ORPHAN_LOCAL_WORK = FixtureIdentity(0L, "<orphan-fixture-work>")
    }
}
