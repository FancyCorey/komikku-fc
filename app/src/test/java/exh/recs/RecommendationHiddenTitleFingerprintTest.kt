package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class RecommendationHiddenTitleFingerprintTest {
    @Test
    fun `replacing one hidden title changes the digest even when the count stays the same`() {
        val first = setOf(SeenMangaKey(1L, "/manga/one"))
        val replacement = setOf(SeenMangaKey(1L, "/manga/two"))

        assertNotEquals(
            RecommendationHiddenTitleFingerprint.digest(first),
            RecommendationHiddenTitleFingerprint.digest(replacement),
        )
    }

    @Test
    fun `digest is independent of key iteration order`() {
        val first = setOf(SeenMangaKey(2L, "/b"), SeenMangaKey(1L, "/a"))
        val sameKeys = setOf(SeenMangaKey(1L, "/a"), SeenMangaKey(2L, "/b"))

        assertEquals(
            RecommendationHiddenTitleFingerprint.digest(first),
            RecommendationHiddenTitleFingerprint.digest(sameKeys),
        )
    }
}
