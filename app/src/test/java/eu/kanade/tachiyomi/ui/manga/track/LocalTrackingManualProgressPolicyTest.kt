package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus

class LocalTrackingManualProgressPolicyTest {
    @Test
    fun `zero or cleared progress is planned and positive fractional progress is reading`() {
        assertEquals(
            LocalTrackedWorkStatus.PLANNED,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.READING, 0.0, false),
        )
        assertEquals(
            LocalTrackedWorkStatus.PLANNED,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.READING, null, false),
        )
        assertEquals(
            LocalTrackedWorkStatus.READING,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.PLANNED, 0.1, false),
        )
    }

    @Test
    fun `completed metadata at the final chapter becomes completed`() {
        assertEquals(
            LocalTrackedWorkStatus.COMPLETED,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.READING, 8.0, true),
        )
    }

    @Test
    fun `hold and dropped remain manual overrides`() {
        assertEquals(
            LocalTrackedWorkStatus.ON_HOLD,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.ON_HOLD, 8.0, true),
        )
        assertEquals(
            LocalTrackedWorkStatus.DROPPED,
            LocalTrackingManualProgressPolicy.resolveStatus(LocalTrackedWorkStatus.DROPPED, 8.0, true),
        )
    }

    @Test
    fun `fallback label preserves semantic integer and fractional chapter numbers`() {
        assertEquals("Chapter 8", LocalTrackingManualProgressPolicy.chapterLabel(8.0, null))
        assertEquals("Chapter 8.5", LocalTrackingManualProgressPolicy.chapterLabel(8.5, null))
        assertEquals("Episode 8", LocalTrackingManualProgressPolicy.chapterLabel(8.0, "Episode 8"))
    }
}
