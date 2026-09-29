package eu.kanade.tachiyomi.extension.util

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal class PrivateExtensionFileStore(
    private val move: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    class Lease(val packageName: String) {
        var finishDownload: suspend () -> Unit = {}
        var ownership: PrivateExtensionRecovery.Ownership? = null
    }

    private val owners = mutableMapOf<File, Lease>()

    private fun <T> withMutation(target: File, action: () -> T): T = PrivateExtensionRecovery.mutate(target.parentFile, action)

    fun install(
        target: File,
        lease: Lease?,
        sharedInstalled: Boolean,
        onInstalled: () -> Unit = {},
        onRecoveryBoundary: (RecoveryBoundary) -> Unit = {},
        copy: (File) -> Unit,
    ): Boolean = withMutation(target) {
        val excluded = PrivateExtensionRecovery.recover(target.parentFile)
        if (target in excluded && File(target.parentFile, ".rollback-${target.name}.transaction").exists()) {
            if (lease != null) return@withMutation false
            throw IOException("Extension recovery is still pending")
        }
        if (lease != null && (target.exists() || sharedInstalled)) return@withMutation false
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, "extension-stage-${UUID.randomUUID()}.tmp")
        Files.createFile(staging.toPath())
        var backup: File? = null
        var rollbackStaging: File? = null
        var retainBackup = false
        var transaction: File? = null
        try {
            copy(staging)
            if (lease != null) {
                var ownership: PrivateExtensionRecovery.Ownership? = null
                try {
                    ownership = PrivateExtensionRecovery.begin(target, staging)
                    lease.ownership = ownership
                } catch (failure: Throwable) {
                    ownership?.let { created ->
                        try {
                            Files.deleteIfExists(created.anchor.toPath())
                        } catch (cleanupFailure: Throwable) {
                            failure.addSuppressed(cleanupFailure)
                        }
                        try {
                            created.close()
                        } catch (cleanupFailure: Throwable) {
                            failure.addSuppressed(cleanupFailure)
                        }
                    }
                    throw failure
                }
            }
            if (target.exists()) {
                val candidate = File(target.parentFile, ".rollback-${target.name}.backup")
                if (candidate.exists()) throw IOException("An extension rollback is already pending")
                val rollbackCandidate = File(target.parentFile, ".rollback-stage-${UUID.randomUUID()}.tmp")
                rollbackStaging = rollbackCandidate
                Files.copy(target.toPath(), rollbackCandidate.toPath())
                if (!rollbackCandidate.setReadOnly()) throw IOException("Could not protect the extension rollback copy")
                transaction = PrivateExtensionRecovery.beginReplacement(target, staging, rollbackCandidate)
                onRecoveryBoundary(RecoveryBoundary.JOURNAL_PUBLISHED)
                Files.move(rollbackCandidate.toPath(), candidate.toPath(), ATOMIC_MOVE)
                onRecoveryBoundary(RecoveryBoundary.ROLLBACK_MOVED)
                backup = candidate
                rollbackStaging = null
            }
            move(staging, target)
            if (transaction != null) onRecoveryBoundary(RecoveryBoundary.REPLACEMENT_PUBLISHED)
            try {
                onInstalled()
            } catch (notificationFailure: Exception) {
                try {
                    if (backup != null) {
                        move(backup, target)
                        owners[target]?.ownership?.adopt(target)
                    } else if (!target.delete()) {
                        throw IOException("Could not remove unannounced private extension")
                    }
                } catch (rollbackFailure: Exception) {
                    retainBackup = backup != null
                    owners.remove(target)
                    if (lease != null) owners[target] = lease
                    rollbackFailure.addSuppressed(notificationFailure)
                    throw rollbackFailure
                }
                throw notificationFailure
            }
            transaction?.let {
                try {
                    PrivateExtensionRecovery.commitReplacement(it)
                    onRecoveryBoundary(RecoveryBoundary.COMMITTED)
                } catch (failure: IOException) {
                    retainBackup = true
                    throw failure
                }
            }
            owners.remove(target)
            if (lease != null) owners[target] = lease
            true
        } catch (failure: Throwable) {
            if (transaction != null) retainBackup = true
            throw failure
        } finally {
            staging.delete()
            rollbackStaging?.delete()
            if (!retainBackup) {
                backup?.delete()
                if (transaction != null && backup != null) onRecoveryBoundary(RecoveryBoundary.BACKUP_CLEANED)
            }
            if (!retainBackup && backup?.exists() != true) transaction?.delete()
            if (lease != null && owners[target] !== lease) {
                lease.ownership?.let { PrivateExtensionRecovery.removeOwned(target, it) {} }
                lease.ownership = null
            }
        }
    }

    internal enum class RecoveryBoundary {
        JOURNAL_PUBLISHED,
        ROLLBACK_MOVED,
        REPLACEMENT_PUBLISHED,
        COMMITTED,
        BACKUP_CLEANED,
    }

    fun remove(target: File, lease: Lease? = null, onRemoved: () -> Unit = {}): Boolean = withMutation(target) {
        if (lease != null && owners[target] !== lease) return@withMutation false
        val removed = target.delete()
        if (removed || !target.exists()) {
            owners.remove(target)?.let { owner ->
                owner.ownership?.let { PrivateExtensionRecovery.removeOwned(target, it) {} }
                owner.ownership = null
            }
        }
        if (removed) onRemoved()
        removed
    }

    fun removeTemporary(target: File, lease: Lease, onRemoved: () -> Unit = {}): Unit = withMutation(target) {
        val ownership = lease.ownership
        if (ownership != null) {
            PrivateExtensionRecovery.removeOwned(target, ownership, onRemoved)
            if (owners[target] === lease) owners.remove(target)
            lease.ownership = null
            return@withMutation
        }
        if (owners[target] !== lease) return@withMutation
        if (!remove(target, lease, onRemoved) && target.exists()) {
            throw IOException("Could not remove temporary extension ${lease.packageName}")
        }
    }
}
