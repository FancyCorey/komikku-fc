import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import mihon.buildlogic.Config
import mihon.buildlogic.getBuildTime
import mihon.buildlogic.getCommitCount
import mihon.buildlogic.getGitSha
import java.time.Instant

private val defaultAndroidVersionName = "1.14.1"
private val defaultAndroidVersionCode = 97

plugins {
    id("mihon.android.application")
    id("mihon.android.application.compose")
    id("com.github.zellius.shortcut-helper")
    kotlin("plugin.parcelize")
    kotlin("plugin.serialization")
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.roborazzi)
    id("com.github.ben-manes.versions")
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

shortcutHelper.setFilePath("./shortcuts.xml")

aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    license {
        additionalLicenses.add("mpl_2_0")
    }
}

// Dependency resolution feeds the generated catalog, but the plugin marks that data internal.
// Track the selected variant's compile/runtime coordinates so dependency updates invalidate it.
tasks.withType<AboutLibrariesTask>()
    .matching { it.name.startsWith("prepareLibraryDefinitions") }
    .configureEach {
        inputs.property(
            "resolvedVariantDependencyCoordinates",
            project.providers.provider {
                val selectedVariant = variant.get()
                project.configurations
                    .filter { configuration ->
                        configuration.name == "${selectedVariant}CompileClasspath" ||
                            configuration.name == "${selectedVariant}RuntimeClasspath"
                    }
                    .flatMap { configuration ->
                        configuration.incoming.resolutionResult.allComponents.map { component ->
                            "${configuration.name}:${component.id.displayName}"
                        }
                    }
                    .distinct()
                    .sorted()
            },
        )
    }

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun configuredGoogleDriveValue(propertyName: String, fallback: String): String =
    buildConfigString(providers.gradleProperty(propertyName).orNull ?: fallback)

val supportedAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val validationAbi = providers.gradleProperty("kmk.validation.abi").orNull
require(validationAbi == null || validationAbi in supportedAbis) {
    "kmk.validation.abi must be one of ${supportedAbis.joinToString()}"
}

