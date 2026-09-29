package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.IntentFilter
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkConstructor
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.core.common.preference.Preference
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ExtensionManagerTemporaryInstallTest {
    @TempDir
    lateinit var directory: File

    private lateinit var manager: ExtensionManager
    private lateinit var context: Context
    private lateinit var cache: File
    private lateinit var target: File
    private val store = PrivateExtensionFileStore()

    @BeforeEach
    fun prepare() {
        mockkObject(ExtensionLoader, Installer.Companion)
        mockkConstructor(ExtensionInstallReceiver::class)
        mockkConstructor(IntentFilter::class)
        every { anyConstructed<IntentFilter>().addAction(any()) } returns Unit
        every { anyConstructed<IntentFilter>().addDataScheme(any()) } returns Unit
        every { anyConstructed<ExtensionInstallReceiver>().register(any()) } returns Unit
        every { Installer.cancelInstallQueue(any(), any()) } returns Unit
        context = mockk()
        cache = File(directory, "cache").apply { mkdirs() }
        target = File(directory, "exts/test.reader.ext")
        every { context.cacheDir } returns cache
        every { ExtensionLoader.loadExtensions(context) } returns emptyList()
        every { ExtensionLoader.installPrivateExtensionFile(context, any(), any()) } answers {
            val download = secondArg<File>()
            store.install(target, thirdArg(), false) { download.copyTo(it, overwrite = true) }
        }
        every { ExtensionLoader.installPrivateExtensionFile(context, any()) } answers {
            val download = secondArg<File>()
            store.install(target, null, false) { download.copyTo(it, overwrite = true) }
        }
        every { ExtensionLoader.removeTemporaryPrivateExtension(context, any()) } answers {
            assertTrue(cache.listFiles().orEmpty().isEmpty(), "Owned download must finish before installed-file cleanup")
            store.remove(target, secondArg())
            Unit
        }
        val blacklist = mockk<Preference<Boolean>> { every { get() } returns false }
        val preferences = mockk<SourcePreferences>(relaxed = true)
        every { preferences.enableSourceBlacklist() } returns blacklist
        every { preferences.enabledLanguages().isSet() } returns false
        manager = ExtensionManager(context, preferences, mockk(relaxed = true))
    }

    @AfterEach
    fun finish() {
        if (::manager.isInitialized) manager.scope.cancel()
        unmockkObject(ExtensionLoader, Installer.Companion)
        unmockkConstructor(ExtensionInstallReceiver::class)
        unmockkConstructor(IntentFilter::class)
    }

    private fun extension(server: ServerSocket) = mockk<Extension.Available> {
        every { pkgName } returns "test.reader"
        every { signatureHash } returns "test-signature"
        every { apkUrl } returns "http://127.0.0.1:${server.localPort}/test.apk"
    }

    private fun serveComplete(server: ServerSocket, count: Int = 1) {
        thread(isDaemon = true) {
            repeat(count) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nab".toByteArray())
                        flush()
                    }
                }
            }
        }
    }

    @Test
    fun `cleanup failure is reported after a successful probe`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            serveComplete(server)
            val cleanupFailure = IOException("cleanup blocked")
            every { ExtensionLoader.removeTemporaryPrivateExtension(context, any()) } throws cleanupFailure
            val caught = assertThrows(IOException::class.java) {
                runBlocking {
                    manager.withTemporaryPrivateExtension(extension(server)) { installation ->
                        assertEquals(InstallStep.Installed, withTimeout(5_000) { installation.first { it == InstallStep.Installed || it == InstallStep.Error } })
                    }
                }
            }
            assertSame(cleanupFailure, caught.cause ?: caught)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `cleanup failure does not replace probe cancellation`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            serveComplete(server)
            val cancellation = kotlinx.coroutines.CancellationException("probe cancelled")
            val cleanupFailure = IOException("cleanup blocked")
            every { ExtensionLoader.removeTemporaryPrivateExtension(context, any()) } throws cleanupFailure
            val caught = assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                runBlocking {
                    manager.withTemporaryPrivateExtension(extension(server)) { installation ->
                        assertEquals(InstallStep.Installed, withTimeout(5_000) { installation.first { it == InstallStep.Installed || it == InstallStep.Error } })
                        throw cancellation
                    }
                }
            }
            assertSame(cancellation, caught)
            assertEquals(listOf(cleanupFailure), caught.suppressed.toList())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `failure before collection stops the download before cleanup`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val sent = CountDownLatch(1)
            val closed = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\na".toByteArray())
                        flush()
                    }
                    sent.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            }
            val failure = IOException("probe setup failed")
            val caught = assertThrows(IOException::class.java) {
                runBlocking {
                    manager.withTemporaryPrivateExtension(extension(server)) {
                        assertTrue(sent.await(5, TimeUnit.SECONDS))
                        throw failure
                    }
                }
            }
            assertSame(failure, caught)
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(target.exists())
            verify(exactly = 0) { ExtensionLoader.installPrivateExtensionFile(context, any(), any()) }
            verify(exactly = 1) { ExtensionLoader.removeTemporaryPrivateExtension(context, any()) }
        }
    }

    @Test
    fun `probe failure cleans an installed file before list visibility`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            serveComplete(server)
            val failure = IOException("probe failed")
            assertSame(
                failure,
                assertThrows(IOException::class.java) {
                    runBlocking {
                        manager.withTemporaryPrivateExtension(extension(server)) { installation ->
                            assertEquals(InstallStep.Installed, withTimeout(5_000) { installation.first { it == InstallStep.Installed || it == InstallStep.Error } })
                            assertTrue(manager.installedExtensionsFlow.value.isEmpty())
                            assertEquals("ab", target.readText())
                            throw failure
                        }
                    }
                },
            )
            assertFalse(target.exists())
            verify(exactly = 1) { ExtensionLoader.removeTemporaryPrivateExtension(context, any()) }
        }
    }

    @Test
    fun `normal update during a probe survives temporary scope cleanup`() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            serveComplete(server, 2)
            val extension = extension(server)
            manager.withTemporaryPrivateExtension(extension) { installation ->
                assertEquals(InstallStep.Installed, withTimeout(5_000) { installation.first { it == InstallStep.Installed || it == InstallStep.Error } })
                assertEquals(
                    InstallStep.Installed,
                    withTimeout(5_000) {
                        manager.installExtension(extension, BasePreferences.ExtensionInstaller.PRIVATE)
                            .first { it == InstallStep.Installed || it == InstallStep.Error }
                    },
                )
            }
            assertEquals("ab", target.readText())
            verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(context, any(), any()) }
            verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(context, any()) }
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun bindInstallerDependencies() {
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory<BasePreferences> { mockk(relaxed = true) }
                    addSingletonFactory<NetworkHelper> { mockk { every { client } returns OkHttpClient() } }
                }
            })
        }
    }
}
