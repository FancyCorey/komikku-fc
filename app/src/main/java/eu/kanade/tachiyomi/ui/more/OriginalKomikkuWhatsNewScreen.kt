package eu.kanade.tachiyomi.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.settings.screen.about.WhatsNewVersionCard
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.ORIGINAL_KOMIKKU_REPOSITORY
import eu.kanade.tachiyomi.util.system.openInBrowser
import exh.recs.KmkRecsReleaseNotesGroupingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.model.Release
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

// KMK v0.8.25 -->
/**
 * The original Komikku project's own release notes, one card per release (newest open), loaded when the screen opens.
 * Komikku FC's own history is [KmkRecsWhatsNewScreen]. The screen takes no arguments, so a saved navigator stack can
 * restore it; the releases are loaded by [OriginalKomikkuWhatsNewScreenModel].
 */
class OriginalKomikkuWhatsNewScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { OriginalKomikkuWhatsNewScreenModel() }
        val state by screenModel.state.collectAsState()

        InfoScreen(
            icon = Icons.Outlined.NewReleases,
            headingText = stringResource(KMR.strings.about_original_whats_new_title),
            subtitleText = stringResource(KMR.strings.about_original_whats_new_subtitle),
            acceptText = stringResource(MR.strings.action_ok),
            onAcceptClick = { navigator.pop() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.padding.large),
            ) {
                when (val current = state) {
                    OriginalKomikkuWhatsNewScreenModel.State.Loading -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.padding.large),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    is OriginalKomikkuWhatsNewScreenModel.State.Error -> {
                        val message = current.cause?.let { with(context) { it.formattedMessage } }
                            ?: stringResource(KMR.strings.about_original_whats_new_error)
                        Text(text = message, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = screenModel::load) {
                                Text(text = stringResource(MR.strings.action_retry))
                            }
                            TextButton(
                                onClick = { context.openInBrowser("https://github.com/$ORIGINAL_KOMIKKU_REPOSITORY/releases") },
                            ) {
                                Text(text = stringResource(KMR.strings.about_original_whats_new_open_releases))
                            }
                        }
                    }
                    is OriginalKomikkuWhatsNewScreenModel.State.Loaded -> {
                        current.releases.forEachIndexed { index, release ->
                            WhatsNewVersionCard(
                                stateKey = "original-komikku-${release.version}",
                                title = release.version,
                                summary = KmkRecsReleaseNotesGroupingPolicy.summaryOf(release.info),
                                markdown = release.info.trimIndent(),
                                expandedByDefault = index == 0,
                                onOpenLink = { context.openInBrowser(release.releaseLink) },
                            )
                        }
                    }
                }
            }
        }
    }
}

class OriginalKomikkuWhatsNewScreenModel(
    private val loadReleases: suspend () -> List<Release> = { AppUpdateChecker().getOriginalKomikkuReleases() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StateScreenModel<OriginalKomikkuWhatsNewScreenModel.State>(State.Loading) {

    sealed interface State {
        data object Loading : State

        /** [cause] is null when the request worked but no release was found. */
        data class Error(val cause: Throwable?) : State
        data class Loaded(val releases: List<Release>) : State
    }

    init {
        load()
    }

    fun load() {
        mutableState.update { State.Loading }
        screenModelScope.launch(ioDispatcher) {
            try {
                val releases = loadReleases()
                mutableState.update { if (releases.isEmpty()) State.Error(null) else State.Loaded(releases) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                mutableState.update { State.Error(e) }
            }
        }
    }
}
// KMK <--
