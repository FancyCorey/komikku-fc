package tachiyomi.domain.manga.interactor

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository

class GetMangaTest {
    @Test
    fun `batch lookup deduplicates requested IDs`() = runTest {
        val repository = mockk<MangaRepository>()
        val manga = Manga.create().copy(id = 3L)
        coEvery { repository.getMangaByIds(listOf(3L, 1L)) } returns listOf(manga)

        assertEquals(listOf(manga), GetManga(repository).await(listOf(3L, 1L, 3L)))
        coVerify(exactly = 1) { repository.getMangaByIds(any()) }
        coVerify(exactly = 0) { repository.getMangaById(any()) }
    }

    @Test
    fun `empty lookup does not query the database`() = runTest {
        val repository = mockk<MangaRepository>()
        assertEquals(emptyList<Manga>(), GetManga(repository).await(emptyList()))
        coVerify(exactly = 0) { repository.getMangaByIds(any()) }
    }

    @Test
    fun `single lookup returns null when the repository fails`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaById(7L) } throws IllegalStateException("Database unavailable")

        assertEquals(null, GetManga(repository).await(7L))
        coVerify(exactly = 1) { repository.getMangaById(7L) }
    }

    @Test
    fun `single lookup propagates cancellation`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaById(7L) } throws CancellationException("Screen disposed")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { GetManga(repository).await(7L) }
        }
        coVerify(exactly = 1) { repository.getMangaById(7L) }
    }

    @Test
    fun `large lookup stays below SQLite parameter limits`() = runTest {
        val repository = mockk<MangaRepository>()
        val batches = mutableListOf<Collection<Long>>()
        coEvery { repository.getMangaByIds(capture(batches)) } returns emptyList()
        val ids = (1L..1801L).toList()

        GetManga(repository).await(ids)

        assertEquals(listOf(900, 900, 1), batches.map { it.size })
        assertEquals(ids, batches.flatMap { it })
    }

    @Test
    fun `failed batch uses individual reads and skips missing manga`() = runTest {
        val repository = mockk<MangaRepository>()
        val manga = Manga.create().copy(id = 2L)
        coEvery { repository.getMangaByIds(any()) } throws IllegalStateException("Batch failed")
        coEvery { repository.getMangaById(1L) } throws NoSuchElementException("Manga removed")
        coEvery { repository.getMangaById(2L) } returns manga

        assertEquals(listOf(manga), GetManga(repository).await(listOf(1L, 2L, 2L)))
        coVerify(exactly = 1) { repository.getMangaById(1L) }
        coVerify(exactly = 1) { repository.getMangaById(2L) }
    }

    @Test
    fun `one failed chunk does not repeat successful chunks`() = runTest {
        val repository = mockk<MangaRepository>()
        val first = Manga.create().copy(id = 1L)
        val last = Manga.create().copy(id = 901L)
        coEvery { repository.getMangaByIds((1L..900L).toList()) } returns listOf(first)
        coEvery { repository.getMangaByIds(listOf(901L)) } throws IllegalStateException("Batch failed")
        coEvery { repository.getMangaById(901L) } returns last

        assertEquals(listOf(first, last), GetManga(repository).await((1L..901L).toList()))
        coVerify(exactly = 1) { repository.getMangaByIds((1L..900L).toList()) }
        coVerify(exactly = 1) { repository.getMangaById(any()) }
    }

    @Test
    fun `cancellation during fallback stops remaining reads`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaByIds(any()) } throws IllegalStateException("Batch failed")
        coEvery { repository.getMangaById(1L) } throws CancellationException("Stopped")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { GetManga(repository).await(listOf(1L, 2L)) }
        }
        coVerify(exactly = 0) { repository.getMangaById(2L) }
    }

    @Test
    fun `cancelled batch does not start individual fallback`() = runTest {
        val repository = mockk<MangaRepository>()
        coEvery { repository.getMangaByIds(any()) } throws CancellationException("Stopped")

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { GetManga(repository).await(listOf(1L)) }
        }
        coVerify(exactly = 0) { repository.getMangaById(any()) }
    }
}
