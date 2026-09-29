package exh.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@RunWith(AndroidJUnit4::class)
class MangaBatchLookupDeviceTest {

    @Test
    fun loadsSeededMangaAcrossSqliteBatchBoundaryWithoutFallback() = runBlocking {
        val repository = Injekt.get<MangaRepository>()
        val before = repository.getAll()
        val fixtureUrls = (0 until 1000).map { "/fixture/manga/$it" }.toSet()
        val fixture = before.filter { it.url in fixtureUrls }
        assertEquals("Prepare the realistic-1k fixture before running this read-only test", 1000, fixture.size)
        assertEquals("Every fixture index must be present exactly once", fixtureUrls, fixture.map { it.url }.toSet())
        assertTrue(
            "Malformed-title fixture rows must be included",
            fixture.any {
                !it.title.startsWith("Fixture Manga ") && !it.title.startsWith("Fixture Shared Work ")
            },
        )

        val batches = mutableListOf<Int>()
        var singleLookups = 0
        val observedRepository = object : MangaRepository by repository {
            override suspend fun getMangaByIds(ids: Collection<Long>): List<Manga> {
                batches += ids.size
                return repository.getMangaByIds(ids)
            }

            override suspend fun getMangaById(id: Long): Manga {
                singleLookups++
                return repository.getMangaById(id)
            }
        }
        val ids = fixture.map { it.id }.reversed()
        val result = GetManga(observedRepository).await(ids + ids.take(3) + Long.MAX_VALUE)

        assertEquals(listOf(900, 101), batches)
        assertEquals("A failed SQL batch must not be hidden by individual lookups", 0, singleLookups)
        assertEquals(fixture.associateBy { it.id }, result.associateBy { it.id })
        assertEquals(1000, result.size)
        assertTrue(result.none { it.id == Long.MAX_VALUE })
        assertEquals("The lookup must not change the stored manga", before, repository.getAll())
    }
}
