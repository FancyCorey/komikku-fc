package eu.kanade.tachiyomi.extension.util

import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

class PrivateExtensionFileStoreTest {
    @TempDir
    lateinit var directory: File
    private val leases = mutableListOf<PrivateExtensionFileStore.Lease>()

    private fun lease() = PrivateExtensionFileStore.Lease("reader").also { leases.add(it) }
    private fun publishedFiles() = directory.listFiles().orEmpty().filter { !it.name.startsWith(".") }

    @AfterEach
    fun releaseLeases() {
        leases.forEach { it.ownership?.close() }
    }

    @Test
    fun `backup name collision preserves both existing files`() {
        val target = File(directory, "reader.ext").apply { writeText("working version") }
        val id = UUID.fromString("00000000-0000-0000-0000-000000000050")
        val existing = File(directory, ".rollback-reader.ext.backup").apply { writeText("unrelated backup") }
        val store = PrivateExtensionFileStore()
        var notified = false
        mockkStatic(UUID::class)
        try {
            every { UUID.randomUUID() } returns id
            assertThrows(IOException::class.java) {
                store.install(target, null, false, onInstalled = { notified = true }) { it.writeText("replacement") }
            }
        } finally {
            unmockkStatic(UUID::class)
        }
        assertFalse(notified)
        assertEquals("working version", target.readText())
        assertEquals("unrelated backup", existing.readText())
        assertEquals(setOf(target), publishedFiles().toSet())
        assertTrue(existing.exists())
    }

    @Test
    fun `failed rollback retains original until recovery permits a safe retry`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        val original = File(directory, "original.anchor")
        Files.createLink(original.toPath(), target.toPath())
        val notes = File(directory, "notes.tmp").apply { writeText("unrelated") }
        var moves = 0
        val rollbackFailure = IOException("rollback unavailable")
        val notificationFailure = IOException("notification unavailable")
        val store = PrivateExtensionFileStore { source, destination ->
            if (++moves == 2) throw rollbackFailure
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        val caught = assertThrows(IOException::class.java) {
            store.install(target, null, false, onInstalled = { throw notificationFailure }) { it.writeText("replacement") }
        }
        assertEquals(rollbackFailure, caught)
        assertTrue(caught.suppressed.contains(notificationFailure))
        val backup = File(directory, ".rollback-reader.ext.backup")
        assertEquals("original", backup.readText())
        assertFalse(Files.isSameFile(original.toPath(), backup.toPath()))
        assertEquals("replacement", target.readText())
        PrivateExtensionRecovery.recover(directory)
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        // Windows prevents replacing a read-only file; Android permits atomic directory renames.
        assertTrue(target.setWritable(true))
        assertTrue(PrivateExtensionFileStore().install(target, null, false) { it.writeText("retry") })
        assertEquals("retry", target.readText())
        assertEquals("unrelated", notes.readText())
        assertEquals(setOf(target, original, notes, File(directory, ".mutation.lock")), directory.listFiles().orEmpty().toSet())
    }

