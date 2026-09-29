package eu.kanade.tachiyomi.extension.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ExtensionDownloadTest {

    @TempDir
    lateinit var directory: File

    @Test
    fun `cancellation closes a download waiting for headers`() = checkCancelled(sendBody = false)

    @Test
    fun `cancellation closes a blocked response body before returning`() = checkCancelled(sendBody = true)

    private fun checkCancelled(sendBody: Boolean) = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val waiting = CountDownLatch(1)
            val closed = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    if (sendBody) {
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\na".toByteArray())
                            flush()
                        }
                    }
                    waiting.countDown()
                    if (input.read() == -1) closed.countDown()
                }
            }
            val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/extension.apk").build())
            var reachedInstall = false
            val download = async(Dispatchers.IO.limitedParallelism(1)) {
                call.downloadExtensionTo(File(directory, "download.apk"))
                reachedInstall = true
            }
            try {
                assertTrue(waiting.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) { download.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue(download.isCancelled)
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertFalse(reachedInstall)
            } finally {
                call.cancel()
                download.cancelAndJoin()
            }
        }
    }

    @Test
    fun `successful download preserves the file bytes`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val bytes = "extension test bytes".toByteArray()
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
            val target = File(directory, "complete.apk")
            val call = OkHttpClient().newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/extension.apk").build())
            withTimeout(5_000) {
                async(Dispatchers.IO) { call.downloadExtensionTo(target) }.await()
            }
            assertArrayEquals(bytes, target.readBytes())
            assertFalse(call.isCanceled())
        }
    }
}
