package exh.recs

import eu.kanade.tachiyomi.network.HttpException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// KMK --> v0.8.24: suggested fixes for failing For You sources
class ForYouSourceFailurePolicyTest {

    // CloudflareInterceptor's exception is private; the policy matches it by class name
    private class CloudflareBypassException : Exception()

    private fun stats(runs: Int, errors: Int) = SourceFitStats(
        sourceId = 1, runCount = runs, shownCount = runs - errors, noMatchCount = 0, filteredOutCount = 0,
        errorCount = errors, hiddenByDuplicateCount = 0, totalVisibleCandidates = 0, lastSuccessAt = 0, lastErrorAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `the cause is read from the error`() {
        val cause = ForYouSourceFailurePolicy::cause
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_CHECK, cause(CloudflareBypassException()))
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_CHECK, cause(HttpException(403)))
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_CHECK, cause(HttpException(503)))
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_PROBLEM, cause(HttpException(500)))
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_PROBLEM, cause(HttpException(429)))
        assertEquals(ForYouSourceFailurePolicy.Cause.UNKNOWN, cause(HttpException(404)))
        assertEquals(ForYouSourceFailurePolicy.Cause.CONNECTION, cause(UnknownHostException()))
        assertEquals(ForYouSourceFailurePolicy.Cause.CONNECTION, cause(SocketTimeoutException()))
        assertEquals(ForYouSourceFailurePolicy.Cause.CONNECTION, cause(IOException("reset")))
        assertEquals(ForYouSourceFailurePolicy.Cause.EXTENSION, cause(NoClassDefFoundError()))
        assertEquals(ForYouSourceFailurePolicy.Cause.UNKNOWN, cause(IllegalStateException()))
        assertEquals(ForYouSourceFailurePolicy.Cause.SITE_PROBLEM, cause(ForYouSourceTimeoutException(30)))
    }

    @Test
    fun `open site is suggested only for a browser check`() {
        assertTrue(ForYouSourceFailurePolicy.advise(HttpException(403), null).suggestsOpenSite)
        assertFalse(ForYouSourceFailurePolicy.advise(UnknownHostException(), null).suggestsOpenSite)
    }

    @Test
    fun `a source keeps failing only when most of its runs failed, using the Often errors rule`() {
        val greedScans = ForYouSourceFailurePolicy.advise(HttpException(403), stats(runs = 163, errors = 138))
        assertTrue(greedScans.keepsFailing)
        assertEquals(138, greedScans.failedRuns)
        assertEquals(163, greedScans.totalRuns)

        assertFalse(ForYouSourceFailurePolicy.advise(HttpException(403), stats(runs = 152, errors = 43)).keepsFailing)
        assertFalse(ForYouSourceFailurePolicy.advise(HttpException(403), stats(runs = 2, errors = 2)).keepsFailing, "too few runs")
        assertFalse(ForYouSourceFailurePolicy.advise(HttpException(403), null).keepsFailing)
    }

    @Test
    fun `the failed row offers fixes that fit and never removes anything itself`() {
        val tab = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText().replace("\r\n", "\n")
        val ui = tab.substringAfter("fun ForYouSourceFailureActions(").substringBefore("private fun FailureNote(")
        assertTrue(ui.contains("if (advice.suggestsOpenSite && siteUrl != null)"))
        assertTrue(ui.contains("if (advice.keepsFailing && check == null)"), "Check source only for sources that keep failing")
        assertTrue(ui.contains("(extensionObsolete || extensionHasUpdate || check == ForYouSourceFailurePolicy.Check.BROKEN)"))
        assertFalse(ui.contains("uninstall", ignoreCase = true), "removing an extension stays on its own screen")
        assertTrue(tab.contains("navigator.push(eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen(pkgName))"))
    }
}
// KMK <--
