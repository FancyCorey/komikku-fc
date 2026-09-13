package exh.recs.loved

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [RatedGroupPrimaryResolver].
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*.RatedGroupPrimaryResolverTest"
 */
class RatedGroupPrimaryResolverTest {

    @Test
    fun `stored primary wins when present among current group members`() {
        val result = RatedGroupPrimaryResolver.resolve(
            grouperPrimaryKey = "1|/a",
            memberKeys = listOf("1|/a", "2|/b"),
            storedPrimary = RatedMangaKey(2, "/b"),
        )
        assertEquals("2|/b", result)
    }

    @Test
    fun `falls back to grouper primary when stored primary is missing from current members`() {
        // Stored primary points at a source/url that isn't part of this group's currently loaded
        // members (e.g. its source was uninstalled) — must not crash, must fall back cleanly.
        val result = RatedGroupPrimaryResolver.resolve(
            grouperPrimaryKey = "1|/a",
            memberKeys = listOf("1|/a", "2|/b"),
            storedPrimary = RatedMangaKey(99, "/uninstalled"),
        )
        assertEquals("1|/a", result)
    }

    @Test
    fun `falls back to grouper primary when there is no stored primary`() {
        val result = RatedGroupPrimaryResolver.resolve(
            grouperPrimaryKey = "1|/a",
            memberKeys = listOf("1|/a", "2|/b"),
            storedPrimary = null,
        )
        assertEquals("1|/a", result)
    }

    @Test
    fun `automatic primary prefers the most-read confirmed version`() {
        val result = RatedGroupPrimaryResolver.resolve(
            grouperPrimaryKey = "1|/a",
            memberKeys = listOf("1|/a", "2|/b"),
            storedPrimary = null,
            automaticSelectionEnabled = true,
            candidates = listOf(
                RatedGroupPrimaryResolver.Candidate("1|/a", readChapterCount = 2, firstRatedAt = 10),
                RatedGroupPrimaryResolver.Candidate("2|/b", readChapterCount = 8, firstRatedAt = 20),
            ),
        )

        assertEquals("2|/b", result)
    }

    @Test
    fun `automatic primary falls back to the first-rated version and never overrides a manual choice`() {
        val candidates = listOf(
            RatedGroupPrimaryResolver.Candidate("1|/a", readChapterCount = 0, firstRatedAt = 20),
            RatedGroupPrimaryResolver.Candidate("2|/b", readChapterCount = 0, firstRatedAt = 10),
        )
        assertEquals(
            "2|/b",
            RatedGroupPrimaryResolver.resolve("1|/a", listOf("1|/a", "2|/b"), null, true, candidates),
        )
        assertEquals(
            "1|/a",
            RatedGroupPrimaryResolver.resolve(
                "1|/a",
                listOf("1|/a", "2|/b"),
                RatedMangaKey(1, "/a"),
                true,
                candidates,
            ),
        )
    }
}
