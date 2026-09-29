package eu.kanade.tachiyomi.extension.util

import android.system.Os
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal object PrivateExtensionRecovery {
    private val monitor = Any()
    private val heldDirectory = ThreadLocal<File?>()

    class Ownership(val anchor: File, private val lock: PrivateExtensionLeaseLock) {
        // Recovery may adopt a rollback inode while this lease is still running.
        fun owns(target: File) = target.exists() && anchor.exists() && anchor.readText() == fileIdentity(target)

        fun adopt(target: File) {
            val replacementIdentity = fileIdentity(target)
            writeAtomically(anchor, replacementIdentity)
        }

        fun close() = lock.close()
    }

    fun <T> mutate(directory: File, action: () -> T): T = synchronized(monitor) {
        val canonical = directory.canonicalFile
        if (heldDirectory.get() == canonical) return@synchronized action()
        Files.createDirectories(canonical.toPath())
        FileChannel.open(File(canonical, ".mutation.lock").toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use {
                val previous = heldDirectory.get()
                heldDirectory.set(canonical)
                try {
                    action()
                } finally {
                    heldDirectory.set(previous)
                }
            }
        }
    }

    fun begin(target: File, staging: File): Ownership {
        val anchor = File(target.parentFile, ".lease-${target.name}.${UUID.randomUUID()}.anchor")
        val lock = PrivateExtensionLeaseLock.tryAcquire(lockFile(target))
            ?: throw IOException("Temporary extension is already in use")
        try {
            val identity = ownershipIdentity(staging)
            Files.write(anchor.toPath(), identity.toByteArray(), CREATE_NEW, WRITE)
            return Ownership(anchor, lock)
        } catch (failure: Throwable) {
            lock.close()
            throw failure
        }
    }

    fun removeOwned(target: File, ownership: Ownership, onRemoved: () -> Unit) {
        try {
            var notificationFailure: Throwable? = null
            if (ownership.owns(target)) {
                if (!target.delete()) throw IOException("Could not remove temporary extension")
                try {
                    onRemoved()
                } catch (failure: Throwable) {
                    notificationFailure = failure
                }
            }
            try {
                Files.deleteIfExists(ownership.anchor.toPath())
            } catch (cleanupFailure: Throwable) {
                if (notificationFailure != null) {
                    notificationFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
            notificationFailure?.let { throw it }
        } finally {
            ownership.close()
        }
    }

    fun recover(directory: File, deleteTarget: (File) -> Boolean = { it.delete() }): Set<File> = mutate(directory) {
        val excluded = mutableSetOf<File>()
        listFiles(directory)
            .filter { it.isFile && it.name.startsWith(".rollback-") && it.name.endsWith(".transaction") }
            .forEach { journal ->
                val name = journal.name.removePrefix(".rollback-").removeSuffix(".transaction")
                if (!name.endsWith(".ext")) return@forEach
                val target = File(directory, name)
                try {
                    val identities = journal.readLines()
                    if (identities.size != 4 || identities[3] !in setOf("pending", "committed")) throw IOException("Invalid extension transaction")
                    val backup = File(directory, ".rollback-$name.backup")
                    if (backup.exists() && fileIdentity(backup) != identities[2]) throw IOException("Extension rollback copy changed")
                    val identity = if (target.exists()) fileIdentity(target) else null
                    if (identities[3] == "committed") {
                        if (identity != identities[1]) throw IOException("Committed extension changed")
                        if (backup.exists()) Files.delete(backup.toPath())
                        Files.delete(journal.toPath())
                        return@forEach
                    }
                    if (backup.exists() || identity == identities[2]) {
                        when (identity) {
                            identities[0] -> Files.deleteIfExists(backup.toPath())
                            identities[1], null -> {
                                Files.move(backup.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                            }
                            identities[2] -> Unit
                            else -> throw IOException("Extension changed outside its pending transaction")
                        }
                        if (fileIdentity(target) == identities[2]) {
                            listFiles(directory)
                                .filter { it.name.startsWith(".lease-$name.") && it.name.endsWith(".anchor") }
                                .filter { it.readText() == identities[0] }
                                .forEach { writeAtomically(it, fileIdentity(target)) }
                        }
                    }
                    Files.delete(journal.toPath())
                } catch (_: IOException) {
                    excluded.add(target)
                }
            }
        listFiles(directory)
            .filter { it.isFile && it.name.endsWith(".tmp") }
            .filter { file ->
                val prefix = when {
                    file.name.startsWith("extension-stage-") -> "extension-stage-"
                    file.name.startsWith(".rollback-stage-") -> ".rollback-stage-"
                    else -> return@filter false
                }
                runCatching { UUID.fromString(file.name.removePrefix(prefix).removeSuffix(".tmp")) }.isSuccess
            }
            .forEach {
                try {
                    Files.delete(it.toPath())
                } catch (_: IOException) {
                    // Staging files are not loadable extensions; retry cleanup on the next scan.
                }
            }
        listFiles(directory)
            .filter { it.isFile && it.name.startsWith(".lease-") && it.name.endsWith(".anchor") }
            .forEach { marker ->
                val stem = marker.name.removePrefix(".lease-").removeSuffix(".anchor")
                val name = stem.substringBeforeLast(".")
                if (!name.endsWith(".ext") || runCatching { UUID.fromString(stem.substringAfterLast(".")) }.isFailure) return@forEach
                val target = File(directory, name)
                if (target in excluded) return@forEach
                try {
                    val lock = PrivateExtensionLeaseLock.tryAcquire(lockFile(target))
                    if (lock == null) {
                        if (sameFile(target, marker)) excluded.add(target)
                    } else {
                        lock.use {
                            if (sameFile(target, marker) && !deleteTarget(target)) {
                                excluded.add(target)
                            } else {
                                Files.deleteIfExists(marker.toPath())
                            }
                        }
                    }
                } catch (_: IOException) {
                    excluded.add(target)
                }
            }
        excluded
    }

    fun beginReplacement(target: File, replacement: File, rollback: File): File {
        val journal = File(target.parentFile, ".rollback-${target.name}.transaction")
        val content = listOf(fileIdentity(target), ownershipIdentity(replacement), ownershipIdentity(rollback), "pending").joinToString("\n")
        writeAtomically(journal, content, replace = false)
        return journal
    }

    private fun listFiles(directory: File): Array<File> = directory.listFiles()
        ?: throw IOException("Could not scan private extensions")

    fun commitReplacement(journal: File) {
        val identities = journal.readLines()
        check(identities.size == 4)
        writeAtomically(journal, (identities.take(3) + "committed").joinToString("\n"))
    }

    private fun writeAtomically(target: File, content: String, replace: Boolean = true) {
        val staging = File(target.parentFile, "extension-stage-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(staging.toPath(), CREATE_NEW, WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(content.toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            if (!replace && target.exists()) throw IOException("Extension transaction already exists")
            Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(staging.toPath())
        }
    }

    private fun sameFile(target: File, marker: File): Boolean {
        if (!target.exists() || !marker.exists()) return false
        return marker.readText() == fileIdentity(target)
    }

    private fun fileIdentity(file: File): String {
        if (System.getProperty("java.vm.name") == "Dalvik") {
            val stat = Os.stat(file.absolutePath)
            return "${stat.st_dev}:${stat.st_ino}"
        }
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        // Windows may preserve the destination's file key during a replacement rename. Pair it
        // with the ownership timestamp so an unrelated replacement cannot inherit the old lease.
        return "${attributes.fileKey()}:${attributes.lastModifiedTime().toMillis()}:${attributes.size()}"
    }

    private fun ownershipIdentity(file: File): String {
        if (System.getProperty("java.vm.name") != "Dalvik") {
            val token = fallbackTimestamp.updateAndGet { previous -> maxOf(previous + 1, System.currentTimeMillis() + 1) }
            if (!file.setLastModified(token)) throw IOException("Could not assign temporary extension identity")
        }
        return fileIdentity(file)
    }

    private fun lockFile(target: File) = File(target.parentFile, ".lease-${target.name}.lock")

    private val fallbackTimestamp = AtomicLong(System.currentTimeMillis())
}
