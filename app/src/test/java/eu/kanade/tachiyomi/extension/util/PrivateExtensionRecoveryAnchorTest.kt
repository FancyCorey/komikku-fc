package eu.kanade.tachiyomi.extension.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class PrivateExtensionRecoveryAnchorTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `anchor identifies the published file after its staging name is gone`() {
        val staging = File(directory, "extension.tmp").apply { writeText("probe") }
        val target = File(directory, "reader.ext")
        val ownership = PrivateExtensionRecovery.begin(target, staging)
        Files.move(staging.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)

        assertFalse(staging.exists())
        assertTrue(ownership.owns(target))
        assertTrue(target.delete())
        assertTrue(ownership.anchor.exists())
        assertTrue(ownership.anchor.delete())
        ownership.close()
        File(directory, ".lease-reader.ext.lock").delete()
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `identical bytes in a user replacement do not match the old anchor`() {
        val target = File(directory, "reader.ext").apply { writeText("same bytes") }
        val ownership = PrivateExtensionRecovery.begin(target, target)
        val replacement = File(directory, "update.tmp").apply { writeText("same bytes") }
        Files.move(replacement.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)

        assertFalse(ownership.owns(target))
        assertTrue(ownership.anchor.delete())
        ownership.close()
        assertEquals("same bytes", target.readText())
        assertTrue(target.exists())
    }
}
