package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The installer which installs, updates and uninstalls the extensions.
 *
 * @param context The application context.
 */
internal class ExtensionInstaller(
    private val context: Context,
    basePreferences: BasePreferences = Injekt.get(),
    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    // KMK -->
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()
    private val privateInstallMutex = Mutex()
    // KMK <--
    private val extensionInstaller = basePreferences.extensionInstaller()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    // KMK -->
    fun downloadAndInstall(
        url: String,
        extension: Extension,
        installerOverride: BasePreferences.ExtensionInstaller? = null,
        temporaryLease: PrivateExtensionFileStore.Lease? = null,
    ): Flow<InstallStep> {
        // KMK <--
        val pkgName = extension.pkgName +
            // KMK -->
            "_${extension.signatureHash}"
        val downloadId = pkgName.toDownloadId()
        // KMK <--
        cancelInstall(pkgName)

        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "extension_${UUID.randomUUID()}.apk")
            try {
                step.value = InstallStep.Downloading
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).downloadExtensionTo(tmpFile)
                ensureActive()

                step.value = InstallStep.Installing
                // KMK -->
                installApk(downloadId, tmpFile, step, installerOverride, temporaryLease)
                // KMK <--
            } catch (e: CancellationException) {
                tmpFile.delete()
                throw e
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    // Canceled
                } else {
                    logcat(LogPriority.ERROR, e)
                    step.value = InstallStep.Error
                }
                // KMK -->
                tmpFile.delete()
                // KMK <--
            }
        }

        activeJobs[pkgName] = job

        val finishDownload: suspend () -> Unit = {
            withContext(NonCancellable) {
                job.cancelAndJoin()
            }
            activeJobs.remove(pkgName, job)
            activeSteps.remove(downloadId, step)
        }
        temporaryLease?.finishDownload = finishDownload
        return step.asStateFlow().onCompletion { finishDownload() }
    }

    /**
     * Starts an intent to install the extension at the given uri.
     *
     * @param tempFile The file of the extension to install. Delete after use.
     */
    // KMK -->
    private suspend fun installApk(
        downloadId: Long,
        tempFile: File,
        step: MutableStateFlow<InstallStep>,
        installerOverride: BasePreferences.ExtensionInstaller? = null,
        temporaryLease: PrivateExtensionFileStore.Lease? = null,
    ) {
        val installer = installerOverride ?: extensionInstaller.get()
        // KMK <--
        when (installer) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                privateInstallMutex.withLock {
                    currentCoroutineContext().ensureActive()
                    try {
                        val installed = if (temporaryLease == null) {
                            ExtensionLoader.installPrivateExtensionFile(context, tempFile)
                        } else {
                            ExtensionLoader.installPrivateExtensionFile(context, tempFile, temporaryLease)
                        }
                        if (installed) {
                            step.value = InstallStep.Installed
                        } else {
                            step.value = InstallStep.Error
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Failed to read downloaded extension file." }
                        step.value = InstallStep.Error
                    } finally {
                        tempFile.delete()
                    }
                }
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }

    /**
     * Cancels extension install and remove from download manager and installer.
     */
    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, /* KMK --> */ pkgName.toDownloadId() /* KMK <-- */)
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            ExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        activeSteps[downloadId]?.let { it.value = step }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val EXTRA_DOWNLOAD_ID = "ExtensionInstaller.extra.DOWNLOAD_ID"

        // KMK -->
        /** Convert packageName to download ID avoiding negative number */
        private fun String.toDownloadId(): Long = hashCode().toLong() and 0xFFFFFFFFL
        // KMK <--
    }
}
