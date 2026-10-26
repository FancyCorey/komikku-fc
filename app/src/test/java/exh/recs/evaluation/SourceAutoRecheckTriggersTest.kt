package exh.recs.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: besides missing and out-of-date results, the automatic check also re-checks sources whose result is current when
 * the taste changed a lot or the installed extension was updated since it was evaluated. It still never installs anything.
 */
class SourceAutoRecheckTriggersTest {

    @Test
    fun `a large change in the number of rated manga counts as a change in taste`() {
        assertFalse(SourceAutoRecheckPolicy.tasteChanged(ratedNow = 150, ratedAtLastCheck = 100))
        assertTrue(SourceAutoRecheckPolicy.tasteChanged(ratedNow = 200, ratedAtLastCheck = 100))
        assertTrue(SourceAutoRecheckPolicy.tasteChanged(ratedNow = 50, ratedAtLastCheck = 150), "removing many ratings is a change too")
        assertFalse(SourceAutoRecheckPolicy.tasteChanged(ratedNow = 500, ratedAtLastCheck = 0), "the first run only records a baseline")
    }

    @Test
    fun `the threshold matches the manual reassessment prompt`() {
        assertEquals(100, SourceAutoRecheckPolicy.TASTE_CHANGE_RATED_THRESHOLD)
        val screen = File("src/main/java/exh/recs/evaluation/SourceEvaluationScreen.kt").readText()
        assertTrue(screen.contains("private const val REASSESSMENT_THRESHOLD = 100"))
    }

    @Test
    fun `current results are left alone unless the taste changed or the extension was updated`() {
        val missing = listOf("m")
        val outdated = listOf("o")
        val checked = listOf("c1", "c2")

        assertEquals(listOf("m", "o"), SourceAutoRecheckPolicy.targets(missing, outdated, checked, false) { false })
        assertEquals(listOf("m", "o", "c1", "c2"), SourceAutoRecheckPolicy.targets(missing, outdated, checked, true) { false })
        assertEquals(listOf("m", "o", "c2"), SourceAutoRecheckPolicy.targets(missing, outdated, checked, false) { it == "c2" })
        assertEquals(emptyList<String>(), SourceAutoRecheckPolicy.targets(emptyList<String>(), emptyList(), checked, false) { false })
    }

    @Test
    fun `an updated extension is one that is installed with a higher version than the one evaluated`() {
        val installed = listOf(Triple("pkg.a", "sigA", 12L), Triple("pkg.b", "sigB", 5L))

        assertTrue(SourceAutoRecheckPolicy.extensionUpdated(10L, "pkg.a", "sigA", installed))
        assertFalse(SourceAutoRecheckPolicy.extensionUpdated(12L, "pkg.a", "sigA", installed), "same version")
        assertFalse(SourceAutoRecheckPolicy.extensionUpdated(null, "pkg.a", "sigA", installed), "no recorded version")
        assertFalse(SourceAutoRecheckPolicy.extensionUpdated(1L, "pkg.a", "other", installed), "a different signature is a different extension")
        assertFalse(SourceAutoRecheckPolicy.extensionUpdated(1L, "pkg.c", "sigC", installed), "not installed")
    }

    @Test
    fun `the worker uses the new triggers and records the rated count, and still cannot install`() {
        val worker = File("src/main/java/exh/recs/evaluation/SourceAutoRecheck.kt").readText()

        assertTrue(worker.contains("SourceAutoRecheckPolicy.tasteChanged("))
        assertTrue(worker.contains("checked = queue.checkedPromising"))
        assertTrue(worker.contains("preferences.recommendationAutoRecheckRatedCount().set(ratedNow)"))
        assertTrue(worker.contains("SourceEvaluationInstallerPolicy.InstallerMode.CURRENT"))
        assertFalse(worker.contains("InstallerMode.PRIVATE"))
        assertFalse(worker.contains("InstallerMode.SHIZUKU"))
    }
}
