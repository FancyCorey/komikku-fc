package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderAheadPolicy.Offer
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.atomic.AtomicInteger

// KMK v0.8.25 -->
class AlternateSourceReaderAheadCheckerTest {

    private val request = AlternateSourceReaderCandidateRequest(
        primaryRoute = readerRoute(AlternateSourceReaderRouteRole.PRIMARY),
        precedingPrimaryChapterId = 11L,
        followingPrimaryChapterId = null,
    )

    private fun candidate(
        source: Long,
        origin: AlternateSourceReaderCandidateOrigin = AlternateSourceReaderCandidateOrigin.CONFIRMED_PAIR,
        needsConfirmation: Boolean = false,
    ) = AlternateSourceReaderMangaCandidate(
        manga = Manga.create().copy(id = source, source = source, url = "/m/$source"),
        sourceLabel = "Source $source",
        origin = origin,
        requiresPairConfirmation = needsConfirmation,
    )

    private fun chapters(vararg numbers: Float) = AlternateSourceReaderChapterDiscovery.Available(
        numbers.map { AlternateSourceReaderChapterCandidate(url = "/c/$it", name = "Chapter $it", chapterNumber = it) },
    )

    private fun checker(
        candidates: List<AlternateSourceReaderMangaCandidate>,
        answers: Map<Long, AlternateSourceReaderChapterDiscovery>,
        calls: AtomicInteger = AtomicInteger(),
        now: () -> Long = { 0L },
        timeout: Long = 1_000L,
        onChapters: suspend () -> Unit = {},
    ) = AlternateSourceReaderAheadChecker(
        discover = { AlternateSourceReaderCandidateDiscovery(candidates, 0) },
        chapters = {
            calls.incrementAndGet()
            onChapters()
            answers[it.manga.source] ?: AlternateSourceReaderChapterDiscovery.Failed
        },
        now = now,
        perVersionTimeoutMillis = timeout,
    )

    @Test
    fun `one confirmed version ahead gives one offer and every answering version's latest chapter`() = runTest {
        val report = checker(
            listOf(candidate(2), candidate(3)),
            mapOf(2L to chapters(33f, 34f, 35f), 3L to chapters(30f, 34f)),
        ).check(request, 34f)

        val offer = report.offer as Offer.One
        assertEquals("2:/m/2", offer.ahead.key)
        assertEquals(35f, offer.ahead.nextChapter)
        assertEquals(mapOf("2:/m/2" to 35f, "3:/m/3" to 34f), report.latestByKey)
    }

    @Test
    fun `a guess from a search or an unconfirmed pair is never asked`() = runTest {
        val calls = AtomicInteger()
        val report = checker(
            listOf(
                candidate(2, AlternateSourceReaderCandidateOrigin.LIVE_SEARCH),
                candidate(3, AlternateSourceReaderCandidateOrigin.LEGACY_GROUP, needsConfirmation = true),
                candidate(4, AlternateSourceReaderCandidateOrigin.CONFIRMED_PAIR, needsConfirmation = true),
            ),
            mapOf(2L to chapters(50f), 3L to chapters(50f), 4L to chapters(50f)),
            calls,
        ).check(request, 34f)

        assertEquals(0, calls.get())
        assertEquals(Offer.None, report.offer)
    }

    @Test
    fun `a version that fails or times out is left out and invents nothing`() = runTest {
        val report = checker(
            listOf(candidate(2), candidate(3), candidate(4)),
            mapOf(
                2L to AlternateSourceReaderChapterDiscovery.Failed,
                3L to AlternateSourceReaderChapterDiscovery.SourceUnavailable,
                4L to chapters(35f),
            ),
        ).check(request, 34f)

        assertEquals(setOf("4:/m/4"), report.latestByKey.keys)
        assertTrue(report.offer is Offer.One)
    }

    @Test
    fun `only the first six versions are asked`() = runTest {
        val calls = AtomicInteger()
        val seven = (2L..8L).map { candidate(it) }
        checker(seven, seven.associate { it.manga.source to chapters(35f) }, calls).check(request, 34f)
        assertEquals(6, calls.get())
    }

    @Test
    fun `the same finished chapter is answered once for half an hour and again after`() = runTest {
        val calls = AtomicInteger()
        var clock = 0L
        val c = checker(listOf(candidate(2)), mapOf(2L to chapters(35f)), calls, now = { clock })

        c.check(request, 34f)
        clock = AlternateSourceReaderAheadPolicy.CACHE_MILLIS - 1
        c.check(request, 34f)
        assertEquals(1, calls.get())

        clock = AlternateSourceReaderAheadPolicy.CACHE_MILLIS + 1
        c.check(request, 34f)
        assertEquals(2, calls.get())
    }

    @Test
    fun `nothing linked gives no offer and asks nobody`() = runTest {
        val calls = AtomicInteger()
        val report = checker(emptyList(), emptyMap(), calls).check(request, 34f)
        assertEquals(0, calls.get())
        assertEquals(AlternateSourceReaderAheadChecker.Report.Empty.offer, report.offer)
    }

    @Test
    fun `a version that never answers is dropped after the timeout`() = runTest {
        val report = checker(
            listOf(candidate(2)),
            mapOf(2L to chapters(35f)),
            timeout = 50L,
            onChapters = { delay(10_000L) },
        ).check(request, 34f)
        assertEquals(Offer.None, report.offer)
        assertTrue(report.latestByKey.isEmpty())
    }
}
// KMK <--
