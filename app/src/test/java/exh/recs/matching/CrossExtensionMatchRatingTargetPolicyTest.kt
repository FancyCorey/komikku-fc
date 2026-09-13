package exh.recs.matching

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrossExtensionMatchRatingTargetPolicyTest {
    @Test
    fun `other-version rating excludes already rated candidates`() {
        val selected = setOf(
            MangaIdentityKey(1L, "/origin"),
            MangaIdentityKey(2L, "/unrated"),
            MangaIdentityKey(3L, "/rated"),
        )

        assertEquals(
            setOf(MangaIdentityKey(1L, "/origin"), MangaIdentityKey(2L, "/unrated")),
            CrossExtensionMatchRatingTargetPolicy.unratedOnly(
                selected = selected,
                rated = setOf(MangaIdentityKey(3L, "/rated")),
            ),
        )
    }

    @Test
    fun `unrated target filtering is idempotent`() {
        val selected = setOf(MangaIdentityKey(2L, "/unrated"))
        val rated = setOf(MangaIdentityKey(3L, "/rated"))

        assertEquals(
            CrossExtensionMatchRatingTargetPolicy.unratedOnly(
                selected = selected,
                rated = rated,
            ),
            CrossExtensionMatchRatingTargetPolicy.unratedOnly(
                selected = CrossExtensionMatchRatingTargetPolicy.unratedOnly(selected, rated),
                rated = rated,
            ),
        )
    }
}
