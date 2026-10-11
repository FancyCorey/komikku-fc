package eu.kanade.tachiyomi.data.updater

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * Explicit two-phase observation of the real Android PackageInstaller path.
 *
 * Ordinary instrumentation runs skip before touching either target app state or files. A `submit`
 * PASS means only that the real owner created a session whose ID was observed and durably bound
 * by a separate test-APK process. Only a later `verify` can establish a completed replacement.
 * This test does not prove ACTION_MY_PACKAGE_REPLACED delivery or completion of that receiver's
 * asynchronous finish.
 */
@RunWith(AndroidJUnit4::class)
class AppUpdateDownloadJobPackageInstallerInstrumentedTest {

    @Test
    fun actualPackageInstallerRouteIsExplicitAndPhased() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "The real installer route is opt-in only.",
            arguments.containsKey(ARG_ROUTE),
        )
        assertEquals(ROUTE_OPT_IN, arguments.getString(ARG_ROUTE))
        assertEquals("true", arguments.getString(ARG_PROFILE_ADMITTED))

        val phase = requireNotNull(arguments.getString(ARG_PHASE))
        assertTrue("phase must be submit or verify", phase == PHASE_SUBMIT || phase == PHASE_VERIFY)
        val profileId = requireNotNull(arguments.getString(ARG_PROFILE_ID)).trim()
        assertFalse("ROOT must name the separately admitted isolated profile", profileId.isEmpty())
        val expectedApkHash = requireNotNull(arguments.getString(ARG_EXPECTED_APK_SHA256)).lowercase()
        assertTrue("expected installed APK hash must be SHA-256", SHA256_PATTERN.matches(expectedApkHash))
        val expectedTestApkHash = requireNotNull(arguments.getString(ARG_EXPECTED_TEST_APK_SHA256)).lowercase()
        assertTrue("expected AndroidTest APK hash must be SHA-256", SHA256_PATTERN.matches(expectedTestApkHash))
        val runNonce = requireNotNull(arguments.getString(ARG_RUN_NONCE))
        assertTrue("run nonce must be 128-bit lowercase hex", RUN_NONCE_PATTERN.matches(runNonce))
        val expectedAttemptId = arguments.getString(ARG_EXPECTED_ATTEMPT_ID)
        val expectedSessionId = arguments.getString(ARG_EXPECTED_SESSION_ID)?.toIntOrNull()
        if (phase == PHASE_SUBMIT) {
            assertNull("submit must not receive an earlier attempt ID", expectedAttemptId)
            assertNull("submit must not receive an earlier session ID", expectedSessionId)
        } else {
            assertFalse("verify requires the exact submit attempt ID", expectedAttemptId.isNullOrBlank())
            assertTrue(
                "verify requires the exact positive submit session ID",
                expectedSessionId != null && expectedSessionId > 0,
            )
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        val installedTestApkHash = sha256(File(instrumentation.context.applicationInfo.sourceDir))
        assertEquals("ROOT's frozen hash must match the installed AndroidTest APK", expectedTestApkHash, installedTestApkHash)
        assertEquals(EXPECTED_PACKAGE, target.packageName)
        assertTrue("the real installer path requires SDK 31 or later", android.os.Build.VERSION.SDK_INT >= 31)
        assertEquals(
            "instrumentation must execute with the target app UID",
            target.applicationInfo.uid,
            Process.myUid(),
        )

        if (phase == PHASE_SUBMIT) {
            submit(
                instrumentation,
                instrumentation.context,
                target,
                profileId,
                expectedApkHash,
                runNonce,
                installedTestApkHash,
            )
        } else {
            verify(
                instrumentation,
                instrumentation.context,
                target,
                profileId,
                expectedApkHash,
                runNonce,
                requireNotNull(expectedAttemptId),
                requireNotNull(expectedSessionId),
                installedTestApkHash,
            )
        }
    }

    private fun submit(
        instrumentation: android.app.Instrumentation,
        observerContext: Context,
        target: Context,
        profileId: String,
        expectedApkHash: String,
        runNonce: String,
        expectedTestApkHash: String,
    ) = runBlocking {
        assertFalse(
            "bounded callback wait must run off the main Handler looper",
            Looper.myLooper() == Looper.getMainLooper(),
        )
        val packageInfo = target.packageManager.getPackageInfo(EXPECTED_PACKAGE, 0)
        val installedApk = File(target.applicationInfo.sourceDir)
        val installedApkHash = sha256(installedApk)
        assertEquals("ROOT's frozen hash must match the installed target APK", expectedApkHash, installedApkHash)

        val updatePrefs = target.getSharedPreferences(ATTEMPT_PREFERENCES, Context.MODE_PRIVATE)
        assertFalse("refusing to replace an existing active updater attempt", updatePrefs.contains(KEY_ACTIVE_ATTEMPT))
        assertFalse("refusing to overwrite an existing terminal updater attempt", updatePrefs.contains(KEY_COMPLETED_ATTEMPT))
        assertFalse("refusing to overwrite an existing terminal installer session", updatePrefs.contains(KEY_COMPLETED_SESSION))

        val installer = target.packageManager.packageInstaller
        val previousOwnedSessions = installer.mySessions
            .filter { it.installerPackageName == EXPECTED_PACKAGE }
            .map { it.sessionId }
            .toSet()
        assertTrue("refusing to guess among pre-existing app-owned installer sessions", previousOwnedSessions.isEmpty())

        val attemptId = "b8-installer-${UUID.randomUUID()}"
        val fileName = "$attemptId.apk"
        val observer = connectObserver(instrumentation, observerContext, target)
        var handedOffToVerify = false
        try {
            val prior = observer.snapshot()
            assertFalse(
                "a previous test attempt must be verified before another submit",
                prior.containsKey(B8InstallerObserverContract.KEY_ATTEMPT_ID),
            )
            val prepared = observer.request(
                B8InstallerObserverContract.MSG_PREPARE,
                Bundle().apply {
                    putString(B8InstallerObserverContract.KEY_ATTEMPT_ID, attemptId)
                    putString(B8InstallerObserverContract.KEY_PROFILE_ID, profileId)
                    putString(B8InstallerObserverContract.KEY_EXPECTED_APK_SHA256, expectedApkHash)
                    putInt(B8InstallerObserverContract.KEY_UID, target.applicationInfo.uid)
                    putLong(B8InstallerObserverContract.KEY_VERSION_CODE, packageInfo.longVersionCode)
                    putString(B8InstallerObserverContract.KEY_TEST_FILE_NAME, fileName)
                },
            )
            assertTrue(
                "test attempt metadata must be durable in the separate observer process before target mutation",
                prepared.getBoolean(B8InstallerObserverContract.EXTRA_OK, false),
            )

            val externalCache = requireNotNull(target.externalCacheDir)
            val testApk = File(externalCache, fileName)
            assertFalse("test-owned APK path must be new; overwriting is forbidden", testApk.exists())
            assertTrue("must create a unique test-owned APK path", testApk.createNewFile())
            installedApk.inputStream().use { input ->
                testApk.outputStream().use { output -> input.copyTo(output) }
            }
            assertEquals("copied test APK must be byte-identical to the installed base APK", expectedApkHash, sha256(testApk))
            val contentUri = testApk.getUriCompat(target)
            assertEquals("actual owner must use the app FileProvider", "content", contentUri.scheme)
            assertEquals("${EXPECTED_PACKAGE}.provider", contentUri.authority)
            assertTrue(
                "ready-to-submit state must be durable before invoking the installer owner",
                observer.setPhase("READY_TO_SUBMIT").getBoolean(B8InstallerObserverContract.EXTRA_OK, false),
            )

            assertTrue(
                "external observer must register from a distinct test-APK UID before the owner runs",
                observer.snapshot().getInt(B8InstallerObserverContract.KEY_OBSERVER_UID, -1) != target.applicationInfo.uid,
            )
            val attemptStore = AppUpdateAttemptStore(target)
            assertTrue("must establish only this unique updater attempt", attemptStore.begin(attemptId))
            assertTrue("actual owner must see the persisted unique attempt", attemptStore.isCurrent(attemptId))
            assertTrue(
                observer.startWatching().getBoolean(B8InstallerObserverContract.EXTRA_OK, false),
            )
            invokeActualStartInstalling(target, testApk, attemptId)
            observer.setPhase("OWNER_RETURNED")

            val observedState = awaitObserverState(observer) { snapshot ->
                snapshot.getStringArrayList(B8InstallerObserverContract.KEY_CREATED_SESSIONS).orEmpty().isNotEmpty() ||
                    snapshot.getString(B8InstallerObserverContract.KEY_OBSERVER_ERROR) != null
            }
            assertNotNull("external observer did not durably capture the real exact-package installer session", observedState)
            assertEquals(null, observedState?.getString(B8InstallerObserverContract.KEY_OBSERVER_ERROR))
            val createdSessions = requireNotNull(observedState)
                .getStringArrayList(B8InstallerObserverContract.KEY_CREATED_SESSIONS).orEmpty()
            assertEquals(
                "session-bound submission requires exactly one real session observed by the external process",
                1,
                createdSessions.size,
            )
            val sessionId = createdSessions.single()
            val targetOwnedSessions = installer.mySessions
            assertEquals(
                "the actual updater owner must leave exactly one caller-owned session for this fresh attempt",
                1,
                targetOwnedSessions.size,
            )
            val targetOwnedSession = targetOwnedSessions.single()
            assertEquals(
                "the separate observer's callback ID must be the exact session owned by the target app",
                sessionId.toInt(),
                targetOwnedSession.sessionId,
            )
            assertEquals(EXPECTED_PACKAGE, targetOwnedSession.installerPackageName)
            assertEquals(attemptId, updatePrefs.getString(KEY_ACTIVE_ATTEMPT, null))
            val bound = observer.bindSession(sessionId)
            assertTrue(
                "submit checkpoint must durably bind the uniquely observed real session",
                bound.getBoolean(B8InstallerObserverContract.EXTRA_OK, false),
            )
            val boundObserverState = observer.snapshot()
            val observerUid = boundObserverState.getInt(B8InstallerObserverContract.KEY_OBSERVER_UID, -1)
            assertTrue(
                "submit evidence must identify the distinct observer APK UID",
                observerUid > 0 && observerUid != target.applicationInfo.uid,
            )
            android.util.Log.i(
                "KfcB8Evidence",
                "B8_SUBMIT_EVIDENCE schema=1 " +
                    "runNonce=$runNonce profile=$profileId attempt=$attemptId session=$sessionId " +
                    "created=true bound=true observerUid=$observerUid " +
                    "observerPackage=${InstrumentationRegistry.getInstrumentation().context.packageName} " +
                    "targetUid=${target.applicationInfo.uid} package=${target.packageName} " +
                    "appSha256=$expectedApkHash testSha256=$expectedTestApkHash",
            )
            handedOffToVerify = true
        } catch (failure: Throwable) {
            if (!handedOffToVerify) {
                runCatching { observer.stop() }.onFailure { stopFailure ->
                    runCatching { stopObserverService(target) }
                        .onFailure(stopFailure::addSuppressed)
                    failure.addSuppressed(stopFailure)
                }
            }
            runCatching { observer.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
        observer.close()
    }

    private fun verify(
        instrumentation: android.app.Instrumentation,
        observerContext: Context,
        target: Context,
        profileId: String,
        expectedApkHash: String,
        runNonce: String,
        expectedAttemptId: String,
        expectedSessionId: Int,
        expectedTestApkHash: String,
    ) = runBlocking {
        val observer = connectObserver(instrumentation, observerContext, target)
        var verificationCompleted = false
        try {
            val state = observer.snapshot()
            val attemptId = requireNotNull(state.getString(B8InstallerObserverContract.KEY_ATTEMPT_ID))
            assertEquals("verify attempt must match the exact submit receipt", expectedAttemptId, attemptId)
            assertEquals(profileId, state.getString(B8InstallerObserverContract.KEY_PROFILE_ID))
            assertEquals(expectedApkHash, state.getString(B8InstallerObserverContract.KEY_EXPECTED_APK_SHA256))
            assertEquals(EXPECTED_PACKAGE, state.getString(B8InstallerObserverContract.KEY_PACKAGE_NAME))
            val attemptFileName = requireNotNull(state.getString(B8InstallerObserverContract.KEY_TEST_FILE_NAME))
            assertEquals("test file name must be derived from this unique attempt", "$attemptId.apk", attemptFileName)

            val packageInfo = target.packageManager.getPackageInfo(EXPECTED_PACKAGE, 0)
            assertEquals(state.getInt(B8InstallerObserverContract.KEY_UID, -1), target.applicationInfo.uid)
            assertEquals(state.getLong(B8InstallerObserverContract.KEY_VERSION_CODE, -1), packageInfo.longVersionCode)
            assertEquals(expectedApkHash, sha256(File(target.applicationInfo.sourceDir)))
            val retainedTestApk = File(requireNotNull(target.externalCacheDir), attemptFileName)
            assertTrue("verify requires the exact test-owned cache copy to remain available", retainedTestApk.isFile)
            assertEquals(expectedApkHash, sha256(retainedTestApk))

            assertFalse(
                "external observer must be a distinct test-APK UID, not the instrumented target UID",
                state.getInt(B8InstallerObserverContract.KEY_OBSERVER_UID, -1) == target.applicationInfo.uid,
            )
            assertEquals(
                "observer must belong to the separate instrumentation APK",
                InstrumentationRegistry.getInstrumentation().context.packageName,
                state.getString(B8InstallerObserverContract.KEY_OBSERVER_PACKAGE),
            )
            assertEquals(
                "expected observer target package must match the actual installed package",
                EXPECTED_PACKAGE,
                state.getString(B8InstallerObserverContract.KEY_EXPECTED_PACKAGE),
            )
            assertEquals(target.applicationInfo.uid, state.getInt(B8InstallerObserverContract.KEY_EXPECTED_UID, -1))

            val createdSessions = state.getStringArrayList(B8InstallerObserverContract.KEY_CREATED_SESSIONS).orEmpty()
            assertEquals("verify requires exactly one durably observed session", 1, createdSessions.size)
            val sessionId = createdSessions.single()
            assertEquals("verify session must match the exact submit receipt", expectedSessionId.toString(), sessionId)
            assertEquals(sessionId, state.getString(B8InstallerObserverContract.KEY_BOUND_SESSION))

            val observedInstallerPackage = state.getString(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_PACKAGE)
            val observedInstallerIdentity = state.getString(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_IDENTITY)
            if (observedInstallerPackage == null) {
                assertTrue("installer UID fallback is only available on API 34+", android.os.Build.VERSION.SDK_INT >= 34)
                assertEquals(
                    "null package attribution requires exact API 34+ UID and exclusive package mapping",
                    "API34_INSTALLER_UID_AND_EXCLUSIVE_PACKAGE_MAPPING",
                    observedInstallerIdentity,
                )
            } else {
                assertEquals(EXPECTED_PACKAGE, observedInstallerPackage)
                assertEquals("PACKAGE_NAME_AND_UID", observedInstallerIdentity)
            }
            assertEquals(
                target.applicationInfo.uid,
                state.getInt(B8InstallerObserverContract.KEY_OBSERVED_INSTALLER_UID, -1),
            )
            val updatePrefs = target.getSharedPreferences(ATTEMPT_PREFERENCES, Context.MODE_PRIVATE)
            val terminalState = awaitObserverState(observer) { snapshot ->
                snapshot.getString(B8InstallerObserverContract.KEY_FINISHED_SESSION) == sessionId ||
                    snapshot.getString(B8InstallerObserverContract.KEY_OBSERVER_ERROR) != null
            }
            if (terminalState == null) {
                val latestObserverState = observer.snapshot()
                val sessionInfo = target.packageManager.packageInstaller.getSessionInfo(sessionId.toInt())
                assertNotNull(
                    "the exact PackageInstaller session had no durable onFinished result within " +
                        "${SESSION_CALLBACK_ACK_TIMEOUT_MS}ms; pending user action or a missing callback " +
                        "must be diagnosed before judging app completion " +
                        "(activeAttempt=${updatePrefs.getString(KEY_ACTIVE_ATTEMPT, null)}, " +
                        "completedAttempt=${updatePrefs.getString(KEY_COMPLETED_ATTEMPT, null)}, " +
                        "completedSession=${updatePrefs.getInt(KEY_COMPLETED_SESSION, NO_SESSION)}, " +
                        "sessionActive=${sessionInfo?.isActive}, " +
                        "observerError=${latestObserverState.getString(B8InstallerObserverContract.KEY_OBSERVER_ERROR)})",
                    terminalState,
                )
            }
            val completedObserverState = requireNotNull(terminalState)
            val finishedSession = completedObserverState.getString(B8InstallerObserverContract.KEY_FINISHED_SESSION)
            val observerError = completedObserverState.getString(B8InstallerObserverContract.KEY_OBSERVER_ERROR)
            assertNull(
                "the independent PackageInstaller observer reported an error before confirming the exact session: " +
                    observerError,
                observerError,
            )
            val sessionSuccess = completedObserverState.getBoolean(B8InstallerObserverContract.KEY_SESSION_SUCCESS, false)
            assertEquals(
                "missing durable onFinished result for this exact session is inconclusive",
                sessionId,
                finishedSession,
            )
            assertTrue(
                "the real PackageInstaller reported this exact session unsuccessful",
                sessionSuccess,
            )
            assertTrue(
                "external observer must durably timestamp the exact terminal session event",
                completedObserverState.getLong(B8InstallerObserverContract.KEY_FINISHED_AT, 0L) > 0L,
            )
            val matchingAppCompletionObserved = awaitMatchingAppCompletion(
                updatePrefs = updatePrefs,
                attemptId = attemptId,
                sessionId = sessionId.toInt(),
            )
            assertTrue(
                "PackageInstaller reported terminal success, but the app did not persist its matching callback " +
                    "within ${SESSION_CALLBACK_ACK_TIMEOUT_MS}ms " +
                    "(activeAttempt=${updatePrefs.getString(KEY_ACTIVE_ATTEMPT, null)}, " +
                    "completedAttempt=${updatePrefs.getString(KEY_COMPLETED_ATTEMPT, null)}, " +
                    "completedSession=${updatePrefs.getInt(KEY_COMPLETED_SESSION, NO_SESSION)})",
                matchingAppCompletionObserved,
            )
            assertEquals(attemptId, updatePrefs.getString(KEY_ACTIVE_ATTEMPT, null))
            assertEquals(
                "PackageInstaller reached terminal success, but the app did not persist its matching callback",
                attemptId,
                updatePrefs.getString(KEY_COMPLETED_ATTEMPT, null),
            )
            assertEquals(sessionId.toInt(), updatePrefs.getInt(KEY_COMPLETED_SESSION, NO_SESSION))
            assertTrue(
                "verified outcome must be durable in the observer APK's private state",
                observer.setPhase("VERIFIED_REAL_SESSION_SUCCESS")
                    .getBoolean(B8InstallerObserverContract.EXTRA_OK, false),
            )
            android.util.Log.i(
                "KfcB8Evidence",
                "B8_CALLBACK_EVIDENCE schema=1 " +
                    "runNonce=$runNonce profile=$profileId attempt=$attemptId session=$sessionId " +
                    "created=${createdSessions.isNotEmpty()} " +
                    "bound=${state.getString(B8InstallerObserverContract.KEY_BOUND_SESSION) == sessionId} " +
                    "finished=$finishedSession success=$sessionSuccess " +
                    "finishedAt=${completedObserverState.getLong(B8InstallerObserverContract.KEY_FINISHED_AT, 0L)} " +
                    "observerUid=${completedObserverState.getInt(B8InstallerObserverContract.KEY_OBSERVER_UID, -1)} " +
                    "observerPackage=${completedObserverState.getString(B8InstallerObserverContract.KEY_OBSERVER_PACKAGE)} " +
                    "targetUid=${target.applicationInfo.uid} package=${packageInfo.packageName} " +
                    "versionCode=${packageInfo.longVersionCode} " +
                    "appSha256=${sha256(File(target.applicationInfo.sourceDir))} " +
                    "testSha256=$expectedTestApkHash " +
                    "completedAttempt=${updatePrefs.getString(KEY_COMPLETED_ATTEMPT, null)} " +
                    "completedSession=${updatePrefs.getInt(KEY_COMPLETED_SESSION, NO_SESSION)}",
            )
            observer.stop()
            verificationCompleted = true
        } catch (failure: Throwable) {
            if (!verificationCompleted) {
                runCatching { observer.stop() }.onFailure { stopFailure ->
                    runCatching { stopObserverService(target) }
                        .onFailure(stopFailure::addSuppressed)
                    failure.addSuppressed(stopFailure)
                }
            }
            runCatching { observer.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
        observer.close()
    }

    private suspend fun awaitObserverState(
        observer: ObserverClient,
        predicate: (Bundle) -> Boolean,
    ): Bundle? = withTimeoutOrNull(SESSION_CALLBACK_ACK_TIMEOUT_MS) {
        var state = observer.snapshot()
        while (!predicate(state)) {
            delay(50)
            state = observer.snapshot()
        }
        state
    }

    private suspend fun awaitMatchingAppCompletion(
        updatePrefs: android.content.SharedPreferences,
        attemptId: String,
        sessionId: Int,
    ): Boolean = withTimeoutOrNull(SESSION_CALLBACK_ACK_TIMEOUT_MS) {
        while (
            updatePrefs.getString(KEY_COMPLETED_ATTEMPT, null) != attemptId ||
            updatePrefs.getInt(KEY_COMPLETED_SESSION, NO_SESSION) != sessionId
        ) {
            delay(50)
        }
        true
    } ?: false

    private suspend fun connectObserver(
        instrumentation: android.app.Instrumentation,
        context: Context,
        target: Context,
    ): ObserverClient {
        val intent = Intent(context, PackageInstallerCallbackObserverService::class.java)
            .setAction(B8InstallerObserverContract.ACTION_KEEP_ALIVE)
        val component = requireNotNull(intent.component)
        val foregroundActivity = instrumentation.startActivitySync(
            Intent(Intent.ACTION_MAIN)
                .setClassName(target.packageName, MAIN_ACTIVITY_CLASS)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        var bound = false
        var serviceStartAttempted = false
        var connection: ServiceConnection? = null
        try {
            val connected = CompletableDeferred<Messenger>()
            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    connected.complete(Messenger(service))
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            serviceStartAttempted = true
            val startedComponent = target.startForegroundService(intent)
            assertEquals(
                "could not start the separate observer foreground service from the visible target Activity",
                component,
                startedComponent,
            )
            bound = target.bindService(intent, requireNotNull(connection), Context.BIND_AUTO_CREATE)
            assertTrue("could not bind to the separate observer service", bound)
            val remote = withTimeoutOrNull(OBSERVER_BIND_TIMEOUT_MS) { connected.await() }
            if (remote == null) {
                throw AssertionError("separate observer service did not bind before timeout")
            }
            val observer = ObserverClient(target, requireNotNull(connection), remote)
            assertTrue(
                "the separate observer must be running as a foreground service before test state is mutated",
                observer.snapshot().getBoolean(B8InstallerObserverContract.KEY_FOREGROUND_ACTIVE, false),
            )
            instrumentation.runOnMainSync { foregroundActivity.finish() }
            return observer
        } catch (failure: Throwable) {
            if (bound) {
                val activeConnection = requireNotNull(connection)
                runCatching { target.unbindService(activeConnection) }
                    .onFailure(failure::addSuppressed)
            }
            if (serviceStartAttempted) {
                runCatching { stopObserverService(target) }
                    .onFailure(failure::addSuppressed)
            }
            runCatching { instrumentation.runOnMainSync { foregroundActivity.finish() } }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun stopObserverService(context: Context) {
        val intent = Intent(context, PackageInstallerCallbackObserverService::class.java)
        // false means no started service was found (for example, it already crashed); that is
        // already stopped and must not mask the original bind/start failure.
        context.stopService(intent)
    }

    private class ObserverClient(
        private val context: Context,
        private val connection: ServiceConnection,
        private val remote: Messenger,
    ) {
        private val requestIds = AtomicInteger()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
        private val replyMessenger = Messenger(
            Handler(Looper.getMainLooper()) { response ->
                pending.remove(response.arg1)?.complete(Bundle(response.data))
                true
            },
        )

        suspend fun request(what: Int, data: Bundle = Bundle()): Bundle {
            val requestId = requestIds.incrementAndGet()
            val response = CompletableDeferred<Bundle>()
            pending[requestId] = response
            val message = Message.obtain(null, what, requestId, 0).apply {
                this.data = Bundle(data)
                replyTo = replyMessenger
            }
            remote.send(message)
            return withTimeoutOrNull(OBSERVER_REQUEST_TIMEOUT_MS) { response.await() }
                ?: throw AssertionError("observer IPC request $what timed out")
        }

        suspend fun snapshot(): Bundle = request(B8InstallerObserverContract.MSG_SNAPSHOT)

        suspend fun startWatching(): Bundle = request(B8InstallerObserverContract.MSG_START_WATCHING)

        suspend fun setPhase(phase: String): Bundle = request(
            B8InstallerObserverContract.MSG_SET_PHASE,
            Bundle().apply { putString(B8InstallerObserverContract.EXTRA_PHASE, phase) },
        )

        suspend fun bindSession(sessionId: String): Bundle = request(
            B8InstallerObserverContract.MSG_SET_PHASE,
            Bundle().apply {
                putString(B8InstallerObserverContract.KEY_BOUND_SESSION, sessionId)
                putString(B8InstallerObserverContract.EXTRA_PHASE, "SUBMITTED_SESSION_BOUND")
            },
        )

        suspend fun stop() {
            val stopped = request(
                B8InstallerObserverContract.MSG_STOP,
                Bundle().apply { putString(B8InstallerObserverContract.EXTRA_PHASE, "OBSERVER_STOPPED_AFTER_VERIFY") },
            )
            check(stopped.getBoolean(B8InstallerObserverContract.EXTRA_OK, false)) {
                "observer did not acknowledge the stop request: ${stopped.getString(B8InstallerObserverContract.EXTRA_ERROR)}"
            }
        }

        fun close() {
            context.unbindService(connection)
        }
    }

    private suspend fun invokeActualStartInstalling(target: Context, apk: File, attemptId: String) {
        val worker = TestListenableWorkerBuilder.from(
            target,
            AppUpdateDownloadJob::class.java,
        ).setInputData(
            Data.Builder().putString(AppUpdateDownloadJob.EXTRA_ATTEMPT_INPUT, attemptId).build(),
        ).build()
        val function = AppUpdateDownloadJob::class.declaredFunctions
            .single { it.name == "startInstalling" }
            .apply { isAccessible = true }
        try {
            function.callSuspend(worker, apk, "KMK installer route", attemptId)
        } catch (error: InvocationTargetException) {
            throw error.cause ?: error
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val EXPECTED_PACKAGE = "app.komikku.kmk"
        const val MAIN_ACTIVITY_CLASS = "eu.kanade.tachiyomi.ui.main.MainActivity"
        const val ROUTE_OPT_IN = "owned-packageinstaller-v1"
        const val PHASE_SUBMIT = "submit"
        const val PHASE_VERIFY = "verify"
        const val ARG_ROUTE = "kmk.installer.route"
        const val ARG_PHASE = "kmk.installer.phase"
        const val ARG_PROFILE_ADMITTED = "kmk.installer.profile_admitted"
        const val ARG_PROFILE_ID = "kmk.installer.profile_id"
        const val ARG_EXPECTED_APK_SHA256 = "kmk.installer.expected_apk_sha256"
        const val ARG_EXPECTED_TEST_APK_SHA256 = "kmk.installer.expected_test_apk_sha256"
        const val ARG_RUN_NONCE = "kmk.installer.run_nonce"
        const val ARG_EXPECTED_ATTEMPT_ID = "kmk.installer.expected_attempt_id"
        const val ARG_EXPECTED_SESSION_ID = "kmk.installer.expected_session_id"
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        val RUN_NONCE_PATTERN = Regex("[0-9a-f]{32}")
        const val SESSION_CALLBACK_ACK_TIMEOUT_MS = 30_000L
        const val OBSERVER_BIND_TIMEOUT_MS = 10_000L
        const val OBSERVER_REQUEST_TIMEOUT_MS = 10_000L
        const val ATTEMPT_PREFERENCES = "app_update_attempt"
        const val KEY_ATTEMPT_ID = "attempt_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_EXPECTED_APK_SHA256 = "expected_apk_sha256"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_UID = "uid"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_TEST_FILE_NAME = "test_file_name"
        const val KEY_PHASE_STATE = "phase_state"
        const val KEY_BOUND_SESSION = "bound_session"

        const val KEY_ACTIVE_ATTEMPT = "active_attempt"
        const val KEY_COMPLETED_ATTEMPT = "completed_attempt"
        const val KEY_COMPLETED_SESSION = "completed_session"
        const val NO_SESSION = -1
    }
}
