package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

class KmkReleaseNativeToolchainSourceTest {

    @Test
    fun `Android plugin classpath provides Kotlin 2_4 compatible R8`() {
        val settings = File("../settings.gradle.kts").readText()
        val pluginManagement = settings.substringAfter("pluginManagement {").substringBefore("resolutionStrategy {")

        assertTrue(pluginManagement.contains("buildscript {"))
        assertTrue(pluginManagement.contains("google()"))
        assertTrue(pluginManagement.contains("classpath(\"com.android.tools:r8:9.1.31\")"))
        val appBuild = File("../app/build.gradle.kts").readText()
        assertTrue(appBuild.contains("com.android.tools.r8.Version.getVersionString()"))
        assertTrue(appBuild.contains("dependsOn(verifyAndroidCompiler)"))
        val kotlinCatalog = File("../gradle/kotlinx.versions.toml").readText()
        assertTrue(kotlinCatalog.contains("kotlin_version = \"2.4.0\""))
    }

    @Test
    fun `release tests and public assembly use separate serial compiler lanes`() {
        val workflow = File("../.github/workflows/build_release.yml").readText()
        val testCommand = workflow.lineSequence().first { "./gradlew spotlessCheck" in it }

        assertFalse(testCommand.contains("assembleKmkPublicTest"))
        assertTrue(testCommand.contains(":domain:testDebugUnitTest :data:testDebugUnitTest"))
        assertTrue(testCommand.contains("--max-workers=1 --no-parallel"))
        assertTrue(workflow.contains("gradle_args=(:app:assembleKmkPublicTest -Penable-updater --max-workers=1 --no-parallel)"))
    }

    @Test
    fun `release workflow requires upgrade continuity before creating a draft`() {
        val workflow = File("../.github/workflows/build_release.yml").readText()
        val continuity = workflow.indexOf("name: Verify upgrade continuity with the published release")
        val signing = workflow.indexOf("name: Sign release APKs")
        val draft = workflow.indexOf("name: Create draft release")

        assertTrue(continuity >= 0 && continuity < signing && signing < draft)
        assertTrue(workflow.contains("releases/latest --jq '.tag_name'"))
        assertTrue(workflow.contains("test \"${'$'}{VERSION_CODE}\" -gt \"${'$'}{previous_code}\""))
        assertTrue(workflow.contains("test \"${'$'}{certificate_fingerprint}\" = \"${'$'}{PREVIOUS_CERTIFICATE_SHA256}\""))
        assertTrue(workflow.contains("--argjson version_code \"${'$'}{VERSION_CODE}\""))
        assertTrue(workflow.indexOf("name: Refuse changes to an already published tag") in signing until draft)
        assertTrue(workflow.contains("releases/tags/${'$'}{RELEASE_TAG}"))
        assertTrue(workflow.contains("jq -e '.draft == true'"))
        assertTrue(workflow.contains("^v[0-9]+\\.[0-9]+\\.[0-9]+(-fix[0-9]+)?${'$'}"))
        assertFalse(workflow.contains("tag=\"${'$'}{{ steps.release.outputs.tag }}\""))
    }

    @Test
    fun `every signed release APK has its package and version verified`() {
        val workflow = File("../.github/workflows/build_release.yml").readText()
        val signedApkLoop = workflow.substringAfter("for apk in Komikku-KMK-*.apk; do").substringBefore("done")

        assertTrue(signedApkLoop.contains("dump badging \"${'$'}{apk}\""))
        assertTrue(signedApkLoop.contains("package: name='app.komikku.kmk'"))
        assertTrue(signedApkLoop.contains("versionName='${'$'}{VERSION_NAME}'"))
        assertTrue(signedApkLoop.contains("versionCode='${'$'}{VERSION_CODE}'"))
    }

    @Test
    fun `release workflow installs the native configuration tool`() {
        val workflow = File("../.github/workflows/build_release.yml").readText()

        assertTrue(workflow.contains("name: Set up native build tools"))
        assertTrue(workflow.contains("sudo apt-get install --yes nasm"))
        assertTrue(workflow.contains("meson==1.12.0"))
        assertTrue(workflow.contains("meson/bin\" >> \"${'$'}GITHUB_PATH"))
        assertTrue(workflow.contains("nasm --version"))
    }

    @Test
    fun `dav1d uses the active Android CMake toolchain on every host`() {
        val source = File("../core/image-decoder/src/main/cpp/libheif/dav1d.cmake").readText()

        assertTrue(source.contains("${'$'}{CMAKE_C_COMPILER}"))
        assertTrue(source.contains("${'$'}{CMAKE_CXX_COMPILER}"))
        assertTrue(source.contains("${'$'}{CMAKE_SYSROOT}"))
        assertFalse(source.contains("prebuilt/windows-x86_64"))
        assertFalse(source.contains("/bin/clang.exe"))
    }

    @Test
    fun `release build carries its unavailable JitPack dependency`() {
        val appBuild = File("../app/build.gradle.kts").readText()
        val catalog = File("../gradle/libs.versions.toml").readText()
        val artifact = File("../app/libs/flexible-adapter-c8013533.aar")

        assertTrue(appBuild.contains("implementation(files(\"libs/flexible-adapter-c8013533.aar\"))"))
        assertFalse(catalog.contains("com.github.arkon.FlexibleAdapter"))
        assertTrue(artifact.isFile)
        assertEquals(
            "41929c785c249e0395faf89fd6bb253aafd65d44d88dbeaa46ecd9658d706cc4",
            MessageDigest.getInstance("SHA-256")
                .digest(artifact.readBytes())
                .joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `formatting excludes generated native build files`() {
        val lintConvention = File("../buildSrc/src/main/kotlin/mihon.code.lint.gradle.kts").readText()

        assertTrue(lintConvention.contains("add(\"**/.cxx/**/*.xml\")"))
    }

    @Test
    fun `release properties do not enable services in the development variant`() {
        val appBuild = File("../app/build.gradle.kts").readText()
        val debugBlock = appBuild.substringAfter("val debug by getting {").substringBefore("val release by getting {")

        assertTrue(debugBlock.contains("buildConfigField(\"boolean\", \"UPDATER_ENABLED\", \"false\")"))
        assertTrue(debugBlock.contains("buildConfigField(\"boolean\", \"GOOGLE_DRIVE_SYNC_ENABLED\", \"false\")"))
    }

    @Test
    fun `locale configuration is generated during task execution`() {
        val generator = File("../buildSrc/src/main/kotlin/mihon/buildlogic/tasks/LocalesConfigTask.kt").readText()

        assertTrue(generator.contains("inputs.files(localeResources)"))
        assertTrue(generator.contains("outputs.file(outputFile)"))
        assertTrue(generator.contains("doLast {"))
    }
}
