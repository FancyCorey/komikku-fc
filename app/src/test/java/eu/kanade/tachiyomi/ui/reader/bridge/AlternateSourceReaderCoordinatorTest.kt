package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.tachiyomi.source.SourceRuntimeFailure
import eu.kanade.tachiyomi.source.SourceRuntimeFailureKind
import eu.kanade.tachiyomi.source.SourceRuntimeFailureRegistry
import eu.kanade.tachiyomi.source.SourceRuntimeOperation
import exh.recs.bridge.AlternateSourceBridgeController
import exh.recs.bridge.AlternateSourceBridgeMutationResult
import exh.recs.matching.CrossSourceIdentityAuthorizationResolver
import exh.recs.matching.CrossSourceIdentityDecisionController
import exh.recs.matching.CrossSourceIdentityMutation
import exh.recs.matching.CrossSourceIdentityMutationResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.GetAlternateSourceBridge
import tachiyomi.domain.taste.model.AlternateSourceBridge
import tachiyomi.domain.taste.model.AlternateSourceBridgeMapping
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingKey
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingRelation
import tachiyomi.domain.taste.model.AlternateSourceBridgeMappingState
import tachiyomi.domain.taste.model.AlternateSourceBridgePolicy
import tachiyomi.domain.taste.model.AlternateSourceBridgeReviewState
import tachiyomi.domain.taste.model.AlternateSourceBridgeState

class AlternateSourceReaderCoordinatorTest {

    private val getBridge = mockk<GetAlternateSourceBridge>()
    private val routeResolver = mockk<AlternateSourceReaderRouteResolver>()
    private val bridgeController = mockk<AlternateSourceBridgeController>()
    private val identityResolver = mockk<CrossSourceIdentityAuthorizationResolver>()
    private val identityController = mockk<CrossSourceIdentityDecisionController>()