// Instrumentation owns its expected-data implementation: the measured APK intentionally
// excludes fixture classes. Reuse these exact sources, never the mutating seeder.
val continuityFixtureTestSourceDir = layout.buildDirectory.dir("generated/continuityFixtureTestSources")
val continuityFixtureTestSources = tasks.register<Sync>("prepareContinuityFixtureTestSources") {
    from("src/debug/java") {
        include(
            "exh/perf/PerformanceFixtureGenerator.kt",
            "exh/perf/PerformanceFixtureSpec.kt",
            "exh/perf/PerformanceFixtureDataset.kt",
        )
    }
    into(continuityFixtureTestSourceDir)
}

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.komikku"

        versionCode = providers.gradleProperty("kmk.releaseVersionCode").orNull?.toInt()
            ?: defaultAndroidVersionCode // Feature versions are tracked in KmkRecsReleaseNotes.
        // A compatibility bridge may need the Android version expected by an older updater.
        // Ordinary builds keep the established application version unless the release workflow
        // supplies an explicit bridge version.
        versionName = providers.gradleProperty("kmk.releaseVersionName").orNull ?: defaultAndroidVersionName

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        // Keep ordinary configuration and host validation reproducible. Candidate builders may
        // provide one stable ISO-8601 timestamp for the whole assembly invocation.
        val buildTime = providers.gradleProperty("kmk.buildTime").orNull
            ?: getBuildTime(useLastCommitTime = true)
        require(runCatching { Instant.parse(buildTime) }.isSuccess) {
            "kmk.buildTime must be an ISO-8601 UTC instant, for example 2026-09-07T12:00:00Z"
        }
        buildConfigField("String", "BUILD_TIME", buildConfigString(buildTime))
        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")
        buildConfigField("boolean", "GOOGLE_DRIVE_SYNC_ENABLED", "${Config.enableGoogleDriveSync}")
        buildConfigField("String", "GOOGLE_DRIVE_CLIENT_SECRETS_ASSET", buildConfigString("client_secrets.json"))
        buildConfigField("String", "GOOGLE_DRIVE_CLIENT_ID", buildConfigString(""))
        // KMK v0.8.24: OAuth client ID of Komikku's own MangaBaka application; empty hides MangaBaka in Settings
        buildConfigField("String", "MANGABAKA_CLIENT_ID", configuredGoogleDriveValue("kmk.mangabaka.clientId", ""))
        buildConfigField("String", "SOURCES_TO_TRY_FIXTURE_SIGNER_SHA256", buildConfigString(""))
        buildConfigField("String", "BEST_VERSION_FIXTURE_PROFILE", buildConfigString(""))
        buildConfigField("String", "ALTERNATE_SOURCE_READER_FIXTURE_PROFILE", buildConfigString(""))
        buildConfigField("boolean", "KMK_BENCHMARK_FIXTURE", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // KMK FC DOD-02: fixture instrumentation targets the separate seed build, never the
        // release-like measured benchmark APK. Ordinary development keeps debug androidTest.
        val updaterInstrumentation = providers.gradleProperty("kmk.updater.instrumentation").orNull == "true"
        val benchmarkFixture = providers.gradleProperty("kmk.benchmark.fixture").isPresent
        check(!(updaterInstrumentation && benchmarkFixture)) {
            "-Pkmk.updater.instrumentation=true cannot be combined with -Pkmk.benchmark.fixture."
        }
        testBuildType = when {
            benchmarkFixture -> "benchmarkSeed"
            updaterInstrumentation -> "kmkPublicTest"
            else -> "debug"
        }
    }

    buildTypes {
        val debug by getting {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-${getCommitCount()}"
            isPseudoLocalesEnabled = true

            buildConfigField("boolean", "UPDATER_ENABLED", "false")
            buildConfigField("boolean", "GOOGLE_DRIVE_SYNC_ENABLED", "false")

            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_SECRETS_ASSET",
                configuredGoogleDriveValue("kmk.googleDrive.debug.asset", "client_secrets.json"),
            )
            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_ID",
                configuredGoogleDriveValue("kmk.googleDrive.debug.clientId", ""),
            )
            buildConfigField(
                "String",
                "SOURCES_TO_TRY_FIXTURE_SIGNER_SHA256",
                buildConfigString(providers.gradleProperty("kmk.sourcesToTryFixture.signerSha256").orNull ?: ""),
            )
            buildConfigField(
                "String",
                "BEST_VERSION_FIXTURE_PROFILE",
                buildConfigString(providers.gradleProperty("kmk.bestVersionFixture.profile").orNull ?: ""),
            )
            buildConfigField(
                "String",
                "ALTERNATE_SOURCE_READER_FIXTURE_PROFILE",
                buildConfigString(providers.gradleProperty("kmk.alternateSourceReaderFixture.profile").orNull ?: ""),
            )
        }
        val release by getting {
            isMinifyEnabled = Config.enableCodeShrink
            isShrinkResources = Config.enableCodeShrink

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")

            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_SECRETS_ASSET",
                configuredGoogleDriveValue("kmk.googleDrive.release.asset", "client_secrets.json"),
            )
            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_ID",
                configuredGoogleDriveValue("kmk.googleDrive.release.clientId", ""),
            )
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("releaseTest") {
            initWith(release)

            applicationIdSuffix = ".rt"
            isMinifyEnabled = false
            isShrinkResources = false

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("preview") {
            initWith(release)

            applicationIdSuffix = ".beta"

            versionNameSuffix = debug.versionNameSuffix
            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmark") {
            initWith(release)

            // Separate instrumentation callers are invisible to the target APK's R8 graph.
            proguardFile("proguard-benchmark-verifier.pro")

            isDebuggable = false
            isProfileable = true
            // Retain release code/resource shrinking for measured-target parity. Fixture
            // implementation belongs only to benchmarkSeed, installed before this target.
            versionNameSuffix = "${debug.versionNameSuffix}-benchmark"
            applicationIdSuffix = ".benchmark"
            buildConfigField("boolean", "KMK_BENCHMARK_FIXTURE", "true")

            signingConfig = debug.signingConfig

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmarkSeed") {
            initWith(debug)

            // Same package, version code and signer permit a data-preserving in-place update
            // from this fixture carrier to the profileable benchmark target.
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "${debug.versionNameSuffix}-benchmark-seed"
            buildConfigField("boolean", "KMK_BENCHMARK_FIXTURE", "true")

            matchingFallbacks.addAll(listOf(debug.name, release.name))
        }
        // KMK --> public test build line: separate applicationId (app.komikku.kmk) for community sharing
        create("kmkPublicTest") {
            initWith(release)

            applicationIdSuffix = ".kmk"
            isMinifyEnabled = false
            isShrinkResources = false

            signingConfig = debug.signingConfig

            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_SECRETS_ASSET",
                configuredGoogleDriveValue("kmk.googleDrive.publicTest.asset", "client_secrets.json"),
            )
            buildConfigField(
                "String",
                "GOOGLE_DRIVE_CLIENT_ID",
                configuredGoogleDriveValue("kmk.googleDrive.publicTest.clientId", ""),
            )

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        // KMK <--
    }

    sourceSets {
        getByName("preview").res.srcDirs("src/beta/res")
        getByName("benchmark").res.srcDirs("src/debug/res")
        // The fixture implementation is compiled into the seed carrier, not the measured APK.
        getByName("benchmarkSeed").res.srcDirs("src/debug/res")
        getByName("benchmarkSeed").java.srcDirs("src/debug/java")
        getByName("test").java.srcDir("src/testSupport/java")
        getByName("androidTest").java.srcDir("src/testSupport/java")
        if (providers.gradleProperty("kmk.benchmark.fixture").isPresent) {
            getByName("androidTest").java.srcDir(continuityFixtureTestSourceDir.get().asFile)
        }
        // These integration tests require owners compiled only into debug/benchmarkSeed.
        // Keep them in ordinary debug and benchmark-fixture runs; omit only from the
        // release-derived public-test updater instrumentation compile.
        if (providers.gradleProperty("kmk.updater.instrumentation").orNull == "true") {
            tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
                if (name == "compileKmkPublicTestAndroidTestKotlin") {
                    exclude(
                        listOf(
                            "eu/kanade/tachiyomi/extension/util/PrivateExtensionRecoveryDeviceTest.kt",
                            "exh/perf/PerformanceFixtureContinuityVerifierTest.kt",
                            "exh/perf/PerformanceFixtureDeviceSeedTest.kt",
                            "exh/perf/RatingPropagationDeviceFixtureSeedTest.kt",
                        ),
                    )
                }
            }
        }
        // KMK --> R-026: expose migration .sqm files as test classpath resources
        getByName("test").resources.srcDirs("../data/src/main/sqldelight/tachiyomi/migrations")
        // KMK <--
    }

    splits {
        abi {
            isEnable = true
            isUniversalApk = validationAbi == null
            reset()
            include(*(validationAbi?.let(::listOf) ?: supportedAbis).toTypedArray())
        }
    }

    packaging {
        jniLibs {
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true

        // Disable some unused things
        renderScript = false
        shaders = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // KMK v0.8.24: the full unit suite outgrew Gradle's default 512 MB test heap; the test JVM died silently
                // ("Could not complete execution for Gradle Test Executor") part-way through. Test runs only.
                it.maxHeapSize = "2g"
            }
        }
        managedDevices {
            localDevices {
                create("kmkAtdApi30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    systemImageSource = "google_apis"
                    testedAbi = "x86"
                }
            }
        }
    }
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

