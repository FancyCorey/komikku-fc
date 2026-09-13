package exh.recs.settings

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.manga.track.AutomaticLocalTrackingStatusPolicy
import exh.util.FakePreferenceStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus

class AutomaticLocalTrackingStatusPolicyTest {
    @Test
    fun `all quality of life preferences default on when unset`() {
        val preferences = SourcePreferences(FakePreferenceStore())

        assertEquals(true, preferences.confirmedTrackedVersionRatingPropagationEnabled().get())
        assertEquals(true, preferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().get())
        assertEquals(true, preferences.automaticRatedGroupPrimaryEnabled().get())
        assertEquals(true, preferences.chapterCompletionRatingOtherVersionsPromptEnabled().get())
        assertEquals(true, preferences.automaticLocalTrackingStatusInferenceEnabled().get())
    }

    @Test
    fun `stored opt outs survive the default migration contract`() {
        val store = FakePreferenceStore()
        val preferences = SourcePreferences(store)
        preferences.confirmedTrackedVersionRatingPropagationEnabled().set(false)
        preferences.confirmedTrackedVersionLocalTrackingPropagationEnabled().set(false)
        preferences.automaticRatedGroupPrimaryEnabled().set(false)
        preferences.chapterCompletionRatingOtherVersionsPromptEnabled().set(false)
        preferences.automaticLocalTrackingStatusInferenceEnabled().set(false)

        val restored = SourcePreferences(store)
        assertEquals(false, restored.confirmedTrackedVersionRatingPropagationEnabled().get())
        assertEquals(false, restored.confirmedTrackedVersionLocalTrackingPropagationEnabled().get())
        assertEquals(false, restored.automaticRatedGroupPrimaryEnabled().get())
        assertEquals(false, restored.chapterCompletionRatingOtherVersionsPromptEnabled().get())
        assertEquals(false, restored.automaticLocalTrackingStatusInferenceEnabled().get())
    }

    @Test
    fun `no progress is planned and fractional progress is reading`() {
        assertEquals(
            LocalTrackedWorkStatus.PLANNED,
            AutomaticLocalTrackingStatusPolicy.resolve(true, null, false, false, false),
        )
        assertEquals(
            LocalTrackedWorkStatus.READING,
            AutomaticLocalTrackingStatusPolicy.resolve(true, null, true, false, false),
        )
    }

    @Test
    fun `completed metadata and final read chapter infer completed`() {
        assertEquals(
            LocalTrackedWorkStatus.COMPLETED,
            AutomaticLocalTrackingStatusPolicy.resolve(true, null, true, true, true),
        )
        assertEquals(
            LocalTrackedWorkStatus.READING,
            AutomaticLocalTrackingStatusPolicy.resolve(true, null, true, true, false),
        )
    }

    @Test
    fun `explicit hold and dropped statuses remain user owned`() {
        assertEquals(
            LocalTrackedWorkStatus.ON_HOLD,
            AutomaticLocalTrackingStatusPolicy.resolve(true, LocalTrackedWorkStatus.ON_HOLD, true, true, true),
        )
        assertEquals(
            LocalTrackedWorkStatus.DROPPED,
            AutomaticLocalTrackingStatusPolicy.resolve(true, LocalTrackedWorkStatus.DROPPED, true, true, true),
        )
        assertEquals(
            LocalTrackedWorkStatus.COMPLETED,
            AutomaticLocalTrackingStatusPolicy.resolve(true, LocalTrackedWorkStatus.COMPLETED, true, false, false),
        )
    }

    @Test
    fun `disabled inference preserves an existing status and uses planned for new work`() {
        assertEquals(
            LocalTrackedWorkStatus.READING,
            AutomaticLocalTrackingStatusPolicy.resolve(false, LocalTrackedWorkStatus.READING, false, false, false),
        )
        assertEquals(
            LocalTrackedWorkStatus.PLANNED,
            AutomaticLocalTrackingStatusPolicy.resolve(false, null, true, true, true),
        )
    }
}
