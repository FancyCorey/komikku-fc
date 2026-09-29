package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.util.Log
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

class ExtensionLoaderPrivateInstallTest {
    @TempDir
    lateinit var directory: File

    private val manager = mockk<PackageManager>()
    private val context = mockk<Context>()
    private lateinit var download: File
    private lateinit var target: File
    private lateinit var info: PackageInfo

    @BeforeEach
    fun prepare() {
        mockkObject(ExtensionInstallReceiver.Companion)
        mockkStatic(Log::class)
        mockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
        every { Log.isLoggable(any(), any()) } returns false
        every { ExtensionInstallReceiver.notifyAdded(any(), any()) } returns Unit
        every { ExtensionInstallReceiver.notifyReplaced(any(), any()) } returns Unit
        every { ExtensionInstallReceiver.notifyRemoved(any(), any()) } returns Unit
        // Host filesystems do not share Android's read-only unlink semantics.
        every { any<File>().copyAndSetReadOnlyTo(any(), any(), any()) } answers {
            firstArg<File>().copyTo(secondArg(), overwrite = thirdArg())
        }
        every { context.filesDir } returns directory
        every { context.packageManager } returns manager
        every { manager.getPackageInfo(any<String>(), any<Int>()) } throws mockk<PackageManager.NameNotFoundException>(relaxed = true)
        download = File(directory, "download.apk").apply { writeText("replacement") }
        target = File(directory, "exts/test.reader.ext")
        info = mockk<PackageInfo>().apply {
            packageName = "test.reader"
            versionCode = 1
            applicationInfo = mockk<ApplicationInfo>()
            reqFeatures = arrayOf(mockk<FeatureInfo>().apply { name = "tachiyomi.extension" })
            signatures = arrayOf(mockk<Signature> { every { toByteArray() } returns byteArrayOf(1, 2, 3) })
        }
        every { manager.getPackageArchiveInfo(any<String>(), any<Int>()) } returns info
    }

    @AfterEach
    fun finish() {
        unmockkObject(ExtensionInstallReceiver.Companion)
        unmockkStatic(Log::class)
        unmockkStatic("eu.kanade.tachiyomi.util.storage.FileExtensionsKt")
    }

    @Test
    fun `recovery failure skips private loading without crashing startup`() {
        target.parentFile.mkdirs()
        target.writeText("user extension")
        every { manager.getInstalledPackages(any<Int>()) } returns emptyList()
        mockkObject(PrivateExtensionRecovery)
        try {
            every { PrivateExtensionRecovery.recover(any()) } throws IOException("lock unavailable")
            assertTrue(ExtensionLoader.loadExtensions(context).isEmpty())
            assertEquals("user extension", target.readText())
            verify(exactly = 0) { manager.getPackageArchiveInfo(any<String>(), any<Int>()) }
        } finally {
            unmockkObject(PrivateExtensionRecovery)
        }
    }

    @Test
    fun `temporary loader install is cleaned without installed list visibility`() {
        val lease = PrivateExtensionFileStore.Lease("test.reader")
        assertTrue(ExtensionLoader.installPrivateExtensionFile(context, download, lease))
        assertEquals("replacement", target.readText())
        ExtensionLoader.removeTemporaryPrivateExtension(context, lease)
        assertFalse(target.exists())
        verify(exactly = 1) { ExtensionInstallReceiver.notifyAdded(context, "test.reader") }
        verify(exactly = 1) { ExtensionInstallReceiver.notifyRemoved(context, "test.reader") }
    }

    @Test
    fun `temporary loader refuses a different package`() {
        assertFalse(ExtensionLoader.installPrivateExtensionFile(context, download, PrivateExtensionFileStore.Lease("other.reader")))
        assertFalse(target.exists())
        verify(exactly = 0) { ExtensionInstallReceiver.notifyAdded(any(), any()) }
    }

    @Test
    fun `temporary loader preserves a preexisting private file`() {
        target.parentFile.mkdirs()
        target.writeText("working version")
        val lease = PrivateExtensionFileStore.Lease("test.reader")
        assertFalse(ExtensionLoader.installPrivateExtensionFile(context, download, lease))
        ExtensionLoader.removeTemporaryPrivateExtension(context, lease)
        assertEquals("working version", target.readText())
        verify(exactly = 0) { ExtensionInstallReceiver.notifyRemoved(any(), any()) }
    }

    @Test
    fun `temporary loader preserves a shared installation`() {
        every { manager.getPackageInfo("test.reader", any<Int>()) } returns info
        assertFalse(ExtensionLoader.installPrivateExtensionFile(context, download, PrivateExtensionFileStore.Lease("test.reader")))
        assertFalse(target.exists())
        verify(exactly = 0) { ExtensionInstallReceiver.notifyAdded(any(), any()) }
    }

    @Test
    fun `normal loader notification failure restores the working file`() {
        target.parentFile.mkdirs()
        target.writeText("working version")
        every { ExtensionInstallReceiver.notifyReplaced(context, "test.reader") } throws IOException("notification failed")
        assertFalse(ExtensionLoader.installPrivateExtensionFile(context, download))
        assertEquals("working version", target.readText())
        assertEquals(listOf(target), target.parentFile.listFiles().orEmpty().filter { !it.name.startsWith(".") })
    }

    @Test
    fun `normal loader update invalidates a temporary cleanup handle`() {
        val lease = PrivateExtensionFileStore.Lease("test.reader")
        assertTrue(ExtensionLoader.installPrivateExtensionFile(context, download, lease))
        download.writeText("user update")
        assertTrue(ExtensionLoader.installPrivateExtensionFile(context, download))
        ExtensionLoader.removeTemporaryPrivateExtension(context, lease)
        assertEquals("user update", target.readText())
        verify(exactly = 0) { ExtensionInstallReceiver.notifyRemoved(any(), any()) }
    }
}