    @Test
    fun `confirmed entry persists pending session before switching and commits afterward`() = runTest {
        val values = linkedMapOf<String, Any?>()
        val store = sessionStore(values)
        val state = bridgeState()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns state
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.ALTERNATE,
                "/chapter/alternate",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
        )
        var observedPending = false
        val coordinator = coordinator(store) { route ->
            val pending = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
            observedPending = pending.currentRoute.role == AlternateSourceReaderRouteRole.PRIMARY &&
                pending.pendingRouteFingerprint == AlternateSourceReaderRouteFingerprint.of(route)
            true
        }

        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.enter(entryRequest()),
        )

        val committed = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertTrue(observedPending)
        assertEquals(AlternateSourceReaderRouteRole.ALTERNATE, committed.currentRoute.role)
        assertEquals(null, committed.pendingRouteFingerprint)
        assertEquals(1, committed.transitionCount)
        assertEquals(AlternateSourceReaderPhase.ALTERNATE, coordinator.state.value.phase)
    }

    @Test
    fun `provisional entry requires explicit per-use confirmation and does not resolve or switch`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(
            mappingState = AlternateSourceBridgeMappingState.PROVISIONAL,
        )
        var switched = false
        val coordinator = coordinator(store) {
            switched = true
            true
        }

        assertEquals(
            AlternateSourceReaderCommandResult.RequiresProvisionalConfirmation(READER_TARGET_ID),
            coordinator.enter(entryRequest()),
        )
        assertFalse(switched)
        coVerify(exactly = 0) { routeResolver.resolve(any(), any(), any(), any()) }
        assertTrue(store.read() is AlternateSourceReaderStateCodec.DecodeResult.Invalid)
    }

    @Test
    fun `provisional uncertainty notice is acknowledged exactly once in stored session`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(
            mappingState = AlternateSourceBridgeMappingState.PROVISIONAL,
        )
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(
                readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
            )
        val coordinator = coordinator(store) { true }

        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.enter(entryRequest(), confirmProvisional = true),
        )
        assertTrue(coordinator.hasUnacknowledgedUncertainAlignment())
        assertTrue(coordinator.acknowledgeUncertainAlignmentNotice())
        assertFalse(coordinator.hasUnacknowledgedUncertainAlignment())
        assertFalse(coordinator.acknowledgeUncertainAlignmentNotice())
        val stored = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertTrue(stored.uncertainAlignmentNoticeEmitted)
    }

    @Test
    fun `selection requires explicit pair confirmation before creating a provisional mapping`() = runTest {
        val store = sessionStore()
        val created = bridgeState(
            bridgeUpdatedAt = 3_000L,
            mappingUpdatedAt = 3_000L,
            mappingState = AlternateSourceBridgeMappingState.PROVISIONAL,
        )
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returnsMany
            listOf(false, false, true, true)
        coEvery { identityController.mutate(any(), CrossSourceIdentityMutation.CONFIRM) } returns
            CrossSourceIdentityMutationResult.APPLIED
        coEvery { getBridge.await(readerBridgeKey()) } returnsMany listOf(null, created)
        coEvery { bridgeController.apply(any(), any()) } returns AlternateSourceBridgeMutationResult.APPLIED
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.ALTERNATE,
                "/chapter/alternate",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
        )
        var switchCount = 0
        val coordinator = coordinator(store) {
            switchCount++
            true
        }

        assertEquals(
            AlternateSourceReaderCommandResult.RequiresPairConfirmation,
            coordinator.select(entryRequest(), "/chapter/alternate"),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.PairConfirmed,
            coordinator.confirmPair(readerBridgeKey()),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate", confirmProvisional = true),
        )
        assertEquals(1, switchCount)
        coVerify(exactly = 1) { identityController.mutate(any(), CrossSourceIdentityMutation.CONFIRM) }
        coVerify(exactly = 1) { bridgeController.apply(any(), any()) }
    }

    @Test
    fun `selection reuses an exact confirmed mapping without mutating it`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.ALTERNATE,
                "/chapter/alternate",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
        )
        var switchCount = 0
        val coordinator = coordinator(store) {
            switchCount++
            true
        }

        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate"),
        )
        assertEquals(1, switchCount)
        coVerify(exactly = 0) { bridgeController.apply(any(), any()) }
    }

    @Test
    fun `stale restored session is cleared without guessing a return route`() = runTest {
        val values = linkedMapOf<String, Any?>()
        val store = sessionStore(values)
        store.write(readerSession())
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(bridgeUpdatedAt = 2_001L)
        val coordinator = coordinator(store) { error("must not switch") }

        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )
        assertTrue(store.read() is AlternateSourceReaderStateCodec.DecodeResult.Invalid)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
        coVerify(exactly = 0) { routeResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `partial restored state is cleared while an absent session remains a no-op`() = runTest {
        val values = linkedMapOf<String, Any?>(AlternateSourceReaderStateCodec.KEY_SCHEMA_VERSION to 2)
        val store = sessionStore(values)
        val coordinator = coordinator(store) { error("must not switch") }

        assertEquals(
            AlternateSourceReaderCommandResult.Invalid,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)

        val empty = coordinator(sessionStore()) { error("must not switch") }
        assertEquals(
            AlternateSourceReaderCommandResult.NoSession,
            empty.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )
        assertEquals(AlternateSourceReaderPhase.PRIMARY, empty.state.value.phase)
    }

    @Test
    fun `failed entry switch preserves the exact readable primary route and clears pending evidence`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(
                readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
            )
        val coordinator = coordinator(store) { false }

        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.enter(entryRequest()),
        )
        val retained = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(AlternateSourceReaderRouteRole.PRIMARY, retained.currentRoute.role)
        assertEquals("/chapter/primary", retained.currentRoute.chapterUrl)
        assertEquals(null, retained.pendingRouteFingerprint)
        assertEquals(1, retained.failedResolutionCount)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
    }

    @Test
    fun `entry cancellation clears pending state restores primary phase and propagates`() {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(
                readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
            )
        val coordinator = coordinator(store) { throw CancellationException("stop") }

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.enter(entryRequest()) }
        }
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.PRIMARY, coordinator.state.value.phase)
        assertEquals(null, coordinator.state.value.session)
    }

    @Test
    fun `dismissal during route replacement cannot resurrect persisted session state`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(
                readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
            )
        lateinit var coordinator: AlternateSourceReaderCoordinator
        coordinator = coordinator(store) {
            coordinator.dismiss()
            true
        }

        assertEquals(
            AlternateSourceReaderCommandResult.NoSession,
            coordinator.enter(entryRequest()),
        )
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.ENDED, coordinator.state.value.phase)
        assertEquals(null, coordinator.state.value.session)
    }

    @Test
    fun `route resolution retries one transient failure and no more`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returnsMany listOf(
            AlternateSourceReaderRouteResolver.Result.Failed,
            AlternateSourceReaderRouteResolver.Result.Resolved(
                readerRoute(AlternateSourceReaderRouteRole.ALTERNATE),
            ),
        )
        val coordinator = coordinator(store) { true }

        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.enter(entryRequest()),
        )
        coVerify(exactly = 2) { routeResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `pair confirmation conflict and ordinary failure create no bridge state`() = runTest {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns false
        coEvery { identityController.mutate(any(), CrossSourceIdentityMutation.CONFIRM) } returnsMany listOf(
            CrossSourceIdentityMutationResult.CONFLICT,
            CrossSourceIdentityMutationResult.FAILED,
        )
        val coordinator = coordinator(store) { error("must not switch") }

        assertEquals(AlternateSourceReaderCommandResult.Conflict, coordinator.confirmPair(readerBridgeKey()))
        assertEquals(AlternateSourceReaderCommandResult.Failed, coordinator.confirmPair(readerBridgeKey()))
        assertFalse(store.hasStoredState())
        coVerify(exactly = 0) { getBridge.await(any()) }
        coVerify(exactly = 0) { bridgeController.apply(any(), any()) }
    }

    @Test
    fun `correction conflict records no session mutation and preserves alternate reading`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        val state = bridgeState()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns state
        coEvery { bridgeController.apply(any(), any()) } returns AlternateSourceBridgeMutationResult.CONFLICT
        val coordinator = coordinator(store) { error("must not switch") }
        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )

        assertEquals(
            AlternateSourceReaderCommandResult.Conflict,
            coordinator.correct(
                readerRoute(
                    AlternateSourceReaderRouteRole.ALTERNATE,
                    chapterUrl = "/chapter/alternate-corrected",
                    chapterId = 23L,
                ),
            ),
        )
        val preserved = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(READER_TARGET_ID, preserved.activeTargetId)
        assertEquals(AlternateSourceReaderPhase.ALTERNATE, coordinator.state.value.phase)
    }

    @Test
    fun `automatic return fires only at exact boundary and keeps the pair after route commit`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        val state = bridgeState(automaticReturn = true, continuationUrl = "/chapter/primary-next")
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns state
        val destination = readerRoute(
            AlternateSourceReaderRouteRole.PRIMARY,
            chapterUrl = "/chapter/primary-next",
            chapterId = 12L,
        )
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/primary-next",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(destination)
        val switched = mutableListOf<AlternateSourceReaderRoute>()
        val coordinator = coordinator(store) {
            switched += it
            true
        }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))

        assertEquals(
            AlternateSourceReaderCommandResult.NotAtBoundary,
            coordinator.automaticReturn("/chapter/nearby"),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Returned,
            coordinator.automaticReturn("/chapter/alternate"),
        )
        assertEquals(listOf(destination), switched)
        assertTrue(store.read() is AlternateSourceReaderStateCodec.DecodeResult.Valid)
        assertEquals(AlternateSourceReaderPhase.PRIMARY, coordinator.state.value.phase)
    }

    @Test
    fun `automatic return stays silent for provisional mapping instead of showing route unavailable`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(
            automaticReturn = true,
            mappingState = AlternateSourceBridgeMappingState.PROVISIONAL,
        )
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))

        assertEquals(
            AlternateSourceReaderCommandResult.NotAtBoundary,
            coordinator.automaticReturn("/chapter/alternate"),
        )
        assertEquals(AlternateSourceReaderPhase.ALTERNATE, coordinator.state.value.phase)
        assertEquals(
            AlternateSourceReaderRouteRole.ALTERNATE,
            (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session.currentRoute.role,
        )
    }

    @Test
    fun `ordinary automatic return failure degrades and consumes the bounded retry`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        val state = bridgeState(automaticReturn = true)
        var bridgeReads = 0
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } answers {
            if (bridgeReads++ == 0) state else throw IllegalStateException("unavailable")
        }
        val coordinator = coordinator(store) { error("must not switch") }
        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )

        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.automaticReturn("/chapter/alternate"),
        )
        val retained = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(1, retained.failedResolutionCount)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
        assertEquals(AlternateSourceReaderRouteRole.ALTERNATE, retained.currentRoute.role)
    }

    @Test
    fun `manual return falls back to exact stored primary route when continuation is unavailable`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        val state = bridgeState(continuationUrl = "/chapter/unavailable")
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns state
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/unavailable",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.ChapterUnavailable
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/primary",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.PRIMARY),
        )
        val coordinator = coordinator(store) { true }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))

        assertEquals(
            AlternateSourceReaderCommandResult.Returned,
            coordinator.manualReturn(preferContinuation = true),
        )
        assertTrue(store.read() is AlternateSourceReaderStateCodec.DecodeResult.Valid)
    }

    @Test
    fun `manual return ignores bridge continuation when identity is no longer confirmed`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returnsMany listOf(true, false)
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(
            continuationUrl = "/chapter/bridge-continuation",
        )
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/primary",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.PRIMARY),
        )

        val coordinator = coordinator(store) { true }
        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )

        assertEquals(
            AlternateSourceReaderCommandResult.Returned,
            coordinator.manualReturn(preferContinuation = true),
        )
        coVerify(exactly = 0) {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/bridge-continuation",
                0,
            )
        }
    }

    @Test
    fun `automatic and manual alternate returns stop when identity is no longer confirmed`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returnsMany listOf(true, false, false)
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery {
            routeResolver.resolve(
                readerBridgeKey(),
                AlternateSourceReaderRouteRole.PRIMARY,
                "/chapter/primary",
                0,
            )
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(
            readerRoute(AlternateSourceReaderRouteRole.PRIMARY),
        )
        val coordinator = coordinator(store) { true }
        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )

        assertEquals(
            AlternateSourceReaderCommandResult.Conflict,
            coordinator.automaticReturn("/chapter/alternate"),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Returned,
            coordinator.manualReturnToChapter(
                readerBridgeKey(),
                READER_SESSION_ID,
                "/chapter/alternate",
                "/chapter/primary",
            ),
        )
    }

    @Test
    fun `restored primary session remains recoverable when identity is no longer confirmed`() = runTest {
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        val store = sessionStore().also { it.write(readerSession(currentRoute = primary)) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns false
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        val coordinator = coordinator(store) { error("must not switch routes") }

        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(primary),
        )
        assertEquals(primary, coordinator.state.value.session?.currentRoute)
    }

    @Test
    fun `restored alternate session is rejected when identity is no longer confirmed`() = runTest {
        val alternate = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        val store = sessionStore().also { it.write(readerSession(currentRoute = alternate)) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns false
        val coordinator = coordinator(store) { error("must not switch a rejected session") }

        assertEquals(
            AlternateSourceReaderCommandResult.Invalid,
            coordinator.restore(alternate),
        )
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
    }

    @Test
    fun `continuing to an alternate route requires a current confirmed identity`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returnsMany listOf(true, false)
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        val coordinator = coordinator(store) { error("must not switch without confirmed identity") }

        assertEquals(
            AlternateSourceReaderCommandResult.Restored,
            coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Conflict,
            coordinator.continueAlternate(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)),
        )
    }

    @Test
    fun `cancellation and fatal identity failures propagate`() {
        val store = sessionStore()
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } throws CancellationException("stop")
        val cancelled = coordinator(store) { true }
        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { cancelled.confirmPair(readerBridgeKey()) }
        }

        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } throws AssertionError("fatal")
        val fatal = coordinator(store) { true }
        assertThrows(AssertionError::class.java) {
            kotlinx.coroutines.runBlocking { fatal.confirmPair(readerBridgeKey()) }
        }
    }

    @Test
    fun `chosen return chapter is used after reading ahead and another switch remains available`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        val destination = readerRoute(AlternateSourceReaderRouteRole.PRIMARY, chapterUrl = "/chapter/primary-ahead", chapterId = 15L)
        coEvery {
            routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, destination.chapterUrl, 0)
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(destination)
        coEvery {
            routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.ALTERNATE, "/chapter/alternate", 0)
        } returns AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        val switched = mutableListOf<AlternateSourceReaderRoute>()
        val coordinator = coordinator(store) {
            switched += it
            true
        }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.manualReturnToChapter(readerBridgeKey(), "old-session", "/chapter/alternate", destination.chapterUrl))
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/old-chapter", destination.chapterUrl))
        assertEquals(AlternateSourceReaderCommandResult.Returned, coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/chapter/alternate", destination.chapterUrl))
        assertEquals(destination, switched.single())
        assertTrue(store.hasStoredState())
        assertEquals(
            AlternateSourceReaderCommandResult.Returned,
            coordinator.manualReturnToChapter(
                readerBridgeKey(),
                READER_SESSION_ID,
                destination.chapterUrl,
                "/chapter/alternate",
            ),
        )
        assertEquals(2, switched.size)
    }

    @Test
    fun `unavailable chosen return chapter leaves alternate reading intact`() = runTest {
        val store = sessionStore().also { it.write(readerSession()) }
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns AlternateSourceReaderRouteResolver.Result.ChapterUnavailable
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        assertEquals(AlternateSourceReaderCommandResult.Unavailable, coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/chapter/alternate", "/missing"))
        assertEquals(AlternateSourceReaderPhase.ALTERNATE, coordinator.state.value.phase)
        assertTrue(store.hasStoredState())
    }

    @Test
    fun `retry selection clears only admitted alternate source after persistence before switching`() = runTest {
        val store = sessionStore()
        stubConfirmedEntry()
        val destination = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        val events = mutableListOf<String>()
        val sourceIds = listOf(readerBridgeKey().primary.source, destination.record.source, 9_019L)
        sourceIds.forEach(::recordRetryFailure)
        val primaryFailure = SourceRuntimeFailureRegistry.get(sourceIds[0])
        val sentinelFailure = SourceRuntimeFailureRegistry.get(sourceIds[2])
        try {
            val coordinator = coordinator(store) { route ->
                events += "switch"
                assertEquals(destination, route)
                assertEquals(null, SourceRuntimeFailureRegistry.get(route.record.source))
                true
            }
            assertEquals(
                AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
                coordinator.select(entryRequest(), destination.chapterUrl) { route ->
                    val pending = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
                    assertEquals(AlternateSourceReaderRouteRole.PRIMARY, pending.currentRoute.role)
                    assertEquals(AlternateSourceReaderRouteFingerprint.of(route), pending.pendingRouteFingerprint)
                    assertEquals(destination, route)
                    events += "retry"
                    SourceRuntimeFailureRegistry.clear(route.record.source)
                },
            )
            assertEquals(listOf("retry", "switch"), events)
            assertEquals(primaryFailure, SourceRuntimeFailureRegistry.get(sourceIds[0]))
            assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(sourceIds[2]))
            assertEquals(destination, coordinator.state.value.session?.currentRoute)
            assertEquals(null, coordinator.state.value.session?.pendingRouteFingerprint)
            coVerify(exactly = 0) { bridgeController.apply(any(), any()) }
        } finally {
            sourceIds.forEach(SourceRuntimeFailureRegistry::clear)
        }
    }

    @Test
    fun `retry return effect follows actual primary and alternate destinations in both directions`() = runTest {
        val alternate = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY, "/chapter/primary-ahead", 15L)
        val store = sessionStore().also { it.write(readerSession()) }
        stubConfirmedEntry()
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, primary.chapterUrl, 0) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(primary)
        val events = mutableListOf<Pair<String, Long>>()
        val sourceIds = listOf(primary.record.source, alternate.record.source, 9_020L)
        sourceIds.forEach(::recordRetryFailure)
        val sentinelFailure = SourceRuntimeFailureRegistry.get(sourceIds[2])
        try {
            val coordinator = coordinator(store) { route ->
                events += "switch" to route.record.source
                assertEquals(null, SourceRuntimeFailureRegistry.get(route.record.source))
                true
            }
            assertEquals(AlternateSourceReaderCommandResult.Restored, coordinator.restore(alternate))
            for (destination in listOf(primary, alternate)) {
                val origin = requireNotNull(coordinator.state.value.session).currentRoute
                val originFailure = SourceRuntimeFailureRegistry.get(origin.record.source)
                recordRetryFailure(destination.record.source)
                assertEquals(
                    AlternateSourceReaderCommandResult.Returned,
                    coordinator.manualReturnToChapter(
                        readerBridgeKey(),
                        READER_SESSION_ID,
                        origin.chapterUrl,
                        destination.chapterUrl,
                    ) { route ->
                        val pending = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
                        assertEquals(origin, pending.currentRoute)
                        assertEquals(AlternateSourceReaderRouteFingerprint.of(destination), pending.pendingRouteFingerprint)
                        assertEquals(destination, route)
                        events += "retry" to route.record.source
                        SourceRuntimeFailureRegistry.clear(route.record.source)
                    },
                )
                assertEquals(originFailure, SourceRuntimeFailureRegistry.get(origin.record.source))
                assertEquals(sentinelFailure, SourceRuntimeFailureRegistry.get(sourceIds[2]))
                assertEquals(destination, coordinator.state.value.session?.currentRoute)
            }
            assertEquals(
                listOf("retry" to primary.record.source, "switch" to primary.record.source, "retry" to alternate.record.source, "switch" to alternate.record.source),
                events,
            )
        } finally {
            sourceIds.forEach(SourceRuntimeFailureRegistry::clear)
        }
    }

    @Test
    fun `ordinary selection and chosen return retain source suppression without retry callback`() = runTest {
        val store = sessionStore()
        stubConfirmedEntry()
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, primary.chapterUrl, 0) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(primary)
        val sourceIds = listOf(primary.record.source, readerBridgeKey().alternate.source)
        sourceIds.forEach(::recordRetryFailure)
        val failures = sourceIds.map(SourceRuntimeFailureRegistry::get)
        try {
            val coordinator = coordinator(store) { true }
            assertEquals(AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID), coordinator.select(entryRequest(), "/chapter/alternate"))
            val session = requireNotNull(coordinator.state.value.session)
            assertEquals(
                AlternateSourceReaderCommandResult.Returned,
                coordinator.manualReturnToChapter(readerBridgeKey(), session.sessionId, session.currentRoute.chapterUrl, primary.chapterUrl),
            )
            assertEquals(failures, sourceIds.map(SourceRuntimeFailureRegistry::get))
        } finally {
            sourceIds.forEach(SourceRuntimeFailureRegistry::clear)
        }
    }

    @Test
    fun `invalid unconfirmed and sessionless retry commands never run admitted effect`() = runTest {
        val coordinator = coordinator(sessionStore()) { error("must not switch") }
        val effects = mutableListOf<AlternateSourceReaderRoute>()
        val effect: suspend (AlternateSourceReaderRoute) -> Unit = { effects += it }
        assertEquals(
            AlternateSourceReaderCommandResult.Invalid,
            coordinator.select(entryRequest().copy(precedingPrimaryChapterUrl = "/stale"), "/chapter/alternate", effect),
        )
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns false
        assertEquals(
            AlternateSourceReaderCommandResult.RequiresPairConfirmation,
            coordinator.select(entryRequest(), "/chapter/alternate", effect),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.NoSession,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/chapter/alternate", "/chapter/primary", effect),
        )
        assertTrue(effects.isEmpty())
        coVerify(exactly = 0) { routeResolver.resolve(any(), any(), any(), any()) }
        coVerify(exactly = 0) { bridgeController.apply(any(), any()) }
    }

    @Test
    fun `provisional selection refusal never runs admitted retry effect`() = runTest {
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returnsMany listOf(null, bridgeState(mappingState = AlternateSourceBridgeMappingState.PROVISIONAL))
        coEvery { bridgeController.apply(any(), any()) } returns AlternateSourceBridgeMutationResult.APPLIED
        var effects = 0
        val store = sessionStore()
        val coordinator = coordinator(store) { error("must not switch") }
        assertEquals(
            AlternateSourceReaderCommandResult.RequiresProvisionalConfirmation(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        assertEquals(0, effects)
        assertFalse(store.hasStoredState())
        coVerify(exactly = 0) { routeResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `stale return identity and unconfirmed alternate destination never run retry effect`() = runTest {
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        val store = sessionStore().also { it.write(readerSession(currentRoute = primary)) }
        stubConfirmedEntry()
        val coordinator = coordinator(store) { error("must not switch") }
        assertEquals(AlternateSourceReaderCommandResult.Restored, coordinator.restore(primary))
        val effect: suspend (AlternateSourceReaderRoute) -> Unit = { error("must not reset") }
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.manualReturnToChapter(readerBridgeKey().copy(primary = readerBridgeKey().alternate), READER_SESSION_ID, primary.chapterUrl, "/chapter/alternate", effect),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.manualReturnToChapter(readerBridgeKey(), "old-session", primary.chapterUrl, "/chapter/alternate", effect),
        )
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/old-chapter", "/chapter/alternate", effect),
        )
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns false
        assertEquals(
            AlternateSourceReaderCommandResult.Conflict,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, primary.chapterUrl, "/chapter/alternate", effect),
        )
        assertEquals(primary, coordinator.state.value.session?.currentRoute)
        coVerify(exactly = 0) { routeResolver.resolve(any(), any(), any(), any()) }
    }

    @Test
    fun `unavailable and invalid destinations never run admitted retry effect`() = runTest {
        stubConfirmedEntry()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.ChapterUnavailable
        val coordinator = coordinator(sessionStore()) { error("must not switch") }
        val effect: suspend (AlternateSourceReaderRoute) -> Unit = { error("must not reset") }
        assertEquals(AlternateSourceReaderCommandResult.Unavailable, coordinator.select(entryRequest(), "/chapter/alternate", effect))
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE).copy(chapterId = 0L))
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), "/chapter/alternate", effect))
        assertEquals(null, coordinator.state.value.session?.pendingRouteFingerprint)
    }

    @Test
    fun `rejected entry and same route return transitions never run retry effect`() = runTest {
        val alternate = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        val store = sessionStore().also { it.write(readerSession()) }
        stubConfirmedEntry()
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(alternate)
        val effect: suspend (AlternateSourceReaderRoute) -> Unit = { error("must not reset") }
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), alternate.chapterUrl, effect))
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(alternate)
        assertEquals(
            AlternateSourceReaderCommandResult.LoopRejected,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, alternate.chapterUrl, "/chapter/primary", effect),
        )
        assertEquals(alternate, coordinator.state.value.session?.currentRoute)
    }

    @Test
    fun `pending persistence failure prevents admitted retry effect and switch`() = runTest {
        stubConfirmedEntry()
        val store = AlternateSourceReaderSessionStore(
            get = { null },
            set = { _, _ -> throw IllegalStateException("controlled persistence failure") },
            remove = {},
        )
        val coordinator = coordinator(store) { error("must not switch") }
        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.select(entryRequest(), "/chapter/alternate") { error("must not reset") },
        )
        assertEquals(null, coordinator.state.value.session)
    }

    @Test
    fun `return pending persistence failure prevents retry effect and preserves current route`() = runTest {
        stubConfirmedEntry()
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, primary.chapterUrl, 0) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(primary)
        val values = linkedMapOf<String, Any?>()
        var failNextWrite = false
        val store = AlternateSourceReaderSessionStore(
            get = values::get,
            set = { key, value ->
                if (failNextWrite) {
                    failNextWrite = false
                    throw IllegalStateException("controlled return persistence failure")
                }
                values[key] = value
            },
            remove = { values.remove(it) },
        )
        store.write(readerSession())
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        failNextWrite = true
        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, "/chapter/alternate", primary.chapterUrl) {
                error("must not reset")
            },
        )
        val retained = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE), retained.currentRoute)
        assertEquals(null, retained.pendingRouteFingerprint)
    }

    @Test
    fun `cancelled coroutine returning a destination cannot run admitted retry effect`() = runTest {
        stubConfirmedEntry()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } coAnswers {
            currentCoroutineContext().cancel()
            AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        }
        val store = sessionStore()
        val coordinator = coordinator(store) { error("must not switch") }
        var effects = 0
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ }
        }
        command.join()
        assertTrue(command.isCancelled)
        assertEquals(0, effects)
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.PRIMARY, coordinator.state.value.phase)
    }

    @Test
    fun `cancellation during destination resolution never reaches retry effect`() = runTest {
        stubConfirmedEntry()
        val resolving = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } coAnswers {
            resolving.complete(Unit)
            release.await()
            AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        }
        val store = sessionStore()
        val coordinator = coordinator(store) { error("must not switch") }
        var effects = 0
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ }
        }
        resolving.await()
        command.cancelAndJoin()
        release.complete(Unit)
        assertEquals(0, effects)
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.PRIMARY, coordinator.state.value.phase)
    }

    @Test
    fun `dismissal during destination resolution never reaches retry effect or restores session`() = runTest {
        stubConfirmedEntry()
        lateinit var coordinator: AlternateSourceReaderCoordinator
        coEvery { routeResolver.resolve(any(), any(), any(), any()) } coAnswers {
            coordinator.dismiss()
            AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
        }
        val store = sessionStore()
        coordinator = coordinator(store) { error("must not switch") }
        var effects = 0
        assertEquals(
            AlternateSourceReaderCommandResult.NoSession,
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        assertEquals(0, effects)
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.ENDED, coordinator.state.value.phase)
    }

    @Test
    fun `cancelled suspended retry effect cannot switch and next generation alone is admitted`() = runTest {
        stubConfirmedEntry()
        val store = sessionStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        var switches = 0
        val coordinator = coordinator(store) {
            switches++
            true
        }
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.select(entryRequest(), "/chapter/alternate") {
                started.complete(Unit)
                release.await()
                effects++
            }
        }
        started.await()
        val oldGeneration = coordinator.state.value.generation
        command.cancelAndJoin()
        release.complete(Unit)
        assertEquals(0, effects)
        assertEquals(0, switches)
        assertFalse(store.hasStoredState())
        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        assertTrue(coordinator.state.value.generation > oldGeneration)
        assertEquals(1, effects)
        assertEquals(1, switches)
    }

    @Test
    fun `dismissal while retry effect suspends prevents subsequent switch and persisted resurrection`() = runTest {
        stubConfirmedEntry()
        val store = sessionStore()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = coordinator(store) { error("must not switch") }
        var result: AlternateSourceReaderCommandResult? = null
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            result = coordinator.select(entryRequest(), "/chapter/alternate") {
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        coordinator.dismiss()
        release.complete(Unit)
        command.join()
        assertEquals(AlternateSourceReaderCommandResult.NoSession, result)
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.ENDED, coordinator.state.value.phase)
        assertEquals(null, coordinator.state.value.session)
    }

    @Test
    fun `cancelled suspended return retry effect restores alternate route without switching`() = runTest {
        stubConfirmedEntry()
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        val alternate = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, primary.chapterUrl, 0) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(primary)
        val store = sessionStore().also { it.write(readerSession()) }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(alternate)
        val command = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, alternate.chapterUrl, primary.chapterUrl) {
                started.complete(Unit)
                release.await()
                effects++
            }
        }
        started.await()
        command.cancelAndJoin()
        release.complete(Unit)
        assertEquals(0, effects)
        val retained = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(alternate, retained.currentRoute)
        assertEquals(null, retained.pendingRouteFingerprint)
        assertEquals(AlternateSourceReaderPhase.ALTERNATE, coordinator.state.value.phase)
    }

    @Test
    fun `dismissal during return resolution never reaches retry effect`() = runTest {
        stubConfirmedEntry()
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        val alternate = readerRoute(AlternateSourceReaderRouteRole.ALTERNATE)
        val store = sessionStore().also { it.write(readerSession()) }
        val coordinator = coordinator(store) { error("must not switch") }
        coordinator.restore(alternate)
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.PRIMARY, primary.chapterUrl, 0) } coAnswers {
            coordinator.dismiss()
            AlternateSourceReaderRouteResolver.Result.Resolved(primary)
        }
        assertEquals(
            AlternateSourceReaderCommandResult.NoSession,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, alternate.chapterUrl, primary.chapterUrl) {
                error("must not reset")
            },
        )
        assertFalse(store.hasStoredState())
        assertEquals(AlternateSourceReaderPhase.ENDED, coordinator.state.value.phase)
    }

    @Test
    fun `explicit retry admits only matching failed initial entry and preserves pending failure count`() = runTest {
        stubConfirmedEntry()
        val store = sessionStore()
        var switches = 0
        val coordinator = coordinator(store) {
            switches++
            switches > 1
        }
        assertEquals(AlternateSourceReaderCommandResult.Failed, coordinator.select(entryRequest(), "/chapter/alternate"))
        val failed = requireNotNull(coordinator.state.value.session)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
        assertEquals(readerRoute(AlternateSourceReaderRouteRole.PRIMARY), failed.currentRoute)
        assertEquals(0, failed.transitionCount)
        assertEquals(1, failed.failedResolutionCount)
        var effects = 0
        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate") { destination ->
                val pending = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
                assertEquals(failed.sessionId, pending.sessionId)
                assertEquals(1, pending.failedResolutionCount)
                assertEquals(failed.currentRoute, pending.currentRoute)
                assertEquals(AlternateSourceReaderRouteFingerprint.of(destination), pending.pendingRouteFingerprint)
                effects++
            },
        )
        assertEquals(1, effects)
        assertEquals(2, switches)
        val committed = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(failed.sessionId, committed.sessionId)
        assertEquals(0, committed.failedResolutionCount)
        assertEquals(1, committed.transitionCount)
        assertEquals(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE), committed.currentRoute)
        coVerify(exactly = 0) { bridgeController.apply(any(), any()) }
    }

    @Test
    fun `failed initial entry retry rejects ordinary changed and exhausted attempts without effects`() = runTest {
        stubConfirmedEntry()
        val store = sessionStore()
        var switches = 0
        val coordinator = coordinator(store) {
            switches++
            false
        }
        assertEquals(AlternateSourceReaderCommandResult.Failed, coordinator.select(entryRequest(), "/chapter/alternate"))
        val failed = requireNotNull(coordinator.state.value.session)
        val noEffect: suspend (AlternateSourceReaderRoute) -> Unit = { error("must not reset") }
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), "/chapter/alternate"))
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.select(entryRequest().copy(primaryRoute = failed.currentRoute.copy(pageIndex = 1)), "/chapter/alternate", noEffect),
        )
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(mappingUpdatedAt = 2_101L)
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), "/chapter/alternate", noEffect))
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState(bridgeUpdatedAt = 2_001L)
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), "/chapter/alternate", noEffect))
        assertEquals(failed, coordinator.state.value.session)
        assertEquals(1, switches)

        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        var effects = 0
        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        val exhausted = requireNotNull(coordinator.state.value.session)
        assertEquals(failed.sessionId, exhausted.sessionId)
        assertEquals(AlternateSourceReaderSession.MAX_FAILED_RESOLUTIONS, exhausted.failedResolutionCount)
        assertEquals(AlternateSourceReaderFailureReason.RETRY_EXHAUSTED, coordinator.state.value.failureReason)
        assertEquals(AlternateSourceReaderCommandResult.Stale, coordinator.select(entryRequest(), "/chapter/alternate", noEffect))
        assertEquals(1, effects)
        assertEquals(2, switches)
        assertEquals(exhausted, coordinator.state.value.session)
    }

    @Test
    fun `degraded primary session after previous transition is not admitted as failed initial entry`() = runTest {
        stubConfirmedEntry()
        val primary = readerRoute(AlternateSourceReaderRouteRole.PRIMARY)
        val store = sessionStore().also { it.write(readerSession(currentRoute = primary)) }
        var switches = 0
        val coordinator = coordinator(store) {
            switches++
            false
        }
        assertEquals(AlternateSourceReaderCommandResult.Restored, coordinator.restore(primary))
        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            coordinator.manualReturnToChapter(readerBridgeKey(), READER_SESSION_ID, primary.chapterUrl, "/chapter/alternate"),
        )
        val failedReturn = requireNotNull(coordinator.state.value.session)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
        assertEquals(1, failedReturn.transitionCount)
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            coordinator.select(entryRequest(), "/chapter/alternate") { error("must not reset") },
        )
        assertEquals(failedReturn, coordinator.state.value.session)
        assertEquals(1, switches)
    }

    @Test
    fun `cancelled failed entry retry restores safe primary session and its failure allowance`() = runTest {
        stubConfirmedEntry()
        val store = sessionStore()
        var switches = 0
        val coordinator = coordinator(store) {
            switches++
            switches > 1
        }
        assertEquals(AlternateSourceReaderCommandResult.Failed, coordinator.select(entryRequest(), "/chapter/alternate"))
        val failed = requireNotNull(coordinator.state.value.session)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var effects = 0
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.select(entryRequest(), "/chapter/alternate") {
                started.complete(Unit)
                release.await()
                effects++
            }
        }
        started.await()
        val retryGeneration = coordinator.state.value.generation
        retry.cancelAndJoin()
        release.complete(Unit)
        val restored = (store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertEquals(failed, restored)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, coordinator.state.value.phase)
        assertEquals(1, restored.failedResolutionCount)
        assertEquals(0, effects)
        assertEquals(1, switches)
        assertEquals(
            AlternateSourceReaderCommandResult.Entered(READER_TARGET_ID),
            coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        assertTrue(coordinator.state.value.generation > retryGeneration)
        assertEquals(1, effects)
        assertEquals(2, switches)
        assertEquals(0, coordinator.state.value.session?.failedResolutionCount)
    }

    @Test
    fun `failed entry retry survives ordinary pending and rollback write failures without effects`() = runTest {
        val fixture = failedEntryWithWriteFaults()
        fixture.writeFailures += IllegalStateException("pending write")
        fixture.writeFailures += IllegalStateException("rollback write")
        var effects = 0
        assertEquals(
            AlternateSourceReaderCommandResult.Failed,
            fixture.coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        val failed = requireNotNull(fixture.coordinator.state.value.session)
        assertEquals(fixture.safeSession.sessionId, failed.sessionId)
        assertEquals(fixture.safeSession.currentRoute, failed.currentRoute)
        assertEquals(null, failed.pendingRouteFingerprint)
        assertEquals(AlternateSourceReaderSession.MAX_FAILED_RESOLUTIONS, failed.failedResolutionCount)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, fixture.coordinator.state.value.phase)
        assertEquals(AlternateSourceReaderFailureReason.RETRY_EXHAUSTED, fixture.coordinator.state.value.failureReason)
        assertEquals(
            AlternateSourceReaderCommandResult.Stale,
            fixture.coordinator.select(entryRequest(), "/chapter/alternate") { effects++ },
        )
        assertEquals(0, effects)
        assertEquals(1, fixture.switches)
        assertEquals(2, fixture.failedWrites)
        assertEquals(fixture.safeSession, (fixture.store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session)
    }

    @Test
    fun `cancelled failed entry pending write preserves original cancellation despite rollback failure`() = runTest {
        val fixture = failedEntryWithWriteFaults()
        val interruption = CancellationException("pending write cancelled")
        val rollback = IllegalStateException("rollback write")
        fixture.writeFailures += interruption
        fixture.writeFailures += rollback
        var effects = 0
        val observed = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.coordinator.select(entryRequest(), "/chapter/alternate") { effects++ }
            }
        }
        assertSame(interruption, observed)
        assertTrue(observed.suppressed.any { it === rollback })
        assertSafeFailedEntry(fixture)
        assertEquals(0, effects)
        assertEquals(2, fixture.failedWrites)
    }

    @Test
    fun `fatal failed entry pending write preserves original error despite rollback failure`() = runTest {
        val fixture = failedEntryWithWriteFaults()
        val interruption = AssertionError("pending write fatal")
        val rollback = IllegalStateException("rollback write")
        fixture.writeFailures += interruption
        fixture.writeFailures += rollback
        var effects = 0
        val observed = assertThrows(AssertionError::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.coordinator.select(entryRequest(), "/chapter/alternate") { effects++ }
            }
        }
        assertSame(interruption, observed)
        assertTrue(observed.suppressed.any { it === rollback })
        assertSafeFailedEntry(fixture)
        assertEquals(0, effects)
        assertEquals(2, fixture.failedWrites)
    }

    @Test
    fun `original cancellation survives cancellation or fatal rollback failure`() = runTest {
        for (rollback in listOf(CancellationException("rollback cancelled"), AssertionError("rollback fatal"))) {
            val fixture = failedEntryWithWriteFaults()
            val interruption = CancellationException("pending write cancelled")
            fixture.writeFailures += interruption
            fixture.writeFailures += rollback
            val observed = assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    fixture.coordinator.select(entryRequest(), "/chapter/alternate") { error("must not reset") }
                }
            }
            assertSame(interruption, observed)
            assertTrue(observed.suppressed.any { it === rollback })
            assertSafeFailedEntry(fixture)
            assertEquals(2, fixture.failedWrites)
        }
    }

    @Test
    fun `ordinary pending failure does not swallow cancellation or fatal rollback failure`() = runTest {
        for (rollback in listOf(CancellationException("rollback cancelled"), AssertionError("rollback fatal"))) {
            val fixture = failedEntryWithWriteFaults()
            fixture.writeFailures += IllegalStateException("pending write")
            fixture.writeFailures += rollback
            val observed = assertThrows(rollback.javaClass) {
                kotlinx.coroutines.runBlocking {
                    fixture.coordinator.select(entryRequest(), "/chapter/alternate") { error("must not reset") }
                }
            }
            assertSame(rollback, observed)
            val failed = requireNotNull(fixture.coordinator.state.value.session)
            assertEquals(fixture.safeSession.currentRoute, failed.currentRoute)
            assertEquals(fixture.safeSession.sessionId, failed.sessionId)
            assertEquals(null, failed.pendingRouteFingerprint)
            assertEquals(AlternateSourceReaderSession.MAX_FAILED_RESOLUTIONS, failed.failedResolutionCount)
            assertEquals(AlternateSourceReaderPhase.DEGRADED, fixture.coordinator.state.value.phase)
            assertEquals(AlternateSourceReaderFailureReason.RETRY_EXHAUSTED, fixture.coordinator.state.value.failureReason)
            assertEquals(1, fixture.switches)
            assertEquals(2, fixture.failedWrites)
        }
    }

    @Test
    fun `cancelled admitted failed entry clears in memory pending state when rollback write fails`() = runTest {
        val fixture = failedEntryWithWriteFaults()
        val interruption = CancellationException("cancel before reset")
        val rollback = IllegalStateException("rollback write")
        var effects = 0
        val observed = assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking {
                fixture.coordinator.select(entryRequest(), "/chapter/alternate") { destination ->
                    val pending = (fixture.store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
                    assertEquals(AlternateSourceReaderRouteFingerprint.of(destination), pending.pendingRouteFingerprint)
                    fixture.writeFailures += rollback
                    suspend fun cancelBeforeReset() {
                        throw interruption
                    }
                    cancelBeforeReset()
                    effects++
                }
            }
        }
        assertSame(interruption, observed)
        assertTrue(observed.suppressed.any { it === rollback })
        assertSafeFailedEntry(fixture)
        assertEquals(0, effects)
        assertEquals(1, fixture.failedWrites)
        val stored = (fixture.store.read() as AlternateSourceReaderStateCodec.DecodeResult.Valid).session
        assertTrue(stored.pendingRouteFingerprint != null)
    }

    @Test
    fun `dismissed failed entry preserves cancellation or fatal error when cleanup clear fails`() = runTest {
        for (interruption in listOf(CancellationException("entry cancelled"), AssertionError("entry fatal"))) {
            val fixture = failedEntryWithWriteFaults()
            val rollback = IllegalStateException("dismissal clear")
            val finalCleanup = IllegalStateException("interruption clear")
            val observed = assertThrows(interruption.javaClass) {
                kotlinx.coroutines.runBlocking {
                    fixture.coordinator.select(entryRequest(), "/chapter/alternate") {
                        fixture.clearFailures += rollback
                        fixture.clearFailures += finalCleanup
                        fixture.coordinator.dismiss()
                        assertEquals(AlternateSourceReaderPhase.ENDED, fixture.coordinator.state.value.phase)
                        assertEquals(null, fixture.coordinator.state.value.session)
                        throw interruption
                    }
                }
            }
            assertSame(interruption, observed)
            assertTrue(observed.suppressed.any { it === finalCleanup })
            assertEquals(AlternateSourceReaderPhase.ENDED, fixture.coordinator.state.value.phase)
            assertEquals(null, fixture.coordinator.state.value.session)
            assertEquals(1, fixture.switches)
            assertEquals(2, fixture.failedClears)
        }
    }

    private class FailedEntryWriteFaultFixture {
        val values = linkedMapOf<String, Any?>()
        val writeFailures = mutableListOf<Throwable>()
        val clearFailures = mutableListOf<Throwable>()
        var failedWrites = 0
        var failedClears = 0
        var switches = 0
        lateinit var coordinator: AlternateSourceReaderCoordinator
        lateinit var safeSession: AlternateSourceReaderSession
        val store = AlternateSourceReaderSessionStore(
            get = values::get,
            set = { key, value ->
                if (key == AlternateSourceReaderStateCodec.KEY_SCHEMA_VERSION && writeFailures.isNotEmpty()) {
                    failedWrites++
                    throw writeFailures.removeAt(0)
                }
                values[key] = value
            },
            remove = {
                if (clearFailures.isNotEmpty()) {
                    failedClears++
                    throw clearFailures.removeAt(0)
                }
                values.remove(it)
            },
        )
    }

    private suspend fun failedEntryWithWriteFaults(): FailedEntryWriteFaultFixture {
        stubConfirmedEntry()
        val fixture = FailedEntryWriteFaultFixture()
        fixture.coordinator = coordinator(fixture.store) {
            fixture.switches++
            false
        }
        assertEquals(AlternateSourceReaderCommandResult.Failed, fixture.coordinator.select(entryRequest(), "/chapter/alternate"))
        fixture.safeSession = requireNotNull(fixture.coordinator.state.value.session)
        assertEquals(1, fixture.safeSession.failedResolutionCount)
        assertEquals(null, fixture.safeSession.pendingRouteFingerprint)
        assertEquals(readerRoute(AlternateSourceReaderRouteRole.PRIMARY), fixture.safeSession.currentRoute)
        return fixture
    }

    private fun assertSafeFailedEntry(fixture: FailedEntryWriteFaultFixture) {
        assertEquals(fixture.safeSession, fixture.coordinator.state.value.session)
        assertEquals(AlternateSourceReaderPhase.DEGRADED, fixture.coordinator.state.value.phase)
        assertEquals(AlternateSourceReaderFailureReason.ROUTE_UNAVAILABLE, fixture.coordinator.state.value.failureReason)
        assertEquals(1, fixture.switches)
    }

    private fun stubConfirmedEntry() {
        coEvery { identityResolver.isConfirmed(any(), any(), any(), any()) } returns true
        coEvery { getBridge.await(readerBridgeKey()) } returns bridgeState()
        coEvery { routeResolver.resolve(readerBridgeKey(), AlternateSourceReaderRouteRole.ALTERNATE, "/chapter/alternate", 0) } returns
            AlternateSourceReaderRouteResolver.Result.Resolved(readerRoute(AlternateSourceReaderRouteRole.ALTERNATE))
    }

    private fun recordRetryFailure(sourceId: Long) {
        SourceRuntimeFailureRegistry.record(
            SourceRuntimeFailure(
                sourceId = sourceId,
                sourceName = "Retry fixture $sourceId",
                sourceLang = "en",
                operation = SourceRuntimeOperation.Search,
                kind = SourceRuntimeFailureKind.Internal,
                throwable = IllegalStateException("controlled prior failure"),
            ),
        )
    }

    private fun coordinator(
        store: AlternateSourceReaderSessionStore,
        switcher: suspend (AlternateSourceReaderRoute) -> Boolean,
    ) = AlternateSourceReaderCoordinator(
        getBridge = getBridge,
        routeResolver = routeResolver,
        bridgeController = bridgeController,
        identityResolver = identityResolver,
        identityController = identityController,
        sessionStore = store,
        routeSwitcher = AlternateSourceReaderRouteSwitcher(switcher),
        clock = { 3_000L },
        targetId = { READER_TARGET_ID },
    )

    private fun entryRequest() = AlternateSourceReaderEntryRequest(
        bridgeKey = readerBridgeKey(),
        primaryRoute = readerRoute(AlternateSourceReaderRouteRole.PRIMARY),
        precedingPrimaryChapterUrl = "/chapter/primary",
        followingPrimaryChapterUrl = "/chapter/after",
    )

    private fun sessionStore(
        values: MutableMap<String, Any?> = linkedMapOf(),
    ) = AlternateSourceReaderSessionStore(
        get = values::get,
        set = values::set,
        remove = { values.remove(it) },
    )

    private fun bridgeState(
        bridgeUpdatedAt: Long = 2_000L,
        mappingUpdatedAt: Long = 2_100L,
        mappingState: AlternateSourceBridgeMappingState = AlternateSourceBridgeMappingState.CONFIRMED,
        automaticReturn: Boolean = false,
        continuationUrl: String = "/chapter/after",
    ) = AlternateSourceBridgeState(
        bridge = AlternateSourceBridge(
            key = readerBridgeKey(),
            version = AlternateSourceBridgePolicy.CURRENT_VERSION,
            continuationPrimaryChapterUrl = continuationUrl,
            returnAfterAlternateChapterUrl = "/chapter/alternate",
            automaticReturn = automaticReturn,
            reviewState = AlternateSourceBridgeReviewState.CURRENT,
            createdAt = 1_000L,
            updatedAt = bridgeUpdatedAt,
        ),
        mappings = listOf(
            AlternateSourceBridgeMapping(
                key = AlternateSourceBridgeMappingKey(readerBridgeKey(), READER_TARGET_ID),
                alternateChapterUrl = "/chapter/alternate",
                precedingPrimaryChapterUrl = "/chapter/primary",
                followingPrimaryChapterUrl = "/chapter/after",
                relation = AlternateSourceBridgeMappingRelation.PRIMARY_MISSING,
                state = mappingState,
                version = AlternateSourceBridgePolicy.CURRENT_VERSION,
                createdAt = 1_000L,
                updatedAt = mappingUpdatedAt,
            ),
        ),
    )
}
