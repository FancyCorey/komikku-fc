plugins {
    id("mihon.android.application")
}

configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    // Extensions execute inside Komikku, which already owns the Kotlin runtime.
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

android {
    namespace = "app.komikku.fixture.sources"

    defaultConfig {
        minSdk = 26
        versionCode = 1
        versionName = "1.6.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "fixture"
    productFlavors {
        create("alpha") {
            dimension = "fixture"
            applicationId = "app.komikku.fixture.sources.alpha"
            buildConfigField("long", "SOURCE_ID", "910000000000000001L")
            buildConfigField("long", "SEARCH_DELAY_MILLIS", "0L")
            buildConfigField(
                "boolean",
                "FAIL_PREVIEW_PAGE",
                providers.gradleProperty("kmk.fixture.previewFailure").map(String::toBoolean).orElse(false).get().toString(),
            )
            resValue("string", "fixture_source_name", "Fixture Source Alpha")
        }
        create("beta") {
            dimension = "fixture"
            applicationId = "app.komikku.fixture.sources.beta"
            buildConfigField("long", "SOURCE_ID", "910000000000000002L")
            buildConfigField(
                "long",
                "SEARCH_DELAY_MILLIS",
                providers.gradleProperty("kmk.fixture.betaSearchDelayMillis")
                    .map {
                        it.toLong().also { delayMillis ->
                            require(delayMillis in 0L..10_000L) {
                                "kmk.fixture.betaSearchDelayMillis must be between 0 and 10000"
                            }
                        }
                    }
                    .orElse(0L)
                    .get()
                    .toString() + "L",
            )
            buildConfigField(
                "boolean",
                "FAIL_PREVIEW_PAGE",
                providers.gradleProperty("kmk.fixture.previewFailure").map(String::toBoolean).orElse(false).get().toString(),
            )
            resValue("string", "fixture_source_name", "Fixture Source Beta")
        }
    }
}

dependencies {
    compileOnly(projects.core.common)
    compileOnly(projects.sourceApi)
    compileOnly(libs.okhttp.core)
}
