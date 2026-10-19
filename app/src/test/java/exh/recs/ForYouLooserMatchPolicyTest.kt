package exh.recs

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.taste.model.TasteProfile

// KMK --> v0.8.24: looser matches relax only the taste match, never the user's own blocks or dislikes
class ForYouLooserMatchPolicyTest {

    private val profile = TasteProfile.EMPTY.copy(
        learnedTagWeights = mapOf("action" to 1.0, "harem" to -1.0),
        blockedGroups = setOf("gore"),
    )

    private fun manga(id: Long, vararg genres: String) =
        Manga.create().copy(id = id, url = "/m$id", ogTitle = "M$id", ogGenre = genres.toList(), source = 1L)

    @Test
    fun `untagged and unrelated manga become looser matches, marked as such`() {
        val picks = ForYouLooserMatchPolicy.pick(listOf(manga(1), manga(2, "romance")), profile, emptyMap())
        assertEquals(setOf(1L, 2L), picks.map { it.manga.id }.toSet())
        assertTrue(picks.all { it.looser && it.matchedGroups.isEmpty() })
    }

    @Test
    fun `blocked tags and disliked tags are never shown`() {
        val picks = ForYouLooserMatchPolicy.pick(
            listOf(manga(1, "gore"), manga(2, "harem"), manga(3, "romance")),
            profile,
            emptyMap(),
        )
        assertEquals(listOf(3L), picks.map { it.manga.id })
    }

    @Test
    fun `at most a few per source, each manga once`() {
        val pool = (1L..10L).map { manga(it) } + manga(1)
        val picks = ForYouLooserMatchPolicy.pick(pool, profile, emptyMap())
        assertEquals(ForYouLooserMatchPolicy.MAX_LOOSER_MATCHES, picks.size)
        assertEquals(picks.size, picks.map { it.manga.id }.distinct().size)
    }

    @Test
    fun `the pool is only reachable inside its own source search`() = runTest {
        val pool = LooserCandidatePool()
        withContext(pool) {
            kotlinx.coroutines.currentCoroutineContext()[LooserCandidatePool]?.add(listOf(manga(1)))
        }
        assertEquals(listOf(1L), pool.snapshot().map { it.id })
        assertEquals(null, kotlinx.coroutines.currentCoroutineContext()[LooserCandidatePool])
    }
}
// KMK <--
