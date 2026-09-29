package eu.kanade.tachiyomi.extension.util

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PrivateExtensionRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun processDeathAfterReplacementPublishRecoversOriginalOnNextProcess() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val unrelated = File(directory, "notes.tmp").apply { writeText("keep") }
        val ready = File(directory, "published")
        val connected = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: android.os.IBinder) {
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                disconnected.countDown()
            }
        }
        val service = Intent(context, PrivateExtensionRecoveryCrashService::class.java)
            .putExtra(PrivateExtensionRecoveryCrashService.EXTRA_TARGET, target.absolutePath)
            .putExtra(PrivateExtensionRecoveryCrashService.EXTRA_READY, ready.absolutePath)
        check(context.bindService(service, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Recovery worker did not connect", connected.await(5, TimeUnit.SECONDS))
            context.startService(service)
            assertTrue("Recovery worker did not terminate", disconnected.await(10, TimeUnit.SECONDS))
            assertEquals("replacement-published", ready.readText())

            assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
            assertEquals("original", target.readText())
            assertEquals("keep", unrelated.readText())
            assertFalse(File(directory, ".rollback-candidate.ext.backup").exists())
            assertFalse(File(directory, ".rollback-candidate.ext.transaction").exists())
            assertFalse(
                directory.listFiles().orEmpty().any { file ->
                    (file.name.startsWith("extension-stage-") && file.name.endsWith(".tmp")) ||
                        (file.name.startsWith(".rollback-stage-") && file.name.endsWith(".tmp"))
                },
            )
        } finally {
            context.unbindService(connection)
        }
    }

    @Test
    fun processDeathAtEachReplacementBoundaryRecoversExpectedVersion() {
        val originalBytes = byteArrayOf(0x00, 0x12, 0x7F, 0x44, 0x01)
        val replacementBytes = byteArrayOf(0x00, 0x55, 0x10, 0x7F, 0x22, 0x01)
        val stages = listOf(
            PrivateExtensionRecoveryCrashService.STAGE_JOURNAL to originalBytes,
            PrivateExtensionRecoveryCrashService.STAGE_ROLLBACK_MOVED to originalBytes,
            PrivateExtensionRecoveryCrashService.STAGE_REPLACEMENT_PUBLISHED to originalBytes,
            PrivateExtensionRecoveryCrashService.STAGE_COMMITTED to replacementBytes,
            PrivateExtensionRecoveryCrashService.STAGE_BACKUP_CLEANED to replacementBytes,
        )

        stages.forEach { (stage, expectedVersion) ->
            withDirectory { directory, _ ->
                val target = File(directory, "candidate.ext").apply { writeBytes(originalBytes) }
                val ready = File(directory, "published")
                val connected = CountDownLatch(1)
                val disconnected = CountDownLatch(1)
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: android.os.IBinder) {
                        connected.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        disconnected.countDown()
                    }
                }
                val service = Intent(context, PrivateExtensionRecoveryCrashService::class.java)
                    .putExtra(PrivateExtensionRecoveryCrashService.EXTRA_TARGET, target.absolutePath)
                    .putExtra(PrivateExtensionRecoveryCrashService.EXTRA_READY, ready.absolutePath)
                    .putExtra(PrivateExtensionRecoveryCrashService.EXTRA_STAGE, stage)
                check(context.bindService(service, connection, Context.BIND_AUTO_CREATE))
                try {
                    assertTrue("Recovery worker did not connect at $stage", connected.await(30, TimeUnit.SECONDS))
                    context.startService(service)
                    assertTrue("Recovery worker did not terminate at $stage", disconnected.await(10, TimeUnit.SECONDS))
                    assertEquals(stage, ready.readText())

                    assertTrue("Recovery failed after $stage", PrivateExtensionRecovery.recover(directory).isEmpty())
                    assertTrue("Wrong recovered bytes after $stage", target.readBytes().contentEquals(expectedVersion))
                    assertTrue(
                        "Recovery artifacts remain after $stage",
                        requireNotNull(directory.listFiles()) {
                            "Could not inspect recovery artifacts after $stage"
                        }.none { file ->
                            file.name.startsWith(".rollback-") ||
                                (file.name.startsWith("extension-stage-") && file.name.endsWith(".tmp")) ||
                                (file.name.startsWith(".rollback-stage-") && file.name.endsWith(".tmp"))
                        },
                    )
                } finally {
                    context.unbindService(connection)
                }
            }
        }
    }

    @Test
    fun replacementMoveFailureKeepsJournalAndRollbackForRecovery() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        var moves = 0
        val store = PrivateExtensionFileStore { source, destination ->
            Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            if (++moves == 1) throw IOException("replacement move result was lost")
        }

        try {
            store.install(target, null, false) { it.writeText("replacement") }
            throw AssertionError("Expected the injected move failure")
        } catch (_: IOException) {
            // The rename completed, but the caller did not receive its result.
        }

        val backup = File(directory, ".rollback-candidate.ext.backup")
        val journal = File(directory, ".rollback-candidate.ext.transaction")
        assertEquals("replacement", target.readText())
        assertTrue(backup.exists())
        assertTrue(journal.exists())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun identicalReplacementSurvivesOldCleanupAndLegacyFilesArePreserved() = withDirectory { directory, leases ->
        val store = PrivateExtensionFileStore()
        val target = File(directory, "candidate.ext")
        val lease = PrivateExtensionFileStore.Lease("candidate").also { leases.add(it) }
        val source = File(directory, "source.bin").apply { writeText("same bytes") }
        val legacy = File(directory, "legacy.ext").apply { writeText("legacy") }
        assertTrue(store.install(target, lease, false) { source.copyAndSetReadOnlyTo(it, overwrite = true) })
        val anchor = requireNotNull(lease.ownership).anchor
        assertTrue(requireNotNull(lease.ownership).owns(target))
        assertTrue(store.install(target, null, false) { source.copyAndSetReadOnlyTo(it, overwrite = true) })
        assertFalse(requireNotNull(lease.ownership).owns(target))
        var removals = 0
        store.removeTemporary(target, lease) { removals++ }
        assertEquals(0, removals)
        assertEquals("same bytes", target.readText())
        assertFalse(target.canWrite())
        assertFalse(anchor.exists())
        assertFalse(store.install(target, PrivateExtensionFileStore.Lease("candidate"), false) { error("Must not copy") })
        assertFalse(store.install(File(directory, "shared.ext"), PrivateExtensionFileStore.Lease("shared"), true) { error("Must not copy") })
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("legacy", legacy.readText())
        assertEquals("same bytes", target.readText())
    }

    @Test
    fun readOnlyTemporaryFileIsExcludedAndFailedDirectoryDeletionCanBeRetried() = withDirectory { directory, leases ->
        val store = PrivateExtensionFileStore()
        val target = File(directory, "candidate.ext")
        val lease = PrivateExtensionFileStore.Lease("candidate").also { leases.add(it) }
        val source = File(directory, "source.bin").apply { writeText("temporary") }
        assertTrue(store.install(target, lease, false) { source.copyAndSetReadOnlyTo(it, overwrite = true) })
        val ownership = requireNotNull(lease.ownership)
        assertFalse(target.canWrite())
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertTrue(directory.setWritable(false, true))
        try {
            try {
                store.removeTemporary(target, lease)
                throw AssertionError("Deletion in a non-writable directory must fail")
            } catch (_: IOException) {
                assertEquals("temporary", target.readText())
                assertTrue(ownership.anchor.exists())
            }
        } finally {
            try {
                assertTrue(directory.setWritable(true, true))
            } finally {
                ownership.close()
            }
        }
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertFalse(target.exists())
        assertFalse(ownership.anchor.exists())
        assertTrue(File(directory, ".lease-candidate.ext.lock").exists())
    }

    @Test
    fun publicationAndRemovalSendPackageScopedNotifications() = withDirectory { directory, _ ->
        val packageName = "fixture.recovery.${UUID.randomUUID()}"
        val actions = listOf("ADDED", "REPLACED", "REMOVED").map { "${context.packageName}.ACTION_EXTENSION_$it" }
        val received = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == packageName) received.offer(Intent(intent))
            }
        }
        val filter = IntentFilter().apply {
            actions.forEach { addAction(it) }
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val store = PrivateExtensionFileStore()
            val target = File(directory, "notification.ext")
            val source = File(directory, "source.bin").apply { writeText("first") }
            assertTrue(store.install(target, null, false, { ExtensionInstallReceiver.notifyAdded(context, packageName) }) { source.copyAndSetReadOnlyTo(it, overwrite = true) })
            source.writeText("replacement")
            assertTrue(store.install(target, null, false, { ExtensionInstallReceiver.notifyReplaced(context, packageName) }) { source.copyAndSetReadOnlyTo(it, overwrite = true) })
            assertTrue(store.remove(target) { ExtensionInstallReceiver.notifyRemoved(context, packageName) })
            actions.forEach { action ->
                val notification = requireNotNull(received.poll(5, TimeUnit.SECONDS)) { "Missing $action" }
                assertEquals(action, notification.action)
                assertEquals(context.packageName, notification.`package`)
                assertEquals("package:$packageName", notification.data.toString())
            }
            assertTrue(received.isEmpty())
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun unreadableExtensionDirectoryFailsClosed() = withDirectory { directory, _ ->
        File(directory, ".mutation.lock").writeText("")
        assertTrue(directory.setReadable(false, false))
        try {
            val failure = runCatching { PrivateExtensionRecovery.recover(directory) }.exceptionOrNull()
            assertTrue("Expected an IOException when extension files cannot be enumerated", failure is IOException)
        } finally {
            assertTrue(directory.setReadable(true, false))
        }
    }

    @Test
    fun oneCleanupFailureDoesNotPreventHealthyTargetRecovery() = withDirectory { directory, leases ->
        val store = PrivateExtensionFileStore()
        val blocked = File(directory, "blocked.ext")
        val healthy = File(directory, "healthy.ext")
        val blockedLease = PrivateExtensionFileStore.Lease("blocked").also { leases.add(it) }
        val healthyLease = PrivateExtensionFileStore.Lease("healthy").also { leases.add(it) }
        val blockedSource = File(directory, "blocked-source.bin").apply { writeText("blocked") }
        val healthySource = File(directory, "healthy-source.bin").apply { writeText("healthy") }
        assertTrue(store.install(blocked, blockedLease, false) { blockedSource.copyAndSetReadOnlyTo(it, overwrite = true) })
        assertTrue(store.install(healthy, healthyLease, false) { healthySource.copyAndSetReadOnlyTo(it, overwrite = true) })
        val blockedAnchor = requireNotNull(blockedLease.ownership).anchor
        val healthyAnchor = requireNotNull(healthyLease.ownership).anchor
        blockedLease.ownership?.close()
        healthyLease.ownership?.close()

        val excluded = PrivateExtensionRecovery.recover(directory) { target ->
            if (target == blocked) throw IOException("blocked target")
            target.delete()
        }

        assertEquals(setOf(blocked), excluded)
        assertTrue(blocked.exists())
        assertTrue(blockedAnchor.exists())
        assertFalse(healthy.exists())
        assertFalse(healthyAnchor.exists())
    }

    @Test
    fun interruptedReplacementBeforePublishRestoresOriginal() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("new version") }
        val backup = File(directory, ".rollback-candidate.ext.backup")
        Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)

        assertTrue(journal.exists())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun interruptedReplacementBeforeCommitRestoresOriginal() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("new version") }
        val backup = File(directory, ".rollback-candidate.ext.backup")
        Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)
        Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun committedReplacementSurvivesInterruptedCleanup() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("new version") }
        val backup = File(directory, ".rollback-candidate.ext.backup")
        Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)
        Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        PrivateExtensionRecovery.commitReplacement(journal)

        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("new version", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun changedRollbackCopyIsPreservedAndTargetExcluded() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("new version") }
        val backup = File(directory, ".rollback-candidate.ext.backup").apply { writeText("rollback") }
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)
        val userFile = File(directory, "user-backup").apply { writeText("user data") }
        Files.move(userFile.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("original", target.readText())
        assertEquals("user data", backup.readText())
        assertTrue(journal.exists())
    }

    @Test
    fun changedTargetIsPreservedAndTransactionExcluded() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("new version") }
        val backup = File(directory, ".rollback-candidate.ext.backup").apply { Files.copy(target.toPath(), toPath()) }
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)
        val userFile = File(directory, "user-extension").apply { writeText("user version") }
        Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        Files.move(userFile.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("user version", target.readText())
        assertEquals("original", backup.readText())
        assertTrue(journal.exists())
    }

    @Test
    fun retryReconcilesPendingReplacementBeforeInstalling() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("original") }
        val replacement = File(directory, "replacement-stage").apply { writeText("interrupted version") }
        val backup = File(directory, ".rollback-candidate.ext.backup").apply { writeText("original") }
        val journal = PrivateExtensionRecovery.beginReplacement(target, replacement, backup)
        Files.move(replacement.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

        assertTrue(PrivateExtensionFileStore().install(target, null, false) { it.writeText("retry version") })

        assertEquals("retry version", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
    }

    @Test
    fun unmarkedLegacyRollbackFileIsPreservedWhenReplacementIsBlocked() = withDirectory { directory, _ ->
        val target = File(directory, "candidate.ext").apply { writeText("installed") }
        val backup = File(directory, ".rollback-candidate.ext.backup").apply { writeText("unmarked user backup") }
        val attemptedSource = File(directory, "attempted.bin").apply { writeText("new version") }
        val failure = runCatching {
            PrivateExtensionFileStore().install(target, null, false) { staging ->
                attemptedSource.copyAndSetReadOnlyTo(staging, overwrite = true)
            }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("installed", target.readText())
        assertEquals("unmarked user backup", backup.readText())
        assertFalse(File(directory, ".rollback-candidate.ext.transaction").exists())
    }

    @Test
    fun liveTemporaryLeaseAdoptsRollbackRestoredByRecovery() = withDirectory { directory, leases ->
        val target = File(directory, "candidate.ext")
        val lease = PrivateExtensionFileStore.Lease("candidate").also { leases.add(it) }
        val store = PrivateExtensionFileStore { source, destination ->
            if (source.name.endsWith(".backup")) throw IOException("blocked rollback")
            Files.move(
                source.toPath(),
                destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }

        assertTrue(store.install(target, lease, false) { it.writeText("temporary") })
        val ownership = requireNotNull(lease.ownership)
        try {
            val failure = runCatching {
                store.install(target, null, false, onInstalled = { throw IOException("notification failed") }) {
                    it.writeText("replacement")
                }
            }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals("replacement", target.readText())

            assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
            assertTrue(ownership.owns(target))
            assertEquals("temporary", target.readText())

            var removals = 0
            store.removeTemporary(target, lease) { removals++ }
            assertFalse(target.exists())
            assertFalse(ownership.anchor.exists())
            assertFalse(File(directory, ".rollback-candidate.ext.backup").exists())
            assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
            store.removeTemporary(target, lease) { removals++ }
            assertEquals(1, removals)
        } finally {
            ownership.close()
        }
    }

    @Test
    fun concurrentTemporaryInstallsForOneTargetAreSerialized() = withDirectory { directory, leases ->
        val target = File(directory, "candidate.ext")
        val firstLease = PrivateExtensionFileStore.Lease("first").also { leases.add(it) }
        val secondLease = PrivateExtensionFileStore.Lease("second").also { leases.add(it) }
        val firstSource = File(directory, "first.bin").apply { writeText("first") }
        val firstCopyEntered = CountDownLatch(1)
        val releaseFirstCopy = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val store = PrivateExtensionFileStore()

        try {
            val firstInstall = executor.submit<Boolean> {
                store.install(target, firstLease, false) { staging ->
                    firstCopyEntered.countDown()
                    check(releaseFirstCopy.await(5, TimeUnit.SECONDS))
                    firstSource.copyAndSetReadOnlyTo(staging, overwrite = true)
                }
            }
            assertTrue(firstCopyEntered.await(5, TimeUnit.SECONDS))
            val secondInstall = executor.submit<Boolean> {
                secondStarted.countDown()
                store.install(target, secondLease, false) { error("Second install must not copy") }
            }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            releaseFirstCopy.countDown()

            assertTrue(firstInstall.get(5, TimeUnit.SECONDS))
            assertFalse(secondInstall.get(5, TimeUnit.SECONDS))
            assertEquals("first", target.readText())
            assertNull(secondLease.ownership)

            store.removeTemporary(target, firstLease)
            assertFalse(target.exists())
            assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        } finally {
            releaseFirstCopy.countDown()
            executor.shutdownNow()
        }
    }

    private fun withDirectory(test: (File, MutableList<PrivateExtensionFileStore.Lease>) -> Unit) {
        val directory = File(context.filesDir, "recovery-device-test-${UUID.randomUUID()}")
        check(directory.canonicalFile.parentFile == context.filesDir.canonicalFile)
        check(directory.mkdir())
        val leases = mutableListOf<PrivateExtensionFileStore.Lease>()
        try {
            test(directory, leases)
        } finally {
            try {
                leases.forEach { it.ownership?.close() }
            } finally {
                directory.setWritable(true, true)
                check(directory.deleteRecursively())
            }
        }
    }
}
