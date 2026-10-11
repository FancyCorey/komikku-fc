package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.android.material.textview.MaterialTextView
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

internal class OpenSourceLibraryLicenseScreen(
    private val name: String,
    private val website: String?,
    internal val licenses: List<OpenSourceLicenseEntry>,
    internal val notice: OpenSourceLicenseNotice?,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current

        Scaffold(
            topBar = {
                AppBar(
                    title = name,
                    navigateUp = navigator::pop,
                    actions = {
                        if (!website.isNullOrEmpty()) {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.website),
                                        icon = Icons.Default.Public,
                                        onClick = { uriHandler.openUri(website) },
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = it,
                )
            },
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                licenses.forEach { license ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (license.name.isNotBlank()) {
                            Text(license.name, style = MaterialTheme.typography.titleMedium)
                        }
                        if (license.content.isNotBlank()) {
                            HtmlLicenseText(html = license.content)
                        }
                        license.url?.let { url ->
                            TextButton(onClick = { uriHandler.openUri(url) }) {
                                Text(stringResource(MR.strings.action_open_in_browser))
                            }
                        }
                    }
                }
                notice?.takeIf { it.content.isNotBlank() }?.let {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(it.title, style = MaterialTheme.typography.titleMedium)
                        HtmlLicenseText(html = android.text.TextUtils.htmlEncode(it.content).replace("\n", "<br>"))
                    }
                }
            }
        }
    }

    @Composable
    private fun HtmlLicenseText(html: String) {
        AndroidView(
            factory = {
                MaterialTextView(it)
            },
            update = {
                it.text = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)
            },
        )
    }
}
