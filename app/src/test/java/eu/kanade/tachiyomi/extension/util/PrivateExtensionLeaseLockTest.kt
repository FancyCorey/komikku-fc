package eu.kanade.tachiyomi.extension.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PrivateExtensionLeaseLockTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `live lease excludes another process until it is closed`() {
        val file = File(directory, "reader.lock")
        val lease = PrivateExtensionLeaseLock.tryAcquire(file)
        assertNotNull(lease)
        lease!!.use {
            assertNull(PrivateExtensionLeaseLock.tryAcquire(file))
            assertEquals(3, otherProcessExitCode(file))
        }
        assertEquals(0, otherProcessExitCode(file))
        lease.close()
    }

    @Test
    fun `scope failure releases the lease for another process`() {
        val file = File(directory, "reader.lock")
        assertThrows(IOException::class.java) {
            PrivateExtensionLeaseLock.tryAcquire(file)!!.use {
                throw IOException("probe failed")
            }
        }
        assertEquals(0, otherProcessExitCode(file))
    }

    private fun otherProcessExitCode(file: File): Int {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = File(System.getProperty("java.home"), "bin/$executable")
        val main = PrivateExtensionLeaseLockProcess::class.java
        val classpath = File(main.protectionDomain.codeSource.location.toURI()).absolutePath
        val process = ProcessBuilder(java.absolutePath, "-cp", classpath, main.name, file.absolutePath)
            .redirectErrorStream(true)
            .start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Lease check process timed out")
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.exitValue() in listOf(0, 3), output)
            return process.exitValue()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
        }
    }
}
