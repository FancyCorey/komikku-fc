package eu.kanade.tachiyomi.extension.util

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class PrivateExtensionRecoveryCrashService : Service() {
    override fun onBind(intent: Intent?): IBinder = Binder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = File(requireNotNull(intent?.getStringExtra(EXTRA_TARGET)))
        val ready = File(requireNotNull(intent.getStringExtra(EXTRA_READY)))
        val stage = intent.getStringExtra(EXTRA_STAGE)
        Thread {
            try {
                if (stage == null) {
                    PrivateExtensionFileStore { source, destination ->
                        Files.move(source.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                        if (destination.canonicalFile == target.canonicalFile) crash(ready, "replacement-published")
                    }.install(target, null, false) { it.writeText("replacement") }
                    ready.writeText("replacement-was-not-published")
                } else {
                    crashAfterStage(target, ready, stage)
                }
            } catch (failure: Throwable) {
                ready.writeText("worker-failed:${failure.javaClass.simpleName}")
            } finally {
                Process.killProcess(Process.myPid())
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun crashAfterStage(target: File, ready: File, stage: String) {
        val replacementBytes = byteArrayOf(0x00, 0x55, 0x10, 0x7F, 0x22, 0x01)
        val installed = PrivateExtensionFileStore().install(
            target = target,
            lease = null,
            sharedInstalled = false,
            onRecoveryBoundary = { boundary ->
                if (boundary.name == stage) crash(ready, stage)
            },
        ) { staging ->
            staging.writeBytes(replacementBytes)
        }
        check(installed) { "Replacement was not installed" }
    }

    private fun crash(ready: File, stage: String): Nothing {
        ready.writeText(stage)
        Process.killProcess(Process.myPid())
        error("Process kill returned")
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_READY = "ready"
        const val EXTRA_STAGE = "stage"
        const val STAGE_JOURNAL = "JOURNAL_PUBLISHED"
        const val STAGE_ROLLBACK_MOVED = "ROLLBACK_MOVED"
        const val STAGE_REPLACEMENT_PUBLISHED = "REPLACEMENT_PUBLISHED"
        const val STAGE_COMMITTED = "COMMITTED"
        const val STAGE_BACKUP_CLEANED = "BACKUP_CLEANED"
    }
}
