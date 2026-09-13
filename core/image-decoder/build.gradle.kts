plugins {
    id("mihon.library")
    kotlin("android")
}

val validationAbi = providers.gradleProperty("kmk.validation.abi").orNull
require(validationAbi == null || validationAbi in setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")) {
    "kmk.validation.abi is not a supported Android ABI"
}

android {
    namespace = "tachiyomi.decoder"

    defaultConfig {
        minSdk = 21

        validationAbi?.let { abi ->
            ndk {
                abiFilters += abi
            }
        }

        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
