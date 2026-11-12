package exh.recs.matching

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes local membership, recording and replay with group separation/link writes.
 * Call locked helpers rather than public entry points while holding this non-reentrant lock.
 * Join orchestration and network calls must remain outside it.
 */
object GroupTrackingMembershipCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withMembershipLock(block: suspend () -> T): T = mutex.withLock { block() }
}
