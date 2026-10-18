package exh.recs

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK --> v0.8.24 recent-reading profile: registration and caller wiring that a unit test of the interactor cannot
// see. A construction of the screen model without the interactor must keep working (tests), while the production tab
// must pass it, and the dependency injection must be able to build it.
class RecentReadingWiringSourceTest {

    private fun source(path: String) = File(path).readText().replace("\r\n", "\n")

    @Test
    fun `the interactor and the repository are registered for injection with matching arity`() {
        val kmkModule = source("src/main/java/eu/kanade/domain/KMKDomainModule.kt")
        val domainModule = source("src/main/java/eu/kanade/domain/DomainModule.kt")
        assertTrue(kmkModule.contains("addFactory { exh.recs.GetRecentReadingTagWeights(get(), get(), get(), get(), get()) }"))
        assertTrue(domainModule.contains("addSingletonFactory<RecentReadingRepository> { RecentReadingRepositoryImpl(get()) }"))
        // five required constructor parameters (the resolver and clock have defaults)
        val required = GetRecentReadingTagWeights::class.java.constructors
            .maxOf { it.parameterCount }
        assertTrue(required >= 5, "constructor takes the five injected collaborators")
    }

    @Test
    fun `the production tab passes the interactor and the screen model defaults it to null`() {
        val tab = source("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt")
        val model = source("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt")
        assertTrue(tab.contains("getRecentReadingTagWeights = Injekt.get(),"))
        assertTrue(tab.contains("isNetworkConstrained = { ForYouDataSavingPolicy.isConstrained(context) },"))
        assertTrue(model.contains("private val getRecentReadingTagWeights: GetRecentReadingTagWeights? = null,"))
    }

    @Test
    fun `recent weights are merged before the empty check, skipped for fixtures and part of the cache fingerprint`() {
        val model = source("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt")
        val merge = model.indexOf("baseProfile.copy(recentTagWeights = recentReading.await(baseProfile, aliasMap))")
        val emptyCheck = model.indexOf("if (profile.isEmpty()) {", merge)
        assertTrue(merge in 0 until emptyCheck, "merged before the profile-empty check")
        assertTrue(model.contains("fixtureMode == ForYouDebugFixtureMode.OFF"))
        assertTrue(model.contains("profile.recentTagWeights.entries.sortedBy { it.key }.forEach { update(\"recent:\${it.key}=\${it.value}\") }"))
    }

    @Test
    fun `recent weights also choose the tags that sources are searched with`() {
        val model = source("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt")
        val body = model.substringAfter("private fun topSearchTags(profile: TasteProfile): List<String> {")
            .substringBefore("// KMK --> v0.7.43: Not Interested mild-negative signal")
        assertTrue(body.contains("profile.recentTagWeights.filterValues { it > 0 }"))
    }

    @Test
    fun `the three settings are written through the policy and shown in Taste and Filters`() {
        val settingsModel = source("src/main/java/exh/recs/settings/RecommendationsSettingsScreenModel.kt")
        val screen = source("src/main/java/exh/recs/settings/RecommendationTasteTagsSettingsScreen.kt")
        assertTrue(settingsModel.contains("RecentReadingPolicy.resolveWindowDays(value)"))
        assertTrue(settingsModel.contains("RecentReadingPolicy.resolveMinChapters(value)"))
        assertTrue(screen.contains("onValueChange = screenModel::setRecentReadingWindowDays"))
        assertTrue(screen.contains("onEnabledChange = screenModel::setRecentReadingEnabled"))
        assertTrue(screen.contains("onValueChange = screenModel::setRecentReadingMinChapters"))
    }
}
// KMK <--
