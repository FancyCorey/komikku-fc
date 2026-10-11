package eu.kanade.tachiyomi.extension.util

import io.mockk.every
import io.mockk.spyk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PrivateExtensionRecoveryTest {
    @TempDir
    lateinit var directory: File
    private val lease = PrivateExtensionFileStore.Lease("reader")
    private val newer = PrivateExtensionFileStore.Lease("reader")
    private fun anchors() = directory.listFiles().orEmpty().filter { it.name.endsWith(".anchor") }

    @AfterEach
    fun releaseLease() {
        lease.ownership?.close()
        newer.ownership?.close()
    }

    @Test
    fun `recovery protects the live publisher and cleans up after process termination`() {
        val extensions = File(directory, "exts").apply { mkdirs() }
        val target = File(extensions, "reader.ext")
        val legacy = File(extensions, "legacy.ext").apply { writeText("user extension") }
        val ready = File(directory, "ready")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        val main = PrivateExtensionRecoveryProcess::class.java
        val classpath = listOf(main, PrivateExtensionFileStore::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(java.absolutePath, "-cp", classpath, main.name, target.absolutePath, ready.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) {
                process.waitFor(20, TimeUnit.MILLISECONDS)
            }
            assertTrue(ready.exists() && process.isAlive, "Publisher failed to become ready")
            assertEquals(setOf(target), PrivateExtensionRecovery.recover(extensions))
            assertEquals("child probe", target.readText())
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Publisher did not terminate")
            assertTrue(PrivateExtensionRecovery.recover(extensions).isEmpty())
            assertFalse(target.exists())
            assertTrue(extensions.listFiles().orEmpty().none { it.name.endsWith(".anchor") })
            assertEquals("user extension", legacy.readText())
            assertTrue(File(extensions, ".lease-reader.ext.lock").exists())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture process still alive")
            }
        }
    }

    @Test
    fun `replacement transaction recovers after process death at every boundary`() {
        val extensions = File(directory, "exts").apply { mkdirs() }
        val unrelated = File(extensions, "notes.tmp").apply { writeText("keep") }
        val stages = listOf(
            "journal" to "original",
            "rollback-moved" to "original",
            "replacement-published" to "original",
            "committed" to "replacement",
            "backup-cleaned" to "replacement",
        )
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        val main = PrivateExtensionRecoveryProcess::class.java
        val classpath = listOf(main, PrivateExtensionRecovery::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct()
            .joinToString(File.pathSeparator)

        stages.forEach { (stage, expectedVersion) ->
            val target = File(extensions, "candidate-$stage.ext").apply { writeText("original") }
            val ready = File(directory, "ready-$stage")
            val process = ProcessBuilder(
                java.absolutePath,
                "-cp",
                classpath,
                main.name,
                target.absolutePath,
                ready.absolutePath,
                "transaction",
                stage,
            ).redirectErrorStream(true).start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ((!ready.exists() || ready.readText() != stage) && process.isAlive && System.nanoTime() < deadline) {
                    process.waitFor(20, TimeUnit.MILLISECONDS)
                }
                assertTrue(ready.exists() && ready.readText() == stage && process.isAlive, "Transaction process missed the $stage boundary")
                assertEquals(stage, ready.readText())
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Transaction process remained alive after $stage")

                assertTrue(PrivateExtensionRecovery.recover(extensions).isEmpty(), "Recovery failed after $stage")
                assertEquals(expectedVersion, target.readText(), "Wrong recovered version after $stage")
                assertEquals("keep", unrelated.readText())
                assertTrue(
                    extensions.listFiles().orEmpty().none { file ->
                        file.name.startsWith(".rollback-") ||
                            (file.name.startsWith("extension-stage-") && file.name.endsWith(".tmp")) ||
                            (file.name.startsWith(".rollback-stage-") && file.name.endsWith(".tmp"))
                    },
                    "Recovery artifacts remain after $stage",
                )
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Transaction fixture remained alive after $stage")
                }
            }
        }
    }

    @Test
    fun `interrupted copy preserves the original and clears only owned staging`() {
        val extensions = File(directory, "exts").apply { mkdirs() }
        val target = File(extensions, "reader.ext").apply { writeText("user extension") }
        val notes = File(extensions, "notes.tmp").apply { writeText("unrelated notes") }
        val rollbackStage = File(extensions, ".rollback-stage-00000000-0000-0000-0000-000000000001.tmp").apply { writeText("partial rollback") }
        val ready = File(directory, "copy-ready")
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        val main = PrivateExtensionRecoveryProcess::class.java
        val classpath = listOf(main, PrivateExtensionFileStore::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
            .distinct()
            .joinToString(File.pathSeparator)
        val process = ProcessBuilder(java.absolutePath, "-cp", classpath, main.name, target.absolutePath, ready.absolutePath, "copying")
            .redirectErrorStream(true)
            .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!ready.exists() && process.isAlive && System.nanoTime() < deadline) {
                process.waitFor(20, TimeUnit.MILLISECONDS)
            }
            assertTrue(ready.exists() && process.isAlive, "Copy fixture failed to become ready")
            assertEquals("user extension", target.readText())
            assertEquals(1, extensions.listFiles().orEmpty().count { it.name.startsWith("extension-stage-") })
            process.destroyForcibly()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Copy fixture did not terminate")
            assertTrue(PrivateExtensionRecovery.recover(extensions).isEmpty())
            assertEquals("user extension", target.readText())
            assertEquals("unrelated notes", notes.readText())
            assertFalse(rollbackStage.exists())
            assertEquals(setOf("reader.ext", "notes.tmp", ".mutation.lock"), extensions.listFiles().orEmpty().map { it.name }.toSet())
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Fixture process still alive")
            }
        }
    }

    @Test
    fun `recovery retains a blocked target and marker then native retry removes them`() {
        val target = File(directory, "reader.ext")
        val legacy = File(directory, "legacy.ext").apply { writeText("user extension") }
        PrivateExtensionFileStore().install(target, lease, false) { it.writeText("probe") }
        lease.ownership!!.close()
        val attempted = mutableListOf<File>()
        val excluded = PrivateExtensionRecovery.recover(directory) {
            attempted.add(it)
            false
        }
        assertEquals(listOf(target), attempted)
        assertEquals(setOf(target), excluded)
        assertEquals("probe", target.readText())
        assertEquals(1, anchors().size)
        assertEquals("user extension", legacy.readText())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertFalse(target.exists())
        assertTrue(anchors().isEmpty())
        assertEquals("user extension", legacy.readText())
        assertTrue(File(directory, ".lease-reader.ext.lock").exists())
    }

    @Test
    fun `fresh recovery removes an abandoned temporary extension but preserves legacy files`() {
        val target = File(directory, "reader.ext")
        val legacy = File(directory, "legacy.ext").apply { writeText("user extension") }
        PrivateExtensionFileStore().install(target, lease, false) { it.writeText("probe") }
        lease.ownership!!.close()
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertFalse(target.exists())
        assertTrue(anchors().isEmpty())
        assertEquals("user extension", legacy.readText())
        assertTrue(File(directory, ".lease-reader.ext.lock").exists())
    }

    @Test
    fun `interruption after backup before publish preserves target and permits retry`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        val staging = File(directory, "replacement").apply { writeText("new") }
        val backup = File(directory, ".rollback-reader.ext.backup")
        java.nio.file.Files.copy(target.toPath(), backup.toPath())
        PrivateExtensionRecovery.beginReplacement(target, staging, backup)
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(backup.exists())
        assertTrue(PrivateExtensionFileStore().install(target, null, false) { it.writeText("retry") })
    }

    @Test
    fun `interruption after publish restores original without touching a newer unrelated target`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        val staging = File(directory, "replacement").apply { writeText("new") }
        val backup = File(directory, ".rollback-reader.ext.backup")
        java.nio.file.Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, staging, backup)
        java.nio.file.Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("original", target.readText())
        assertFalse(journal.exists())

        staging.writeText("second")
        java.nio.file.Files.copy(target.toPath(), backup.toPath())
        PrivateExtensionRecovery.beginReplacement(target, staging, backup)
        val unrelated = File(directory, "unrelated").apply { writeText("later user version") }
        java.nio.file.Files.move(unrelated.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("later user version", target.readText())
        assertEquals("original", backup.readText())
        assertTrue(journal.exists())
    }

    @Test
    fun `committed replacement survives interrupted cleanup`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        val staging = File(directory, "replacement").apply { writeText("new") }
        val backup = File(directory, ".rollback-reader.ext.backup")
        java.nio.file.Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, staging, backup)
        java.nio.file.Files.move(staging.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        PrivateExtensionRecovery.commitReplacement(journal)
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("new", target.readText())
        assertFalse(backup.exists())
        assertFalse(journal.exists())
    }

    @Test
    fun `changed rollback copy is preserved and only its target is excluded`() {
        val target = File(directory, "reader.ext").apply { writeText("original") }
        val staging = File(directory, "replacement").apply { writeText("new") }
        val backup = File(directory, ".rollback-reader.ext.backup")
        java.nio.file.Files.copy(target.toPath(), backup.toPath())
        val journal = PrivateExtensionRecovery.beginReplacement(target, staging, backup)
        val unrelated = File(directory, "unrelated").apply { writeText("user backup") }
        java.nio.file.Files.move(unrelated.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("original", target.readText())
        assertEquals("user backup", backup.readText())
        assertTrue(journal.exists())
    }

    @Test
    fun `live lease cleanup follows the rollback ownership adopted by recovery`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore { source, destination ->
            if (source.name.endsWith(".backup")) throw IOException("blocked rollback")
            java.nio.file.Files.move(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(store.install(target, lease, false) { it.writeText("temporary") })
        assertThrows(IOException::class.java) {
            store.install(target, null, false, onInstalled = { throw IOException("notification failed") }) { it.writeText("replacement") }
        }
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("temporary", target.readText())
        // Match Android's directory-rename/delete behavior on Windows' read-only files.
        assertTrue(target.setWritable(true))
        var removed = 0
        store.removeTemporary(target, lease) { removed++ }
        assertEquals(1, removed)
        assertFalse(target.exists())
        assertTrue(anchors().isEmpty())
        assertFalse(File(directory, ".rollback-reader.ext.backup").exists())
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
    }

    @Test
    fun `unavailable directory scan fails closed`() {
        val blockedDirectory = spyk(directory)
        every { blockedDirectory.listFiles() } returns null
        assertThrows(IOException::class.java) { PrivateExtensionRecovery.recover(blockedDirectory) }
    }

    @Test
    fun `one target cleanup exception does not suppress healthy extensions`() {
        val target = File(directory, "reader.ext")
        val healthy = File(directory, "healthy.ext").apply { writeText("healthy") }
        PrivateExtensionFileStore().install(target, lease, false) { it.writeText("probe") }
        lease.ownership!!.close()
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory) { throw IOException("blocked target") })
        assertEquals("healthy", healthy.readText())
        assertEquals("probe", target.readText())
    }

    @Test
    fun `startup excludes but does not remove a live probe`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        store.install(target, lease, false) { it.writeText("probe") }
        assertEquals(setOf(target), PrivateExtensionRecovery.recover(directory))
        assertEquals("probe", target.readText())
        store.removeTemporary(target, lease)
        assertFalse(target.exists())
        assertTrue(anchors().isEmpty())
    }

    @Test
    fun `recovery preserves an identical normal replacement`() {
        val target = File(directory, "reader.ext")
        val store = PrivateExtensionFileStore()
        store.install(target, lease, false) { it.writeText("same bytes") }
        store.install(target, null, false) { it.writeText("same bytes") }
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        lease.ownership!!.close()
        assertTrue(PrivateExtensionRecovery.recover(directory).isEmpty())
        assertEquals("same bytes", target.readText())
        assertTrue(anchors().isEmpty())
    }

    @Test
    fun `failed old cleanup cannot delete a newer recovered lease`() {
        val target = spyk(File(directory, "reader.ext"))
        val store = PrivateExtensionFileStore()
        store.install(target, lease, false) { it.writeText("old probe") }
        every { target.delete() } returns false
        assertThrows(IOException::class.java) { store.removeTemporary(target, lease) }
        assertEquals(1, anchors().size)
        PrivateExtensionRecovery.recover(directory)
        every { target.delete() } answers { callOriginal() }
        store.install(target, newer, false) { it.writeText("new probe") }
        store.removeTemporary(target, lease)
        assertEquals("new probe", target.readText())
        assertTrue(store.remove(target, newer))
        assertTrue(anchors().isEmpty())
    }
}
