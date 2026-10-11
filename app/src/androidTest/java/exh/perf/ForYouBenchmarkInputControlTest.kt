package exh.perf

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.BuildConfig
import exh.recs.ForYouDebugFixture
import exh.recs.ForYouDebugFixtureMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Selects the deterministic For You mode on the isolated measured benchmark target, outside the
 * timed macrobenchmark. The runner must supply both exact arguments:
 * `kmk.dod05.forYouFixtureMode=top_picks` and
 * `kmk.dod05.ackForYouPreferenceWrite=I_ACKNOWLEDGE_BENCHMARK_ONLY_PREFERENCE_WRITE`.
 *
 * This method changes only SourcePreferences.forYouFixtureMode. It does not seed or inspect the
 * database, and it does not claim visible For You content; the root-owned route preflight verifies
 * the content sentinel separately.
 */
@RunWith(AndroidJUnit4::class)
class ForYouBenchmarkInputControlTest {

    @Test
    fun configureForYouTopPicksBenchmarkInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val measuredTargetNonDebuggable = targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0
        val requestedMode = validateControlRequest(
            targetPackage = targetContext.packageName,
            benchmarkFixtureEnabled = BuildConfig.KMK_BENCHMARK_FIXTURE,
            measuredTargetNonDebuggable = measuredTargetNonDebuggable,
            arguments = arguments,
        )

        val preference = Injekt.get<SourcePreferences>().forYouFixtureMode()
        assertTrue("For You benchmark mode must persist before measurement", preference.commit(requestedMode))
        val storedMode = preference.get()
        assertEquals(REQUESTED_MODE, storedMode)

        // Repeating the same app-owned setting must leave the effective mode unchanged.
        assertTrue("Repeated For You benchmark mode write must remain successful", preference.commit(requestedMode))
        val repeatedStoredMode = preference.get()
        assertEquals(REQUESTED_MODE, repeatedStoredMode)
        val effectiveMode = ForYouDebugFixture.resolveMode(
            fixtureBuildEnabled = BuildConfig.KMK_BENCHMARK_FIXTURE,
            preferenceValue = repeatedStoredMode,
        )
        assertEquals(ForYouDebugFixtureMode.TOP_PICKS, effectiveMode)

        val report = JSONObject()
            .put("schema", REPORT_SCHEMA)
            .put("status", "PASS")
            .put("targetPackage", TARGET_PACKAGE)
            .put("benchmarkFixtureEnabled", BuildConfig.KMK_BENCHMARK_FIXTURE)
            .put("measuredTargetNonDebuggable", measuredTargetNonDebuggable)
            .put("requestedFixtureMode", requestedMode)
            .put("storedFixtureMode", storedMode)
            .put("effectiveFixtureMode", effectiveMode.prefValue)
            .put("durablePreferenceWriteVerified", true)
            .put("idempotentReadbackVerified", storedMode == repeatedStoredMode)
            .put("visibleContentVerified", false)
            .put("assertionScope", "preference-only; outside macrobenchmark timing")
        val serialized = report.toString()
        Log.i(TAG, "FOR_YOU_INPUT_CONTROL_JSON:$serialized")
        instrumentation.sendStatus(
            0,
            Bundle().apply { putString("kmk.dod05.forYouInputControl", serialized) },
        )
    }

    private fun validateControlRequest(
        targetPackage: String,
        benchmarkFixtureEnabled: Boolean,
        measuredTargetNonDebuggable: Boolean,
        arguments: Bundle,
    ): String {
        check(targetPackage == TARGET_PACKAGE) { "For You input control requires the isolated benchmark package" }
        check(benchmarkFixtureEnabled) { "For You benchmark fixture flag is disabled" }
        check(measuredTargetNonDebuggable) { "For You preference control requires the non-debuggable measured target" }
        check(arguments.getString(ACKNOWLEDGEMENT_ARGUMENT) == REQUIRED_ACKNOWLEDGEMENT) {
            "Explicit benchmark-only preference-write acknowledgement is required"
        }
        val requestedMode = arguments.getString(FIXTURE_MODE_ARGUMENT)
        check(requestedMode == REQUESTED_MODE) { "Unsupported For You benchmark fixture mode" }
        return requestedMode
    }

    private companion object {
        const val TARGET_PACKAGE = "app.komikku.benchmark"
        const val FIXTURE_MODE_ARGUMENT = "kmk.dod05.forYouFixtureMode"
        const val ACKNOWLEDGEMENT_ARGUMENT = "kmk.dod05.ackForYouPreferenceWrite"
        const val REQUIRED_ACKNOWLEDGEMENT = "I_ACKNOWLEDGE_BENCHMARK_ONLY_PREFERENCE_WRITE"
        const val REQUESTED_MODE = "top_picks"
        const val REPORT_SCHEMA = "kmk-dod05-for-you-input-control-v1"
        const val TAG = "ForYouInputControl"
    }
}
