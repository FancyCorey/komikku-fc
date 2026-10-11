package exh.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// KMK F2-04 (KFC-V0.8.21-FIX2-CORRECTIVE-RECHECK-AND-RELEASE-PROGRAM) -->
/**
 * Source-guard proof that the C4 performance fixture generator/seeder never ships in a release
 * artifact. `PerformanceFixtureSpec`/`Dataset`/`Generator`/`Seeder` moved from `app/src/main`
 * (compiled into EVERY build type) to `app/src/debug`. The debug and separate `benchmarkSeed`
 * fixture-carrier variants compile those sources; the release-like measured `benchmark` variant
 * and public release variants must not. Host/device seeder tests therefore remain available
 * without shipping the classes in a measured or released artifact.
 *
 * A live `:app:compileReleaseKotlin` run is the ultimate confirmation (record its result in the
 * dated receipt), but that is a one-off manual check; this test is the durable, always-run
 * regression guard against the fixture files silently drifting back into `src/main` later.
 */
class PerformanceFixtureProductionExclusionTest {

    private val fixtureFileNames = setOf(
        "PerformanceFixtureSpec.kt",
        "PerformanceFixtureDataset.kt",
        "PerformanceFixtureGenerator.kt",
        "PerformanceFixtureSeeder.kt",
    )

    @Test
    fun `no performance fixture file exists under app-src-main`() {
        val mainPerfDir = repoRelative("app/src/main/java/exh/perf")
        assertFalse(
            Files.isDirectory(mainPerfDir),
            "app/src/main/java/exh/perf must not exist -- fixture classes belong in app/src/debug, never a production source set",
        )
    }

    @Test
    fun `every performance fixture file exists under app-src-debug`() {
        val debugPerfDir = repoRelative("app/src/debug/java/exh/perf")
        assertTrue(Files.isDirectory(debugPerfDir), "app/src/debug/java/exh/perf must exist")

        fixtureFileNames.forEach { fileName ->
            assertTrue(
                Files.isRegularFile(debugPerfDir.resolve(fileName)),
                "$fileName must exist under app/src/debug/java/exh/perf",
            )
        }
    }

    @Test
    fun `no other src set (main, release-derived, or otherwise) carries a performance fixture file`() {
        // Defensive breadth check: even if someone later adds a NEW build-type source set (e.g.
        // app/src/foss/java/...), a fixture file placed there would also leak into that release
        // variant. Test-only support is admitted only with the exact source-set wiring below.
        val appSrcDir = repoRelative("app/src")
        assertTrue(Files.isDirectory(appSrcDir), "app/src must exist")

        val buildScript = String(Files.readAllBytes(repoRelative("app/build.gradle.kts")))
        val supportMapping = Regex("""getByName\(\s*"([^"]+)"\s*\)\s*\.java\.srcDir\(\s*"src/testSupport/java"\s*\)""")
        val supportSourceSets = supportMapping.findAll(buildScript).map { it.groupValues[1] }.toList()
        assertEquals(listOf("test", "androidTest"), supportSourceSets)
        assertEquals(2, Regex("src/testSupport/java").findAll(buildScript).count())

        val allowedSourceSets = setOf("debug", "test", "androidTest", "testFixtures", "test-shared", "testSupport")
        Files.newDirectoryStream(appSrcDir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { it.fileName.toString() !in allowedSourceSets }
                .forEach { sourceSetDir ->
                    val perfDir = sourceSetDir.resolve("java/exh/perf")
                    assertFalse(
                        Files.isDirectory(perfDir) && Files.list(perfDir).use { it.findAny().isPresent },
                        "${sourceSetDir.fileName}/java/exh/perf must not contain fixture files -- " +
                            "that source set merges into a release-derived build type",
                    )
                }
        }
    }

    @Test
    fun `measured benchmark source set excludes debug fixture Java and retains release shrinking`() {
        val buildScript = String(Files.readAllBytes(repoRelative("app/build.gradle.kts")))
        val measuredBlock = buildScript.substringAfter("create(\"benchmark\") {", missingDelimiterValue = "")
            .substringBefore("create(\"benchmarkSeed\") {")

        assertTrue(measuredBlock.contains("initWith(release)"), "Measured benchmark must inherit release settings")
        assertFalse(measuredBlock.contains("isMinifyEnabled = false"), "Measured benchmark must not disable R8")
        assertFalse(measuredBlock.contains("isShrinkResources = false"), "Measured benchmark must not disable resource shrinking")

        val debugJavaMapping = Regex("""getByName\(\s*"benchmark"\s*\)\s*\.java\.srcDirs\(\s*"src/debug/java"\s*\)""")
        val seedJavaMapping = Regex("""getByName\(\s*"benchmarkSeed"\s*\)\s*\.java\.srcDirs\(\s*"src/debug/java"\s*\)""")
        assertFalse(debugJavaMapping.containsMatchIn(buildScript), "Measured benchmark must not compile fixture Java")
        assertTrue(seedJavaMapping.containsMatchIn(buildScript), "Only the seed carrier needs fixture Java")
    }

