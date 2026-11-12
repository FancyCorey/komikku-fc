package exh.recs

import eu.kanade.domain.source.service.SourcePreferences
import exh.util.FakePreferenceStore
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tachiyomi.domain.manga.model.Manga

@OptIn(ExperimentalCoroutinesApi::class)
class RecommendationChapterCountCheckerTest {
    private fun manga(id: Long) = Manga.create().copy(id = id, source = id, url = "/$id")

    @Test
    fun `known short titles are removed on a reopened single or group recommendations screen without fetching again`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        preferences.recommendationCheckedChapterCounts().set("2:4:900")
        val fetched = mutableListOf<Long>()
        val checker = RecommendationChapterCountChecker(mockk(), preferences, now = { 1_000L }, loadCount = {
            fetched += it.id
            30L
        })
        val rows = listOf(listOf(manga(1), manga(2), manga(3)))
        var visible = rows.flatten()
        checker.checkShown(rows, mapOf(1L to 3L), 20) { counts ->
            visible = visible.filter { (counts[it.id] ?: Long.MAX_VALUE) >= 10 }
        }
        assertEquals(listOf(3L), visible.map { it.id })
        assertEquals(listOf(3L), fetched)
    }

    @Test
    fun `fast counts reach the screen while another source is still loading`() = runTest {
        val slow = CompletableDeferred<Long>()
        val updates = mutableListOf<Map<Long, Long>>()
        val checker = RecommendationChapterCountChecker(mockk(), SourcePreferences(FakePreferenceStore()), loadCount = {
            if (it.id == 1L) slow.await() else 2L
        })
        val job = launch { checker.checkShown(listOf(listOf(manga(1), manga(2))), emptyMap(), 20, updates::add) }
        runCurrent()
        assertFalse(job.isCompleted)
        assertTrue(updates.any { it[2L] == 2L })
        slow.complete(40L)
        job.join()
        assertTrue(updates.any { it[1L] == 40L })
    }

    @Test
    fun `checking is bounded and skips metadata-only titles and duplicate IDs`() = runTest {
        val fetched = mutableListOf<Long>()
        val checker = RecommendationChapterCountChecker(mockk(), SourcePreferences(FakePreferenceStore()), loadCount = {
            fetched += it.id
            10L
        })
        checker.checkShown(listOf(listOf(manga(-1), manga(0), manga(1), manga(1), manga(2))), emptyMap(), 1) {}
        assertEquals(listOf(1L), fetched)
        checker.checkShown(listOf(listOf(manga(3))), emptyMap(), 0) {}
        assertEquals(listOf(1L), fetched)
    }

    @Test
    fun `leaving the screen cancels in-flight checks without publishing a made-up count`() = runTest {
        var cancelled = false
        val updates = mutableListOf<Map<Long, Long>>()
        val checker = RecommendationChapterCountChecker(mockk(), SourcePreferences(FakePreferenceStore()), loadCount = {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        })
        val job = launch { checker.checkShown(listOf(listOf(manga(1))), emptyMap(), 20, updates::add) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(updates.all { it.isEmpty() })
    }

    @Test
    fun `leaving preserves completed counts without caching unfinished checks`() = runTest {
        val preferences = SourcePreferences(FakePreferenceStore())
        val checker = RecommendationChapterCountChecker(mockk(), preferences, now = { 1_000L }, loadCount = {
            if (it.id == 1L) 3L else awaitCancellation()
        })
        val rows = listOf(listOf(manga(1), manga(2)))
        val job = launch { checker.checkShown(rows, emptyMap(), 20) {} }
        runCurrent()
        job.cancelAndJoin()
        val fetched = mutableListOf<Long>()
        val reopened = RecommendationChapterCountChecker(mockk(), preferences, now = { 1_100L }, loadCount = {
            fetched += it.id
            20L
        })
        val updates = mutableListOf<Map<Long, Long>>()
        reopened.checkShown(rows, emptyMap(), 20, updates::add)
        assertEquals(mapOf(1L to 3L), updates.first())
        assertEquals(listOf(2L), fetched)
    }

    @Test
    fun `fatal VM errors are not converted to an unknown chapter count`() {
        assertThrows<OutOfMemoryError> {
            runTest {
                val checker = RecommendationChapterCountChecker(mockk(), SourcePreferences(FakePreferenceStore()), loadCount = {
                    throw OutOfMemoryError("test")
                })
                checker.check(listOf(manga(1)))
            }
        }
    }
}
