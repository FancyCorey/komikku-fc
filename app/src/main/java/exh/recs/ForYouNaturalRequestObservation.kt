package exh.recs

/**
 * Per-ScreenModel, opt-in observation of logical provider calls, never physical HTTP traffic.
 * Session identities stay private; exact cache-write provenance may remain in bounded private process
 * memory across model disposal until cache-entry expiry/capacity eviction or observed gate/capture
 * retirement. The sink receives enums/numbers only.
 * Callers serialize generation reservation and successful state publication with loadLaunchLock.
 */
internal class ForYouNaturalRequestObservation(
    private val enabled: () -> Boolean,
    private val sink: (String) -> Unit,
    private val disposition: (Disposition) -> Unit,
    private val cacheProvenance: ForYouNaturalCacheProvenance = ForYouNaturalCacheProvenance(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxCacheContexts: Int = 1,
) {
    internal enum class Disposition { GATE_RETIRED, CAPTURE_LIMIT, UNKNOWN_CACHE_CONTEXT, CACHE_CONTEXT_EVICTED, CLOSED }

    internal enum class Terminal(val wireValue: String) {
        RETURNED("returned"),
        CANCELLED("cancelled"),
        ERROR("error"),
        SUPPRESSED("suppressed"),
    }

    internal data class Context(val source: Long, val query: Long, val profile: Long)

    internal class Request internal constructor(
        val generation: Long,
        val request: Long,
        val context: Context,
        val page: Int,
        internal val sourceId: Long,
        internal val concreteQuery: String,
        internal val profileKey: String,
    ) {
        internal var invoked = false
        internal var terminal: Terminal? = null
        internal var returned = false
        internal var candidates = 0
        internal var published = false
    }

    internal data class Contribution(val request: Request, val candidateKeys: Set<Pair<Long, String>>)

    private val sources = mutableMapOf<Long, Long>()
    private val queries = mutableMapOf<String, Long>()
    private val profiles = mutableMapOf<String, Long>()
    private val activeRequests = mutableMapOf<Long, Request>()
    private val generations = mutableSetOf<Long>()
    private var nextRequest = 1L
    private var events = 0
    private var wasEnabled = false
    private var retired = false

    @Synchronized
    private fun admitted(): Boolean {
        if (retired) return false
        if (!enabled()) {
            cacheProvenance.clear()
            if (wasEnabled) retire(Disposition.GATE_RETIRED)
            return false
        }
        wasEnabled = true
        return true
    }

    private fun retire(reason: Disposition) {
        retired = true
        sources.clear()
        queries.clear()
        profiles.clear()
        activeRequests.clear()
        generations.clear()
        // Opaque identities end with this session. Exact cache provenance may bridge model
        // disposal only while the private gate stays enabled and the original entry is valid.
        if (reason == Disposition.GATE_RETIRED || reason == Disposition.CAPTURE_LIMIT) cacheProvenance.clear()
        if (wasEnabled) disposition(reason)
    }

    /** The existing evidence reader accepts at most 10,000 events, not a new product budget. */
    private fun emit(event: String, generation: Long, fields: String = "") {
        if (!admitted()) return
        if (events >= 10_000) {
            retire(Disposition.CAPTURE_LIMIT)
            return
        }
        events++
        sink("{\"event\":\"$event\",\"generation\":$generation$fields}")
    }

    @Synchronized
    fun supersession(old: Long, replacement: Long) {
        if (admitted() && old in generations && replacement > 0 && replacement !in generations &&
            activeRequests.values.any { it.generation == old && it.invoked && it.terminal == null }
        ) {
            emit("supersession", old, ",\"replacement\":$replacement")
        }
    }

    @Synchronized
    fun generationStart(generation: Long) {
        if (!admitted() || generation <= 0 || !generations.add(generation)) return
        emit("generation_start", generation)
    }

    @Synchronized
    fun requestStart(generation: Long, source: Long, concreteQuery: String, profile: String, page: Int): Request? {
        if (!admitted() || generation !in generations || page < 1) return null
        // Tables cannot outgrow the finite event capture; no raw key reaches emit().
        val context = opaqueContext(source, concreteQuery, profile)
        val token = Request(generation, nextRequest++, context, page, source, concreteQuery, profile)
        emit(
            "request_start",
            generation,
            ",\"request\":${token.request},\"source\":${context.source},\"query\":${context.query}" +
                ",\"profile\":${context.profile},\"page\":$page",
        )
        return token.takeUnless { retired }
    }

    private fun opaqueContext(source: Long, query: String, profile: String): Context = Context(
        sources.getOrPut(source) { sources.size.toLong() + 1 },
        queries.getOrPut(query) { queries.size.toLong() + 1 },
        profiles.getOrPut(profile) { profiles.size.toLong() + 1 },
    )

    @Synchronized
    fun providerStart(request: Request?) {
        if (request == null || !admitted() || request.invoked || request.terminal != null) return
        request.invoked = true
        activeRequests[request.request] = request
        emit("provider_start", request.generation, ",\"request\":${request.request}")
    }

    @Synchronized
    fun providerReturn(request: Request?, count: Int) {
        if (request == null || !admitted() || !request.invoked || request.returned || request.terminal != null) return
        request.returned = true
        request.candidates = count.coerceAtLeast(0)
        emit("provider_return", request.generation, ",\"request\":${request.request},\"candidates\":${request.candidates}")
    }

    @Synchronized
    fun terminal(request: Request?, outcome: Terminal) {
        if (request == null || !admitted() || request.terminal != null) return
        request.terminal = outcome
        activeRequests.remove(request.request)
        emit("request_terminal", request.generation, ",\"request\":${request.request},\"outcome\":\"${outcome.wireValue}\"")
    }

    /** Bind only after the existing cache write succeeds, with the actual provider context. */
    @Synchronized
    fun rememberCache(key: String, profile: String, request: Request?, version: Any, expiresAt: Long = Long.MAX_VALUE) {
        if (request == null || !admitted() || request.terminal != Terminal.RETURNED || request.profileKey != profile) return
        val evicted = cacheProvenance.put(
            key,
            ForYouNaturalCacheProvenance.Entry(request.sourceId, request.concreteQuery, profile, version, expiresAt),
            clock(),
            maxCacheContexts,
        )
        if (evicted) disposition(Disposition.CACHE_CONTEXT_EVICTED)
    }

    @Synchronized
    fun forgetCache(key: String) {
        cacheProvenance.remove(key)
    }

    @Synchronized
    fun cacheHit(generation: Long, key: String, profile: String, count: Int, version: Any) {
        if (!admitted() || generation !in generations) return
        val cached = cacheProvenance.get(key, profile, version, clock())
        if (cached == null) {
            disposition(Disposition.UNKNOWN_CACHE_CONTEXT)
            return
        }
        val context = opaqueContext(cached.sourceId, cached.concreteQuery, cached.profileKey)
        emit(
            "cache_hit",
            generation,
            ",\"source\":${context.source},\"query\":${context.query}" +
                ",\"profile\":${context.profile},\"candidates\":${count.coerceAtLeast(0)}",
        )
    }

    /** Called only after a successful CAS, still inside the caller's generation boundary. */
    @Synchronized
    fun committed(contributions: List<Contribution>, retained: Set<Pair<Long, String>>) {
        if (!admitted()) return
        contributions.forEach { contribution ->
            val request = contribution.request
            if (request.published || request.terminal != Terminal.RETURNED) return@forEach
            request.published = true
            if (request.page > 1) {
                val accepted = contribution.candidateKeys.intersect(retained).size.coerceAtMost(request.candidates)
                emit("refill_applied", request.generation, ",\"request\":${request.request},\"candidates\":$accepted")
            }
            emit("source_applied", request.generation, ",\"request\":${request.request}")
        }
    }

    @Synchronized
    fun dropped(contributions: List<Contribution>) {
        if (!admitted()) return
        contributions.forEach { contribution ->
            val request = contribution.request
            if (request.published || request.terminal !in setOf(Terminal.RETURNED, Terminal.ERROR)) return@forEach
            request.published = true
            emit("source_dropped", request.generation, ",\"request\":${request.request}")
        }
    }

    @Synchronized
    fun close() {
        if (!retired) retire(if (wasEnabled && !enabled()) Disposition.GATE_RETIRED else Disposition.CLOSED)
    }
}

/**
 * Private process-memory provenance, never persisted or exported. It carries exact observed
 * cache writes across model replacement; numeric session identities are allocated afresh on read.
 * Capacity comes from the existing source-attempt bound, expiry from the actual cache entry.
 */
internal class ForYouNaturalCacheProvenance {
    internal data class Entry(
        val sourceId: Long,
        val concreteQuery: String,
        val profileKey: String,
        val version: Any,
        val expiresAt: Long,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun put(key: String, entry: Entry, now: Long, capacity: Int): Boolean {
        entries.entries.removeAll { it.value.expiresAt < now }
        entries.remove(key)
        if (entry.expiresAt < now) return false
        entries[key] = entry
        var evicted = false
        while (entries.size > capacity.coerceAtLeast(1)) {
            entries.remove(entries.keys.first())
            evicted = true
        }
        return evicted
    }

    @Synchronized
    fun get(key: String, profile: String, version: Any, now: Long): Entry? {
        entries.entries.removeAll { it.value.expiresAt < now }
        val entry = entries[key] ?: return null
        if (entry.profileKey != profile || entry.version != version) {
            entries.remove(key)
            return null
        }
        return entry
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
