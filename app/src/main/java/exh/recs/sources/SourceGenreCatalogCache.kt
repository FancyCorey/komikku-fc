package exh.recs.sources

import eu.kanade.tachiyomi.source.model.FilterList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// KMK HR-2026-08-26-FOCUS-CRITERION-CATALOG-COMPLETENESS (C3/E4.3) -->
/**
 * Process-lifetime, per-source cache of [SourceGenreFilterExtractor.extract]'s output, so the For
 * You focus criterion catalog is not limited to whatever happens to be in the currently loaded
 * recommendation result set -- once a source's filter capabilities have been seen even once (in
 * any past load, this session), they stay available to the catalog for the rest of the process,
 * independent of whether that source produced any candidates in the CURRENT load.
 *
 * Deliberately reuses [BrowsePersonalRecommendationsScreenModel]'s own existing
 * `SourceRuntime.run(source, SourceRuntimeOperation.FilterList) { getFilterList() }` call (already
 * made once per matching source, every refresh, to build search queries) rather than issuing a
 * second network round-trip just for cataloging -- [record] is called as a free byproduct right
 * after that existing call already has the raw `Result<FilterList>` in hand.
 *
 * KMK F2-03 corrective history (see the fix2 handoff's F2-03 sections for full dated receipts):
 * 2026-08-27 fixed failure/successful-empty conflation, added deterministic snapshot ordering, and
 * added [pruneIneligibleSources]. 2026-08-28 (first pass) fixed a tombstone-generation defect by
 * splitting the generation barrier and the labels into two separate maps -- which then introduced a
 * NEW defect an independent review correctly found: because acceptance (checking the barrier) and
 * mutation (writing labels) were two separate operations across two separate maps, they were not
 * atomic together. An older call could pass its acceptance check, be paused (thread scheduling,
 * GC, or genuine concurrent contention), and then write its (now stale) labels AFTER a newer call
 * had already accepted and written its own -- the two-map split reintroduced exactly the race the
 * single-map tombstone was meant to prevent, just one map removed from where it was visible. The
 * same problem applied to [pruneIneligibleSources]: an older prune could still remove labels a
 * newer, already-completed record had just written.
 *
 * KMK F2-03 atomicity correction (2026-08-27): back to ONE map, [bySource], but never deleting an
 * entry once created (the actual defect in the ORIGINAL, pre-2026-08-27 design was deleting the
 * whole entry on a successful-empty result or a prune, which lost the generation barrier along with
 * the labels -- not the fact that it was one map). [Entry] now always carries both the current
 * labels (possibly empty, meaning "no labels currently active," not "unknown") and the generation
 * that produced them, and every mutation -- [record]'s three outcomes and [pruneIneligibleSources]
 * -- goes through [ConcurrentHashMap.compute], whose remapping function [ConcurrentHashMap] executes
 * atomically and exclusively per key (per the JDK's own contract: "the entire method invocation is
 * performed atomically... some attempted update operations on this map by other threads may be
 * blocked while computation is in progress"). Reading the current entry, deciding whether the
 * incoming generation is stale, and writing the new entry therefore all happen as a single
 * indivisible step for a given source id -- there is no window between "accepted" and "written" for
 * a slower, older call to land after a faster, newer one. See
 * [SourceGenreCatalogCacheConcurrencyTest] for real multi-threaded proof of this property, not just
 * sequential arrival-order tests (which cannot exercise a race that depends on genuine thread
 * interleaving).
 *
 * KMK F2-03 production-caller-boundary correction (2026-08-27): a THIRD independent review found a
 * narrower gap one level above the atomicity fix: [pruneIneligibleSources] could only protect a
 * source id it could currently SEE -- either already cached, or present in the calling refresh's own
 * `eligibleSourceIds`. A source that was eligible in an OLDER refresh, never recorded anything (no
 * cache entry yet), and was then uninstalled before a NEWER refresh ran was invisible to BOTH of
 * those sources of truth at once, so no barrier was ever raised for it, and the older refresh's
 * delayed first `record()` call could still repopulate it. [everEligibleSourceIds] (see its own doc)
 * closes this by making the cache itself the durable "which source ids has this process ever known
 * about" owner, rather than requiring [BrowsePersonalRecommendationsScreenModel] to track that
 * history itself.
 *
 * Memory has two hard limits: [snapshot] caps the combined label output, and the cache evicts its
 * oldest-generation source entry after [MAX_RETAINED_SOURCES]. A generation floor prevents an
 * evicted or removed source from being restored by an older refresh.
 */
