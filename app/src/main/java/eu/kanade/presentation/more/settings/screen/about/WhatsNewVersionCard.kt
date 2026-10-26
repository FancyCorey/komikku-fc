package eu.kanade.presentation.more.settings.screen.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.manga.components.MarkdownRender
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

// KMK v0.8.25 -->
/**
 * One release in a What's New list. Collapsed it shows the version and a one-line summary; the release's notes are only
 * composed once it is opened, so a long history no longer renders as one giant block. Used by both the Komikku FC history
 * and the original Komikku release notes.
 */
@Composable
fun WhatsNewVersionCard(
    stateKey: String,
    title: String,
    summary: String,
    markdown: String,
    expandedByDefault: Boolean,
    modifier: Modifier = Modifier,
    onOpenLink: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(expandedByDefault) }
    val stateText = stringResource(
        if (expanded) KMR.strings.accessibility_state_expanded else KMR.strings.accessibility_state_collapsed,
    )
    val clickLabel = stringResource(
        if (expanded) KMR.strings.accessibility_collapse_section else KMR.strings.accessibility_expand_section,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = clickLabel, role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = stateText }
                .padding(vertical = MaterialTheme.padding.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (!expanded && summary.isNotBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
        if (expanded) {
            MarkdownRender(content = markdown, flavour = GFMFlavourDescriptor())
            if (onOpenLink != null) {
                TextButton(onClick = onOpenLink) {
                    Text(text = stringResource(KMR.strings.about_original_whats_new_open_release))
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.small))
    }
}
// KMK <--
