package eu.kanade.tachiyomi.extension.util

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateExtensionLeaseLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    private val lockPath: Path,
) : Closeable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } finally {
            channel.close()
            synchronized(heldPaths) {
                heldPaths.remove(lockPath)
            }
        }
    }

    companion object {
        // On POSIX, closing any channel for a file can release every JVM-held lock for that file.
        // Avoid opening a second channel when this process already owns the lease.
        private val heldPaths = mutableSetOf<Path>()

        fun tryAcquire(file: File): PrivateExtensionLeaseLock? {
            val lockPath = file.canonicalFile.toPath()
            synchronized(heldPaths) {
                if (!heldPaths.add(lockPath)) return null

                var channel: FileChannel? = null
                var keepPathReserved = false
                try {
                    val openedChannel = FileChannel.open(lockPath, CREATE, WRITE)
                    channel = openedChannel
                    val lock = try {
                        openedChannel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        null
                    }
                    if (lock == null) {
                        try {
                            openedChannel.close()
                        } finally {
                            channel = null
                        }
                        return null
                    }

                    val lease = PrivateExtensionLeaseLock(openedChannel, lock, lockPath)
                    keepPathReserved = true
                    return lease
                } finally {
                    if (!keepPathReserved) {
                        try {
                            channel?.close()
                        } finally {
                            heldPaths.remove(lockPath)
                        }
                    }
                }
            }
        }
    }
}