configurations.configureEach {
    if (name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "org.conscrypt", module = "conscrypt-android")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    implementation(projects.i18n)
    // KMK -->
    implementation(projects.i18nKmk)
    // KMK <--
    // SY -->
    implementation(projects.i18nSy)
    // SY <--
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(compose.activity)
    implementation(compose.foundation)
    implementation(compose.material3.core)
    implementation(compose.material.icons)
    implementation(compose.animation)
    implementation(compose.animation.graphics)
    debugImplementation(compose.ui.tooling)
    implementation(compose.ui.tooling.preview)
    implementation(compose.ui.util)

    implementation(androidx.interpolator)

    implementation(androidx.paging.runtime)
    implementation(androidx.paging.compose)

    implementation(androidx.sqlite.bundled)

    // SY -->
    implementation(sylibs.sqlcipher)
    // SY <--

    implementation(kotlinx.reflect)
    implementation(kotlinx.immutables)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(kotlinx.bundles.coroutines)

    // AndroidX libraries
    implementation(androidx.annotation)
    implementation(androidx.appcompat)
    implementation(androidx.biometricktx)
    implementation(androidx.constraintlayout)
    implementation(androidx.corektx)
    implementation(androidx.splashscreen)
    implementation(androidx.recyclerview)
    implementation(androidx.viewpager)
    implementation(androidx.profileinstaller)

    implementation(androidx.bundles.lifecycle)

    // Job scheduling
    implementation(androidx.workmanager)

    // RxJava
    implementation(libs.rxjava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt.android) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(kotlinx.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.disklrucache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.preferencektx)

    // Dependency injection
    implementation(libs.injekt)

    // Image loading
    implementation(platform(libs.coil.bom))
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingscaleimageview) {
        exclude(module = "image-decoder")
    }
    implementation(projects.core.imageDecoder)

    // UI libraries
    implementation(libs.material)
    // KMK: keep the pinned JitPack build. Upstream's Maven Central 5.1.0 is compiled for Java 21 (class file 65), while this
    // fork's builds and release workflow run on Java 17.
    implementation(files("libs/flexible-adapter-c8013533.aar"))
    implementation(libs.photoview)
    implementation(libs.directionalviewpager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.richeditor.compose)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.compose.materialmotion)
    implementation(libs.swipe)
    implementation(libs.compose.webview)
    implementation(libs.compose.grid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // KMK -->
    implementation(libs.palette.ktx)
    implementation(libs.haze)
    implementation(compose.colorpicker)
    implementation(projects.flagkit)
    // KMK OCR -->
    implementation(libs.mlkit.text.recognition)
    // KMK OCR <--
    // KMK <--

    // Logging
    implementation(libs.timber)
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // Tests
    testImplementation(libs.bundles.test)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.compose)
    testImplementation(androidx.test.ext)
    testImplementation(compose.ui.test.junit4)
    testImplementation(androidx.workmanager.testing)
    // KMK --> R-026: in-memory SQLite driver for migration tests
    testImplementation(libs.sqldelight.sqlite.driver)
    // KMK <--

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakcanary.android)
    implementation(libs.leakcanary.plumber)

    testImplementation(kotlinx.coroutines.test)

    // Android UI evidence runner; never packaged in production APKs.
    androidTestImplementation(androidx.test.ext)
    androidTestImplementation(androidx.test.runner)
    androidTestImplementation(androidx.test.uiautomator)
    androidTestImplementation(androidx.workmanager.testing)
    // KMK v0.8.21-fix5: R5 correction -- genuine Compose semantics coverage for TrackInfoDialogHome's
    // Local/External row coexistence and accessible naming, per the exit gate's explicit "source
    // analogy alone is insufficient." Debug-only, instrumented; never packaged in production APKs.
    androidTestImplementation(platform(compose.bom))
    androidTestImplementation(compose.ui.test.junit4)
    debugImplementation(compose.ui.test.manifest)

    // SY -->
    // Better logging (EH)
    implementation(sylibs.xlog)

    // RatingBar (SY)
    implementation(sylibs.ratingbar)
    implementation(sylibs.composeRatingbar)

    // Google drive
    implementation(sylibs.google.api.services.drive)

    // ZXing Android Embedded
    implementation(sylibs.zxing.android.embedded)
}

androidComponents {
    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}

buildscript {
    dependencies {
        classpath(kotlinx.gradle)
    }
}

val verifyAndroidCompiler = tasks.register("verifyAndroidCompiler") {
    doLast {
        val version = com.android.tools.r8.Version.getVersionString()
        check(version.substringBefore(" ") == "9.1.31") {
            "Expected Kotlin 2.4-compatible R8 9.1.31, found $version"
        }
        logger.lifecycle("Android bytecode compiler: R8 $version")
    }
}

if (providers.gradleProperty("kmk.benchmark.fixture").isPresent) {
    tasks.matching { it.name == "preBenchmarkSeedAndroidTestBuild" }.configureEach {
        dependsOn(continuityFixtureTestSources)
    }
}

tasks.matching { it.name == "assembleKmkPublicTest" }.configureEach {
    dependsOn(verifyAndroidCompiler)
}