    @Test
    fun `temporary cleanup reports blocked deletion but permits retry`() {
        val target = spyk(File(directory, "reader.ext"))
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("probe") })
        var blocked = true
        every { target.delete() } answers { if (blocked) false else callOriginal() }
        assertThrows(IOException::class.java) { store.removeTemporary(target, lease) }
        assertEquals("probe", target.readText())
        blocked = false
        store.removeTemporary(target, lease)
        assertFalse(target.exists())
    }

    @Test
    fun `temporary cleanup does not report or delete a normal replacement`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("probe") })
        assertTrue(store.install(target, null, false) { it.writeText("user version") })
        store.removeTemporary(target, lease)
        assertEquals("user version", target.readText())
    }

    @Test
    fun `failed deletion retains ownership so cleanup can retry`() {
        val target = spyk(File(directory, "reader.ext"))
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("probe") })
        var deletionBlocked = true
        every { target.delete() } answers { if (deletionBlocked) false else callOriginal() }
        var notifications = 0

        assertFalse(store.remove(target, lease) { notifications++ })
        assertEquals("probe", target.readText())
        assertEquals(0, notifications)
        deletionBlocked = false

        assertTrue(store.remove(target, lease) { notifications++ })
        assertFalse(target.exists())
        assertEquals(1, notifications)
        assertFalse(store.remove(target, lease) { notifications++ })
        assertEquals(1, notifications)
    }

    @Test
    fun `temporary install preserves a preexisting private file`() {
        val target = File(directory, "reader.ext").apply { writeText("user version") }
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertFalse(store.install(target, lease, false) { it.writeText("probe") })
        assertFalse(store.remove(target, lease))
        assertEquals("user version", target.readText())
    }

    @Test
    fun `temporary install refuses a shared package`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        assertFalse(store.install(target, lease(), true) { it.writeText("probe") })
        assertFalse(target.exists())
    }

    @Test
    fun `owned file can be cleaned without installed list visibility`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("probe") })
        assertTrue(store.remove(target, lease))
        assertFalse(target.exists())
        assertFalse(store.remove(target, lease))
    }

    @Test
    fun `normal install invalidates old cleanup even with identical bytes`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("same bytes") })
        assertTrue(store.install(target, null, false) { it.writeText("same bytes") })
        assertFalse(store.remove(target, lease))
        assertEquals("same bytes", target.readText())
    }

    @Test
    fun `old lease cannot remove a newer temporary file`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val old = lease()
        val newer = lease()
        assertTrue(store.install(target, old, false) { it.writeText("old") })
        assertTrue(store.remove(target))
        assertTrue(store.install(target, newer, false) { it.writeText("new") })
        assertFalse(store.remove(target, old))
        assertEquals("new", target.readText())
        assertTrue(store.remove(target, newer))
    }

    @Test
    fun `failed copy removes the partial file and retains no ownership`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertThrows(IOException::class.java) {
            store.install(target, lease, false) { staging ->
                staging.writeText("partial")
                throw IOException("copy failed")
            }
        }
        assertFalse(target.exists())
        assertFalse(store.remove(target, lease))
        assertTrue(publishedFiles().isEmpty())
    }

    @Test
    fun `notification failure restores the previous file and owner`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("working version") })
        assertThrows(IOException::class.java) {
            store.install(target, null, false, onInstalled = {
                assertEquals("replacement", target.readText())
                throw IOException("notification failed")
            }) { it.writeText("replacement") }
        }
        assertEquals("working version", target.readText())
        assertTrue(requireNotNull(lease.ownership).owns(target))
        assertEquals(listOf(target), publishedFiles())
        assertTrue(store.remove(target, lease))
    }

    @Test
    fun `notification failure removes an unannounced temporary file`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertThrows(IOException::class.java) {
            store.install(target, lease, false, onInstalled = {
                throw IOException("notification failed")
            }) { it.writeText("probe") }
        }
        assertFalse(target.exists())
        assertFalse(store.remove(target, lease))
        assertTrue(publishedFiles().isEmpty())
    }

    @Test
    fun `failed normal update preserves the working private file`() {
        val target = File(directory, "reader.ext").apply { writeText("working version") }
        val store = PrivateExtensionFileStore()
        assertThrows(IOException::class.java) {
            store.install(target, null, false) { staging ->
                staging.writeText("partial update")
                assertEquals("working version", target.readText())
                throw IOException("copy failed")
            }
        }
        assertEquals("working version", target.readText())
        assertEquals(listOf(target), publishedFiles())
    }

    @Test
    fun `failed normal update preserves the previous temporary owner`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        val lease = lease()
        assertTrue(store.install(target, lease, false) { it.writeText("probe") })
        assertThrows(IOException::class.java) {
            store.install(target, null, false) { staging ->
                staging.writeText("partial update")
                throw IOException("copy failed")
            }
        }
        assertEquals("probe", target.readText())
        assertTrue(store.remove(target, lease))
        assertTrue(publishedFiles().isEmpty())
    }

    @Test
    fun `failed report after replacement move keeps transaction for recovery`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        var moves = 0
        val store = PrivateExtensionFileStore { source, destination ->
            Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            if (++moves == 1) throw IOException("replacement move result was lost")
        }

        assertThrows(IOException::class.java) {
            store.install(target, null, false) { it.writeText("replacement") }
        }

        val backup = File(directory, ".rollback-reader.ext.backup")
        val journal = File(directory, ".rollback-reader.ext.transaction")
        assertEquals("replacement", target.readText())
        assertTrue(backup.exists())
        assertTrue(journal.exists())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }
}
