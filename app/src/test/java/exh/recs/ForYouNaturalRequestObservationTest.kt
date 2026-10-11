package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForYouNaturalRequestObservationTest {
    private class Capture(
        var enabled: Boolean = true,
        val cacheProvenance: ForYouNaturalCacheProvenance = ForYouNaturalCacheProvenance(),
    ) {
        var now = 1_000L
        val events = mutableListOf<String>()
        val dispositions = mutableListOf<ForYouNaturalRequestObservation.Disposition>()
        val observer = ForYouNaturalRequestObservation(
            { enabled },
            events::add,
            dispositions::add,
            cacheProvenance = cacheProvenance,
            clock = { now },
            maxCacheContexts = 2,
        )

        fun returned(generation: Long = 1, page: Int = 1, count: Int = 3): ForYouNaturalRequestObservation.Request {
            val request = observer.requestStart(generation, 77, "private query/url/filter", "profile", page)!!
            observer.providerStart(request)
            observer.providerReturn(request, count)
            observer.terminal(request, ForYouNaturalRequestObservation.Terminal.RETURNED)
            return request
        }
    }

    @Test
    fun `disabled capture never exports or allocates a request`() {
        val capture = Capture(false)
        capture.observer.generationStart(1)
        assertNull(capture.observer.requestStart(1, 77, "secret", "secret", 1))
        capture.observer.close()
        assertTrue(capture.events.isEmpty())
        assertTrue(capture.dispositions.isEmpty())
    }

    @Test
    fun `retired provider return keeps its own token and drop after supersession`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        val old = capture.observer.requestStart(1, 77, "query", "profile", 1)!!
        capture.observer.providerStart(old)
        capture.observer.supersession(1, 2)
        capture.observer.generationStart(2)
        val replacement = capture.returned(2)
        capture.observer.committed(listOf(ForYouNaturalRequestObservation.Contribution(replacement, emptySet())), emptySet())
        capture.observer.providerReturn(old, 2)
        capture.observer.terminal(old, ForYouNaturalRequestObservation.Terminal.RETURNED)
        capture.observer.dropped(listOf(ForYouNaturalRequestObservation.Contribution(old, emptySet())))
        assertTrue(capture.events.last().contains("\"request\":${old.request}"))
        assertTrue(capture.events.last().contains("source_dropped"))
        assertFalse(capture.events.any { it.contains("source_applied") && it.contains("\"generation\":1") })
    }

    @Test
    fun `suppression and cancellation cannot impersonate a committed or dropped result`() {
        for (outcome in listOf(ForYouNaturalRequestObservation.Terminal.SUPPRESSED, ForYouNaturalRequestObservation.Terminal.CANCELLED)) {
            val capture = Capture()
            capture.observer.generationStart(1)
            val request = capture.observer.requestStart(1, 77, "query", "profile", 2)!!
            capture.observer.terminal(request, outcome)
            val contribution = ForYouNaturalRequestObservation.Contribution(request, setOf(77L to "/a"))
            capture.observer.committed(listOf(contribution), contribution.candidateKeys)
            capture.observer.dropped(listOf(contribution))
            assertFalse(capture.events.any { it.contains("source_applied") || it.contains("source_dropped") || it.contains("refill_applied") })
            assertFalse(capture.events.any { it.contains("provider_start") })
        }
    }

    @Test
    fun `refill counts only committed membership and publishes once after its marker`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        val request = capture.returned(page = 2, count = 8)
        val contribution = ForYouNaturalRequestObservation.Contribution(request, setOf(77L to "/kept", 77L to "/filtered"))
        val retained = setOf(77L to "/kept", 77L to "/unrelated")
        capture.observer.committed(listOf(contribution), retained)
        capture.observer.committed(listOf(contribution), retained)
        val refill = capture.events.single { it.contains("refill_applied") }
        assertTrue(refill.contains("\"candidates\":1"))
        assertTrue(capture.events.last().contains("source_applied"))
        assertEquals(1, capture.events.count { it.contains("source_applied") })
    }

    @Test
    fun `all filtered extra page has zero accepted additions despite a populated provider response`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        val request = capture.returned(page = 2, count = 8)
        capture.observer.committed(listOf(ForYouNaturalRequestObservation.Contribution(request, setOf(77L to "/filtered"))), emptySet())
        assertTrue(capture.events.single { it.contains("refill_applied") }.contains("\"candidates\":0"))
    }

    @Test
    fun `cache provenance requires the exact successful entry version and profile`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        val request = capture.returned()
        capture.observer.rememberCache("cache", "profile", request, "version1")
        capture.observer.generationStart(2)
        capture.observer.cacheHit(2, "cache", "profile", 3, "overwritten")
        capture.observer.cacheHit(2, "cache", "different profile", 3, "version1")
        assertFalse(capture.events.any { it.contains("cache_hit") })
        capture.observer.rememberCache("cache", "profile", request, "version1")
        capture.observer.cacheHit(2, "cache", "profile", 3, "version1")
        assertEquals(1, capture.events.count { it.contains("cache_hit") })
        capture.observer.forgetCache("cache")
        capture.observer.cacheHit(2, "cache", "profile", 3, "version1")
        assertEquals(1, capture.events.count { it.contains("cache_hit") })
    }

    @Test
    fun `raw identities never reach the wire and gate retirement prevents later events`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        val request = capture.returned()
        assertFalse(capture.events.joinToString().contains("private"))
        assertFalse(capture.events.joinToString().contains("url/filter"))
        capture.enabled = false
        capture.observer.generationStart(2)
        val size = capture.events.size
        capture.enabled = true
        capture.observer.dropped(listOf(ForYouNaturalRequestObservation.Contribution(request, emptySet())))
        assertEquals(size, capture.events.size)
        assertEquals(listOf(ForYouNaturalRequestObservation.Disposition.GATE_RETIRED), capture.dispositions)
    }

    @Test
    fun `terminal or never invoked old generation does not emit provider supersession`() {
        val capture = Capture()
        capture.observer.generationStart(1)
        capture.returned()
        capture.observer.supersession(1, 2)
        capture.observer.generationStart(2)
        capture.observer.requestStart(2, 77, "query", "profile", 1)
        capture.observer.supersession(2, 3)
        assertFalse(capture.events.any { it.contains("supersession") })
    }

    @Test
    fun `exact observed cache provenance survives model disposal with fresh opaque context`() {
        val shared = ForYouNaturalCacheProvenance()
        val prior = Capture(cacheProvenance = shared)
        prior.observer.generationStart(1)
        val request = prior.returned()
        prior.observer.rememberCache("cache", "profile", request, "exact entry", expiresAt = 2_000)
        prior.observer.close()
        val current = Capture(cacheProvenance = shared)
        current.observer.generationStart(1)
        current.observer.cacheHit(1, "cache", "profile", 3, "exact entry")
        val live = current.returned()
        val hit = current.events.single { it.contains("cache_hit") }
        assertTrue(hit.contains("\"source\":${live.context.source}"))
        assertTrue(hit.contains("\"query\":${live.context.query}"))
        assertTrue(hit.contains("\"profile\":${live.context.profile}"))
        assertFalse(current.events.joinToString().contains("private"))
        current.now = 2_001
        current.observer.cacheHit(1, "cache", "profile", 3, "exact entry")
        assertEquals(1, current.events.count { it.contains("cache_hit") })
        assertTrue(current.dispositions.contains(ForYouNaturalRequestObservation.Disposition.UNKNOWN_CACHE_CONTEXT))
    }

    @Test
    fun `gate off and bounded eviction invalidate shared cache provenance`() {
        val shared = ForYouNaturalCacheProvenance()
        val capture = Capture(cacheProvenance = shared)
        capture.observer.generationStart(1)
        val request = capture.returned()
        capture.observer.rememberCache("first", "profile", request, "v1")
        capture.observer.rememberCache("second", "profile", request, "v2")
        capture.observer.rememberCache("third", "profile", request, "v3")
        capture.observer.cacheHit(1, "first", "profile", 3, "v1")
        assertTrue(capture.dispositions.contains(ForYouNaturalRequestObservation.Disposition.CACHE_CONTEXT_EVICTED))
        assertFalse(capture.events.any { it.contains("cache_hit") })
        capture.enabled = false
        capture.observer.generationStart(2)
        val current = Capture(cacheProvenance = shared)
        current.observer.generationStart(1)
        current.observer.cacheHit(1, "third", "profile", 3, "v3")
        assertFalse(current.events.any { it.contains("cache_hit") })
    }
}
