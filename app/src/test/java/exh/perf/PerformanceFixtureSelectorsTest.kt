package exh.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceFixtureSelectorsTest {

    @Test
    fun fixtureMangaSelectorAcceptsNumericFixturePathsOnly() {
        assertTrue(PerformanceFixtureSelectors.isFixtureMangaUrl("/fixture/manga/0"))
        assertTrue(PerformanceFixtureSelectors.isFixtureMangaUrl("/fixture/manga/123"))
        assertFalse(PerformanceFixtureSelectors.isFixtureMangaUrl("/fixture/manga/x"))
        assertFalse(PerformanceFixtureSelectors.isFixtureMangaUrl("/fixture/manga/12/ch/1"))
        assertFalse(PerformanceFixtureSelectors.isFixtureMangaUrl("/other/manga/12"))
    }

    @Test
    fun fixtureCategorySelectorAcceptsNumberedFixtureNamesOnly() {
        assertTrue(PerformanceFixtureSelectors.isFixtureCategoryName("Fixture Category 1"))
        assertTrue(PerformanceFixtureSelectors.isFixtureCategoryName("Fixture Category 14"))
        assertFalse(PerformanceFixtureSelectors.isFixtureCategoryName("Fixture Category x"))
        assertFalse(PerformanceFixtureSelectors.isFixtureCategoryName("Fixture Category 1 extra"))
        assertFalse(PerformanceFixtureSelectors.isFixtureCategoryName("Personal Category 1"))
    }
}
