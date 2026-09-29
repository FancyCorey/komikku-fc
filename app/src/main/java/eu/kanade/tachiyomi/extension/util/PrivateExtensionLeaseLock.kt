package eu.kanade.tachiyomi.extension.util

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivateExtensionLeaseLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : Closeable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            lock.release()
        } finally {
            channel.close()
        }
    }

    companion object {
        fun tryAcquire(file: File): PrivateExtensionLeaseLock? {
            val channel = FileChannel.open(file.toPath(), CREATE, WRITE)
            try {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) return PrivateExtensionLeaseLock(channel, lock)
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
            channel.close()
            return null
        }
    }
}
