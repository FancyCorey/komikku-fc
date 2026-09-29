package tachiyomi.core.common.util.lang

import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rx.Observable
import rx.subscriptions.Subscriptions
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RxCoroutineBridgeTest {
    @Test
    fun `single result and source errors retain their original behavior`() = runBlocking {
        assertEquals("result", Observable.just("result").awaitSingle())
        val error = IllegalStateException("Source failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { Observable.error<String>(error).awaitSingle() }
        }
        assertEquals(error.message, thrown.message)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `cancellation closes an in flight legacy HTTP search connection`() = runBlocking {
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    activeCall.set(call)
                }
            })
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        try {
            ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
                server.soTimeout = 3_000
                val request = Request.Builder().url("http://localhost:${server.localPort}/search?page=2").build()
                val job = launch(Dispatchers.IO) {
                    client.newCall(request).asObservableSuccess()
                        .map { response -> response.use { "result" } }
                        .awaitSingle()
                }
                try {
                    server.accept().use { peer ->
                        peer.soTimeout = 3_000
                        val input = peer.getInputStream().bufferedReader()
                        assertTrue(input.readLine().startsWith("GET /search?page=2 "))
                        while (input.readLine()?.isNotEmpty() == true) { }
                        job.cancel()
                        val closed = try {
                            input.read() == -1
                        } catch (_: SocketException) {
                            true
                        }
                        assertTrue(closed, "HTTP connection stayed open after cancellation")
                        assertTrue(activeCall.get().isCanceled())
                    }
                } finally {
                    withTimeout(3_000) { job.cancelAndJoin() }
                }
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `cancellation unsubscribes while subscription is still blocked`() = runBlocking {
        val started = CountDownLatch(1)
        val unsubscribed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observable = Observable.unsafeCreate<String> { subscriber ->
            subscriber.add(Subscriptions.create { unsubscribed.countDown() })
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            if (!subscriber.isUnsubscribed) {
                subscriber.onNext("late result")
                subscriber.onCompleted()
            }
        }
        val job = launch(Dispatchers.IO) { observable.awaitSingle() }
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS), "Subscription did not start")
            job.cancel()
            assertTrue(unsubscribed.await(1, TimeUnit.SECONDS), "Cancellation waited for subscription to return")
        } finally {
            release.countDown()
            withTimeout(3_000) { job.cancelAndJoin() }
        }
    }
}