    @Test
    fun `external verifier ABI rules are confined to the measured benchmark`() {
        val buildScript = String(Files.readAllBytes(repoRelative("app/build.gradle.kts")))
        val measuredBlock = buildScript.substringAfter("create(\"benchmark\") {")
            .substringBefore("create(\"benchmarkSeed\") {")
        val rulesFile = "proguard-benchmark-verifier.pro"

        assertEquals(1, Regex(Regex.escape(rulesFile)).findAll(buildScript).count())
        assertTrue(measuredBlock.contains("proguardFile(\"$rulesFile\")"))
        assertFalse(String(Files.readAllBytes(repoRelative("app/proguard-rules.pro"))).contains(rulesFile))

        // Exact compiled verifier reference contract: 23 domain types, 102 members, and
        // three runner tracing methods. This guards wiring/whitelist drift, not runtime ABI.
        val rules = Files.readAllLines(repoRelative("app/$rulesFile"))
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")
        assertFalse(rules.contains("*"), "No package or member wildcard is admitted")
        assertFalse(rules.contains("-dont"), "Benchmark must retain release shrinking and optimization")
        val digest = MessageDigest.getInstance("SHA-256").digest(rules.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("03cbf522e910ca460ce662c87ed0f21bb9e38d8a115aaf221fd4b3e2a0a5b76a", digest)
    }

    @Test
    fun `For You benchmark input control is test-only and guarded before its preference write`() {
        val relativePath = "app/src/androidTest/java/exh/perf/ForYouBenchmarkInputControlTest.kt"
        val sourcePath = repoRelative(relativePath)
        assertTrue(Files.isRegularFile(sourcePath), "For You input control belongs in androidTest")
        assertFalse(
            Files.exists(repoRelative("app/src/main/java/exh/perf/ForYouBenchmarkInputControlTest.kt")),
            "For You input control must not ship in the application",
        )
        val source = String(Files.readAllBytes(sourcePath))
        val validationCall = source.indexOf("val requestedMode = validateControlRequest(")
        val preferenceLookup = source.indexOf("Injekt.get<SourcePreferences>().forYouFixtureMode()")
        val firstWrite = source.indexOf("preference.commit(requestedMode)")
        assertTrue(
            validationCall >= 0 && validationCall < preferenceLookup,
            "Request validation must precede the app-owned preference lookup",
        )
        assertTrue(validationCall < firstWrite, "Request validation must precede every preference write")
        assertEquals(2, Regex("preference\\.commit\\(requestedMode\\)").findAll(source).count())
        val validator = source.substringAfter("private fun validateControlRequest(")
            .substringBefore("private companion object")
        val guardChecks = listOf(
            "check(targetPackage == TARGET_PACKAGE)",
            "check(benchmarkFixtureEnabled)",
            "check(measuredTargetNonDebuggable)",
            "arguments.getString(ACKNOWLEDGEMENT_ARGUMENT) == REQUIRED_ACKNOWLEDGEMENT",
            "check(requestedMode == REQUESTED_MODE)",
        )
        var lastGuardPosition = -1
        guardChecks.forEach { guard ->
            val guardPosition = validator.indexOf(guard)
            assertTrue(guardPosition > lastGuardPosition, "Missing or reordered fail-closed guard: $guard")
            lastGuardPosition = guardPosition
        }
        assertTrue(lastGuardPosition < validator.indexOf("return requestedMode"))
        assertTrue(
            source.contains("REQUIRED_ACKNOWLEDGEMENT = \"I_ACKNOWLEDGE_BENCHMARK_ONLY_PREFERENCE_WRITE\""),
        )
        assertTrue(source.contains("ForYouDebugFixture.resolveMode("))
        assertTrue(source.contains("visibleContentVerified\", false"))
        assertTrue(source.contains("import android.content.pm.ApplicationInfo"))
        assertFalse(source.contains("import android.app.ApplicationInfo"))
        assertTrue(source.contains("measuredTargetNonDebuggable"))
        assertFalse(source.contains("targetDebuggable"))
        assertFalse(source.contains("PerformanceFixtureSeeder"))
        assertFalse(source.contains("MangaRepository"))
    }

    private fun repoRelative(relativePath: String): Path {
        val direct = Path.of(relativePath)
        val fromParent = Path.of("..").resolve(relativePath)
        return listOf(direct, fromParent).map(Path::toAbsolutePath).firstOrNull { Files.exists(it) }
            ?: direct.toAbsolutePath()
    }
}
// KMK <--
