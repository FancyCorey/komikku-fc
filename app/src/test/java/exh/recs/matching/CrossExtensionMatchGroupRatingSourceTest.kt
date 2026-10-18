package exh.recs.matching

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CrossExtensionMatchGroupRatingSourceTest {
    @Test
    fun `Track Other Versions exposes the effective rating independently of candidate selection`() {
        val model = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()
        val screen = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreen.kt").readText()
        val control = File("src/main/java/exh/recs/matching/ConfirmedGroupRatingControl.kt").readText()
        val ratingAction = model.substringAfter("fun setGroupRating(rating: MangaRating?)")
            .substringBefore("private fun loadGroupRating")

        assertTrue(model.contains("if (mode is CrossExtensionMatchMode.LocalTracking) loadGroupRating(manga)"))
        assertTrue(ratingAction.contains("confirmedGroupTasteRatingActions.setGroupRating(origin, rating)"))
        assertTrue(!ratingAction.contains("selectedKeys"), "group-level changes must not depend on highlighted search rows")
        assertTrue(screen.contains("if (mode is CrossExtensionMatchMode.LocalTracking)"))
        assertTrue(screen.contains("state.groupRating == null -> stringResource(KMR.strings.local_tracking_group_rating_not_rated)"))
        assertTrue(screen.contains("MangaRating.LOVE to stringResource") && screen.contains("MangaRating.LIKE to stringResource"))
        assertTrue(screen.contains("MangaRating.DISLIKE to stringResource") && screen.contains("MangaRating.NOT_INTERESTED to stringResource"))
        assertTrue(screen.contains("onSetRating = screenModel::setGroupRating"))
        assertTrue(control.contains("enabled = !loading && !updating"))
        assertTrue(control.contains("onSetRating(null)"), "Not rated must invoke the group clear action")
        assertTrue(control.contains("onSetRating(rating)"), "each existing rating must invoke a group-wide set")
    }

    @Test
    fun `confirmed membership rating sync runs after selected local tracking and link persistence`() {
        val source = File("src/main/java/exh/recs/matching/CrossExtensionMatchScreenModel.kt").readText()
        val linksWrite = source.indexOf("upsertCrossSourceMangaLinks.await(links)")
        val trackingPropagation = source.indexOf("confirmedGroupLocalTrackingPropagator.propagateIfAnyTracked(", linksWrite)
        val ratingPropagation = source.indexOf("confirmedGroupTasteRatingActions.propagateToConfirmedMember(origin, target)", trackingPropagation)

        assertTrue(linksWrite >= 0)
        assertTrue(trackingPropagation > linksWrite, "local-tracking mappings must be written before membership rating sync")
        assertTrue(ratingPropagation > trackingPropagation, "rating sync must follow persisted links and tracked membership")
        assertTrue(source.substring(trackingPropagation, ratingPropagation).contains("mode is CrossExtensionMatchMode.LocalTracking"))
    }
}