internal object SourceGenreCatalogCache {

    /** Caps the total catalog size across every cached source -- see the class doc. */
    internal const val MAX_TOTAL_LABELS = 2000

    /** Far above a normal installed-source count, but finite under malformed or synthetic input. */
    internal const val MAX_RETAINED_SOURCES = 4096

    /** Labels and the newest refresh generation accepted for one retained source. */
    private data class Entry(val labels: Set<String>, val generation: Long)

    private val bySource = ConcurrentHashMap<Long, Entry>()

    private val generationFloor = AtomicLong(Long.MIN_VALUE)

    /**
     * Records one source result. Older generations are ignored, a successful empty result clears
     * stale labels, and a failed fetch keeps the source's last successful labels.
     */
    @Synchronized
    fun record(sourceId: Long, generation: Long, result: Result<FilterList>) {
        if (generation < generationFloor.get()) return
        bySource.compute(sourceId) { _, existing ->
            if (existing != null && generation < existing.generation) return@compute existing
            val filterList = result.getOrNull()
            if (filterList == null) {
                Entry(existing?.labels ?: emptySet(), generation)
            } else {
                Entry(SourceGenreFilterExtractor.extract(filterList), generation)
            }
        }
        trimToRetainedSourceLimit()
    }

    /**
     * Removes retained labels for sources that are no longer installed and enabled. The generation
     * floor rejects delayed results from an older refresh, including a source that had not recorded
     * labels before it was removed. Language filters are intentionally excluded because changing a
     * temporary language choice must not erase the source catalog.
     */
    @Synchronized
    fun pruneIneligibleSources(eligibleSourceIds: Set<Long>, generation: Long) {
        if (generation < generationFloor.get()) return
        generationFloor.updateAndGet { maxOf(it, generation) }
        val targets = bySource.keys - eligibleSourceIds
        for (sourceId in targets) {
            bySource.compute(sourceId) { _, existing ->
                if (existing != null && generation < existing.generation) return@compute existing
                null
            }
        }
    }

    /**
     * The bounded, deterministically-ordered union of every source's currently active labels.
     * Ordering: sources by ascending id, then each source's own labels alphabetically -- an
     * arbitrary but fixed and reproducible rule, chosen only so the SAME accumulated cache state
     * always truncates to the SAME bounded result, not because source id or alphabetical order is
     * meaningful to the focus picker itself (which re-sorts for display independently).
     */
    @Synchronized
    fun snapshot(): Set<String> {
        return bySource.entries
            .sortedBy { it.key }
            .asSequence()
            .flatMap { it.value.labels.sorted().asSequence() }
            .distinct()
            .take(MAX_TOTAL_LABELS)
            .toSet()
    }

    /** Test-only: resets accumulated state so tests never leak into each other via this singleton. */
    @Synchronized
    internal fun clearForTesting() {
        bySource.clear()
        generationFloor.set(Long.MIN_VALUE)
    }

    internal fun retainedSourceCountForTesting(): Int = bySource.size

    private fun trimToRetainedSourceLimit() {
        while (bySource.size > MAX_RETAINED_SOURCES) {
            val oldest = bySource.entries.minWithOrNull(compareBy({ it.value.generation }, { it.key })) ?: return
            if (bySource.remove(oldest.key, oldest.value)) {
                generationFloor.updateAndGet { maxOf(it, oldest.value.generation) }
            }
        }
    }
}
// KMK <--
