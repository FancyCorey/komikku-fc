package exh.perf

/** Pure fixture identity selectors; test and androidTest source sets reuse this file. */
object PerformanceFixtureSelectors {
    private val fixtureMangaUrl = Regex("^/fixture/manga/\\d+$")
    private val fixtureCategoryName = Regex("^Fixture Category \\d+$")

    fun isFixtureMangaUrl(value: String): Boolean = fixtureMangaUrl.matches(value)

    fun isFixtureCategoryName(value: String): Boolean = fixtureCategoryName.matches(value)
}
