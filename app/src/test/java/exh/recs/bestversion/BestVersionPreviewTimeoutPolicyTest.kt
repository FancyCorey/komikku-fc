package exh.recs.bestversion

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BestVersionPreviewTimeoutPolicyTest {
    @Test
    fun `timeout warning is shown while preview images remain unresolved`() {
        assertTrue(
            BestVersionPreviewTimeoutPolicy.shouldShowWarning(
                timedOut = true,
                pendingPageCount = 2,
                failedPageCount = 0,
                sourceFailedPageCount = 0,
            ),
        )
    }

    @Test
    fun `late successful images clear the timeout warning`() {
        assertFalse(
            BestVersionPreviewTimeoutPolicy.shouldShowWarning(
                timedOut = true,
                pendingPageCount = 0,
                failedPageCount = 0,
                sourceFailedPageCount = 0,
            ),
        )
    }

    @Test
    fun `a page failure uses its specific error instead of the timeout warning`() {
        assertFalse(
            BestVersionPreviewTimeoutPolicy.shouldShowWarning(
                timedOut = true,
                pendingPageCount = 2,
                failedPageCount = 1,
                sourceFailedPageCount = 0,
            ),
        )
    }
}
