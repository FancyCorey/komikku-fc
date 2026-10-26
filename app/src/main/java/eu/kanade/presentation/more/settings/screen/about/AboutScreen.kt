package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.ORIGINAL_KOMIKKU_REPOSITORY
import eu.kanade.tachiyomi.data.updater.getGithubRepo
import eu.kanade.tachiyomi.ui.more.ComingUpdatesScreen
import eu.kanade.tachiyomi.ui.more.KmkRecsWhatsNewScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OriginalKomikkuWhatsNewScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import exh.recs.KmkRecsReleaseNotes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Github
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

// KMK v0.8.25: this fork's repository, for the Komikku FC links
private val FORK_REPOSITORY_URL = "https://github.com/${getGithubRepo()}"

class AboutScreen : Screen() {
    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var isCheckingUpdates by remember { mutableStateOf(false) }

        // KMK -->
        var isCheckingWhatsComing by remember { mutableStateOf(false) }
        // KMK <--

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader()
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.version),
                        subtitle = getVersionName(withBuildDate = true),
                        onPreferenceClick = {
                            val deviceInfo = CrashLogUtil(context).getDebugInfo()
                            context.copyToClipboard("Debug information", deviceInfo)
                        },
                    )
                }

                if (updaterEnabled) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.check_for_updates),
                            widget = {
                                AnimatedVisibility(visible = isCheckingUpdates) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 3.dp,
                                    )
                                }
                            },
                            onPreferenceClick = {
                                if (!isCheckingUpdates) {
                                    scope.launch {
                                        isCheckingUpdates = true

                                        checkVersion(
                                            context = context,
                                            onAvailableUpdate = { result ->
                                                val updateScreen = NewUpdateScreen(
                                                    versionName = result.release.version,
                                                    changelogInfo = result.release.info,
                                                    releaseLink = result.release.releaseLink,
                                                    downloadLink = result.release.downloadLink,
                                                )
                                                navigator.push(updateScreen)
                                            },
                                            onFinish = {
                                                isCheckingUpdates = false
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }

                // KMK v0.8.25 -->
                item { PreferenceGroupHeader(title = stringResource(KMR.strings.about_section_komikku_fc)) }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.whats_new),
                        subtitle = KmkRecsReleaseNotes.DISPLAY_VERSION_NAME,
                        onPreferenceClick = { navigator.push(KmkRecsWhatsNewScreen()) },
                    )
                }

                if (isReleaseBuildType || isDebugBuildType) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(KMR.strings.whats_coming),
                            widget = {
                                AnimatedVisibility(visible = isCheckingWhatsComing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 3.dp,
                                    )
                                }
                            },
                            onPreferenceClick = {
                                if (!isCheckingWhatsComing) {
                                    scope.launch {
                                        isCheckingWhatsComing = true

                                        checkVersion(
                                            context = context,
                                            onAvailableUpdate = { result ->
                                                val updateScreen = ComingUpdatesScreen(
                                                    versionName = result.release.version,
                                                    changelogInfo = result.release.info,
                                                    releaseLink = result.release.releaseLink,
                                                    downloadLink = result.release.downloadLink,
                                                )
                                                navigator.push(updateScreen)
                                            },
                                            onFinish = {
                                                isCheckingWhatsComing = false
                                            },
                                            peekIntoPreview = true,
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.about_komikku_fc_privacy),
                        subtitle = stringResource(KMR.strings.about_komikku_fc_privacy_subtitle),
                        onPreferenceClick = { uriHandler.openUri("$FORK_REPOSITORY_URL/blob/master/docs/kmk/privacy-and-data.md") },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.licenses),
                        onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = "GitHub",
                            icon = CustomIcons.Github,
                            url = FORK_REPOSITORY_URL,
                        )
                    }
                }

                item { PreferenceGroupHeader(title = stringResource(KMR.strings.about_section_original_komikku)) }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.whats_new),
                        subtitle = stringResource(KMR.strings.about_original_whats_new_subtitle),
                        onPreferenceClick = { navigator.push(OriginalKomikkuWhatsNewScreen()) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.help_translate),
                        onPreferenceClick = {
                            uriHandler.openUri(
                                "https://hosted.weblate.org/engage/komikku-app/",
                            )
                        },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.privacy_policy),
                        subtitle = stringResource(KMR.strings.about_original_privacy_subtitle),
                        onPreferenceClick = { uriHandler.openUri("https://komikku-app.github.io/privacy/") },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(KMR.strings.sponsor_original_developer),
                        onPreferenceClick = { uriHandler.openUri(Constants.SPONSOR) },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = stringResource(MR.strings.website),
                            icon = Icons.Outlined.Public,
                            url = "https://komikku-app.github.io",
                        )
                        LinkIcon(
                            label = "Discord",
                            icon = CustomIcons.Discord,
                            url = "https://discord.gg/85jB7V5AJR",
                        )
                        LinkIcon(
                            label = "GitHub",
                            icon = CustomIcons.Github,
                            url = "https://github.com/$ORIGINAL_KOMIKKU_REPOSITORY",
                        )
                    }
                }
                // KMK <--
            }
        }
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: () -> Unit,
        // KMK -->
        peekIntoPreview: Boolean = false,
        // KMK <--
    ) {
        val updateChecker = AppUpdateChecker(
            // KMK -->
            peekIntoPreview = peekIntoPreview,
            // KMK <--
        )
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(MR.strings.update_check_no_new_updates)
                    }
                    is GetApplicationRelease.Result.OsTooOld -> {
                        context.toast(MR.strings.update_check_eol)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.toast(with(context) { e.formattedMessage })
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish()
            }
        }
    }

    companion object {
        // KMK -->
        suspend fun getReleaseNotes(
            context: Context,
            onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
            onFinish: () -> Unit,
        ) {
            val updateChecker = AppUpdateChecker()
            withUIContext {
                try {
                    when (val result = withIOContext { updateChecker.getReleaseNotes() }) {
                        is GetApplicationRelease.Result.NewUpdate -> {
                            onAvailableUpdate(result)
                        }

                        else -> {}
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    context.toast(with(context) { e.formattedMessage })
                    logcat(LogPriority.ERROR, e)
                } finally {
                    onFinish()
                }
            }
        }
        // KMK <--

        fun getVersionName(withBuildDate: Boolean): String {
            return when {
                isDebugBuildType -> {
                    "Debug ${BuildConfig.COMMIT_SHA}".let {
                        if (withBuildDate) {
                            "$it (${getFormattedBuildTime()})"
                        } else {
                            it
                        }
                    }
                }

                isPreviewBuildType -> {
                    "Beta r${BuildConfig.COMMIT_COUNT}".let {
                        if (withBuildDate) {
                            "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                        } else {
                            "$it (${BuildConfig.COMMIT_SHA})"
                        }
                    }
                }

                else -> {
                    "Stable ${BuildConfig.VERSION_NAME}".let {
                        if (withBuildDate) {
                            "$it (${getFormattedBuildTime()})"
                        } else {
                            it
                        }
                    }
                }
            }
        }

        internal fun getFormattedBuildTime(): String {
            return try {
                LocalDateTime.ofInstant(
                    Instant.parse(BuildConfig.BUILD_TIME),
                    ZoneId.systemDefault(),
                )
                    .toDateTimestampString(
                        UiPreferences.dateFormat(
                            Injekt.get<UiPreferences>().dateFormat().get(),
                        ),
                    )
            } catch (_: Exception) {
                BuildConfig.BUILD_TIME
            }
        }
    }
}
