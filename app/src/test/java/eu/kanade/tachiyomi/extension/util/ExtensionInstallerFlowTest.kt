package eu.kanade.tachiyomi.extension.util

import android.content.Context
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ExtensionInstallerFlowTest {

    @TempDir
    lateinit var directory: File

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeEach
    fun prepare() {
        mockkObject(Installer.Companion, ExtensionLoader)
        every { Installer.cancelInstallQueue(any(), any()) } returns Unit
    }

    @AfterEach
    fun finish() {
        scope.cancel()
        unmockkObject(Installer.Companion, ExtensionLoader)
    }

    private fun installer(): ExtensionInstaller {
        val context = mockk<Context>()
        every { context.cacheDir } returns directory
        return ExtensionInstaller(context, mockk(relaxed = true), OkHttpClient(), scope)
    }

    private fun extension() = mockk<Extension> {
        every { pkgName } returns "test.reader"
        every { signatureHash } returns "test-signature"
    }

    @Test
    fun `collector cancellation waits for download cleanup`() = runBlocking {
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
            val flow = installer().downloadAndInstall("http://127.0.0.1:${server.localPort}/test.apk", extension(), BasePreferences.ExtensionInstaller.PRIVATE)
            val collector = async(start = CoroutineStart.UNDISPATCHED) { flow.first { it == InstallStep.Installed || it == InstallStep.Error } }
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) {
                    while (directory.listFiles().orEmpty().none { it.length() > 0 }) delay(10)
                    collector.cancelAndJoin()
                }
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                verify(exactly = 0) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
            } finally {
                collector.cancelAndJoin()
            }
        }
    }

    @Test
    fun `old collector cleanup preserves a replacement download`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { oldServer ->
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { newServer ->
                val oldSent = CountDownLatch(1)
                val oldClosed = CountDownLatch(1)
                val newSent = CountDownLatch(1)
                val releaseNew = CountDownLatch(1)
                val bytes = "replacement extension bytes".toByteArray()
                thread(isDaemon = true) {
                    oldServer.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\na".toByteArray())
                            flush()
                        }
                        oldSent.countDown()
                        if (input.read() == -1) oldClosed.countDown()
                    }
                }
                thread(isDaemon = true) {
                    newServer.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes, 0, 1)
                            flush()
                            newSent.countDown()
                            if (releaseNew.await(5, TimeUnit.SECONDS)) {
                                write(bytes, 1, bytes.size - 1)
                                flush()
                            }
                        }
                    }
                }
                var installedBytes = byteArrayOf()
                every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } answers {
                    installedBytes = secondArg<File>().readBytes()
                    true
                }
                val installer = installer()
                val extension = extension()
                val oldFlow = installer.downloadAndInstall("http://127.0.0.1:${oldServer.localPort}/old.apk", extension, BasePreferences.ExtensionInstaller.PRIVATE)
                val oldCollector = async(start = CoroutineStart.UNDISPATCHED) { oldFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
                var newCollector: Deferred<InstallStep>? = null
                try {
                    assertTrue(oldSent.await(5, TimeUnit.SECONDS))
                    val oldFile = withTimeout(5_000) {
                        while (directory.listFiles().orEmpty().none { it.length() > 0 }) delay(10)
                        directory.listFiles().orEmpty().single()
                    }
                    val newFlow = installer.downloadAndInstall("http://127.0.0.1:${newServer.localPort}/new.apk", extension, BasePreferences.ExtensionInstaller.PRIVATE)
                    val replacement = async(start = CoroutineStart.UNDISPATCHED) { newFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
                    newCollector = replacement
                    assertTrue(newSent.await(5, TimeUnit.SECONDS))
                    withTimeout(5_000) { oldCollector.cancelAndJoin() }
                    assertTrue(oldClosed.await(5, TimeUnit.SECONDS))
                    val newFile = withTimeout(5_000) {
                        while (directory.listFiles().orEmpty().none { it.length() > 0 }) delay(10)
                        directory.listFiles().orEmpty().single()
                    }
                    assertNotEquals(oldFile.absolutePath, newFile.absolutePath)
                    releaseNew.countDown()
                    assertEquals(InstallStep.Installed, withTimeout(5_000) { replacement.await() })
                    assertArrayEquals(bytes, installedBytes)
                    assertTrue(directory.listFiles().orEmpty().isEmpty())
                    verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
                } finally {
                    releaseNew.countDown()
                    oldCollector.cancelAndJoin()
                    newCollector?.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun `old private completion cannot finish a replacement download`() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            val installingOld = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val downloadingNew = CountDownLatch(1)
            val releaseNew = CountDownLatch(1)
            thread(isDaemon = true) {
                repeat(2) { index ->
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\na".toByteArray())
                            flush()
                            if (index == 1) {
                                downloadingNew.countDown()
                                releaseNew.await(5, TimeUnit.SECONDS)
                            }
                            write('b'.code)
                            flush()
                        }
                    }
                }
            }
            every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } answers {
                installingOld.countDown()
                releaseOld.await(5, TimeUnit.SECONDS)
                true
            }
            val installer = installer()
            val extension = extension()
            val url = "http://127.0.0.1:${server.localPort}/test.apk"
            val oldFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
            val oldCollector = async(start = CoroutineStart.UNDISPATCHED) { oldFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
            var replacement: Deferred<InstallStep>? = null
            try {
                assertTrue(installingOld.await(5, TimeUnit.SECONDS))
                val oldFile = directory.listFiles().orEmpty().single()
                val newFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
                val newCollector = async(start = CoroutineStart.UNDISPATCHED) { newFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
                replacement = newCollector
                assertTrue(downloadingNew.await(5, TimeUnit.SECONDS))
                releaseOld.countDown()
                withTimeout(5_000) {
                    while (oldFile.exists()) delay(10)
                }
                delay(100)
                assertFalse(newCollector.isCompleted, "The replacement body is still incomplete")
                releaseNew.countDown()
                assertEquals(InstallStep.Installed, withTimeout(5_000) { newCollector.await() })
                verify(exactly = 2) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
            } finally {
                releaseOld.countDown()
                releaseNew.countDown()
                oldCollector.cancelAndJoin()
                replacement?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `replacement private copy waits for the old copy to finish`() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                repeat(2) {
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
            val oldCopy = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val newReady = CountDownLatch(1)
            val copies = AtomicInteger()
            every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } answers {
                if (copies.incrementAndGet() == 1) {
                    oldCopy.countDown()
                    releaseOld.await(5, TimeUnit.SECONDS)
                }
                true
            }
            val installer = installer()
            val extension = extension()
            val url = "http://127.0.0.1:${server.localPort}/test.apk"
            val oldFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
            val oldCollector = async(start = CoroutineStart.UNDISPATCHED) { oldFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
            var replacement: Deferred<InstallStep>? = null
            try {
                assertTrue(oldCopy.await(5, TimeUnit.SECONDS))
                val newFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
                val newCollector = async(start = CoroutineStart.UNDISPATCHED) {
                    newFlow.onEach { if (it == InstallStep.Installing) newReady.countDown() }
                        .first { it == InstallStep.Installed || it == InstallStep.Error }
                }
                replacement = newCollector
                withTimeout(5_000) {
                    while (newReady.count > 0) delay(10)
                }
                assertEquals(1, copies.get())
                assertFalse(newCollector.isCompleted)
                releaseOld.countDown()
                assertEquals(InstallStep.Installed, withTimeout(5_000) { newCollector.await() })
                assertEquals(2, copies.get())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                releaseOld.countDown()
                oldCollector.cancelAndJoin()
                replacement?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `canceled private waiter removes only its own download`() = runBlocking {
        ServerSocket(0, 2, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                repeat(2) {
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
            val oldCopy = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val newReady = CountDownLatch(1)
            val copies = AtomicInteger()
            every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } answers {
                copies.incrementAndGet()
                oldCopy.countDown()
                releaseOld.await(5, TimeUnit.SECONDS)
                true
            }
            val installer = installer()
            val extension = extension()
            val url = "http://127.0.0.1:${server.localPort}/test.apk"
            val oldFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
            val oldCollector = async(start = CoroutineStart.UNDISPATCHED) { oldFlow.first { it == InstallStep.Installed || it == InstallStep.Error } }
            var replacement: Deferred<InstallStep>? = null
            try {
                assertTrue(oldCopy.await(5, TimeUnit.SECONDS))
                val oldFile = directory.listFiles().orEmpty().single()
                val newFlow = installer.downloadAndInstall(url, extension, BasePreferences.ExtensionInstaller.PRIVATE)
                val newCollector = async(start = CoroutineStart.UNDISPATCHED) {
                    newFlow.onEach { if (it == InstallStep.Installing) newReady.countDown() }
                        .first { it == InstallStep.Installed || it == InstallStep.Error }
                }
                replacement = newCollector
                withTimeout(5_000) {
                    while (newReady.count > 0) delay(10)
                    newCollector.cancelAndJoin()
                }
                assertEquals(listOf(oldFile), directory.listFiles().orEmpty().toList())
                assertEquals(1, copies.get())
                releaseOld.countDown()
                assertEquals(InstallStep.Installed, withTimeout(5_000) { oldCollector.await() })
                assertEquals(1, copies.get())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                releaseOld.countDown()
                oldCollector.cancelAndJoin()
                replacement?.cancelAndJoin()
            }
        }
    }

    @Test
    fun `rejected private install reports error and removes its download`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nab".toByteArray())
                        flush()
                    }
                }
            }
            every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } returns false
            val result = withTimeout(5_000) {
                installer().downloadAndInstall("http://127.0.0.1:${server.localPort}/test.apk", extension(), BasePreferences.ExtensionInstaller.PRIVATE)
                    .first { it == InstallStep.Installed || it == InstallStep.Error }
            }
            assertEquals(InstallStep.Error, result)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
        }
    }

    @Test
    fun `private install receives complete bytes and removes its temporary download`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val bytes = "private extension test bytes".toByteArray()
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            var installedBytes = byteArrayOf()
            every { ExtensionLoader.installPrivateExtensionFile(any(), any()) } answers {
                installedBytes = secondArg<File>().readBytes()
                true
            }
            val result = withTimeout(5_000) {
                installer().downloadAndInstall("http://127.0.0.1:${server.localPort}/test.apk", extension(), BasePreferences.ExtensionInstaller.PRIVATE)
                    .first { it == InstallStep.Installed || it == InstallStep.Error }
            }
            assertEquals(InstallStep.Installed, result)
            assertArrayEquals(bytes, installedBytes)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            verify(exactly = 1) { ExtensionLoader.installPrivateExtensionFile(any(), any()) }
        }
    }
}
