package exh.recs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tachiyomi.domain.taste.model.TasteProfile
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

// KMK -->
/**
 * v0.8.24 Focus picker layout. Many sources bring their own copy of the same tags, so a flat alphabetical list grew to
 * hundreds of rows. The picker now shows the small Format/Demographic/Access groups first, then the user's own tags,
 * then genres grouped by kind (see [FocusTagCategory]) with tags in what For You shows now first; long groups show a
 * preview with "Show all", and unrecognized tags are folded under "More tags". A search shows every match unfolded.
 * Selected tags always stay visible under "Your tags".
 */
internal object FocusPickerLayout {

    /** Provisional: how many of the user's strongest tags are listed first. */
    const val MAX_YOUR_TAGS = 16

    /** Provisional: chips shown in a category before "Show all". */
    const val CATEGORY_PREVIEW = 12

    data class Layout(
        val dimensions: List<Pair<FocusDimension, List<String>>>,
        val yourTags: List<String>,
        /** Genre tags by kind (see [FocusTagCategory]); tags in what For You shows now come first in each. */
        val categories: List<Pair<FocusTagCategory, List<String>>>,
    ) {
        /** Every genre tag, for search. */
        val allGenres: List<String> get() = yourTags + categories.flatMap { it.second }
    }

    fun build(
        options: List<String>,
        preferred: Set<String>,
        shownNow: Set<String>,
        selected: Set<String>,
    ): Layout {
        val byDimension = options.groupBy(::classifyFocusDimension)
        val genres = byDimension[FocusDimension.GENRE].orEmpty()
        val yours = genres.filter { it in preferred || it in selected }
        val byCategory = genres.filter { it !in yours }
            .sortedWith(compareBy({ it !in shownNow }, { it }))
            .groupBy(FocusTagCategory::classify)
        return Layout(
            dimensions = listOf(FocusDimension.FORMAT, FocusDimension.DEMOGRAPHIC, FocusDimension.PRESENTATION_ACCESS)
                .mapNotNull { dimension -> byDimension[dimension]?.takeIf { it.isNotEmpty() }?.let { dimension to it } },
            yourTags = yours,
            categories = FocusTagCategory.displayOrder.mapNotNull { category ->
                byCategory[category]?.takeIf { it.isNotEmpty() }?.let { category to it }
            },
        )
    }

    /** The user's strongest liked tags as focus groups: explicit likes, then recent and long-term taste. */
    fun preferredGroups(profile: TasteProfile, focusAliasMap: Map<String, String>): Set<String> {
        val ranked = buildList {
            profile.explicitTagPreferences.filterValues { it > 0 }.keys.forEach(::add)
            profile.recentTagWeights.filterValues { it > 0 }.entries.sortedByDescending { it.value }.forEach { add(it.key) }
            profile.learnedTagWeights.filterValues { it > 0 }.entries.sortedByDescending { it.value }.forEach { add(it.key) }
        }
        return ranked.asSequence()
            .map { canonicalFocusGroup(it, focusAliasMap) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_YOUR_TAGS)
            .toCollection(LinkedHashSet())
    }

    /**
     * Folds plural spellings into the singular when both appear among [groups] ("monsters" -> "monster", "witches" ->
     * "witch"), as extra focus aliases so the picker and the matching agree. Only merges what the user's own sources
     * actually show in both forms.
     */
    fun withPluralMerges(focusAliasMap: Map<String, String>, groups: Set<String>): Map<String, String> {
        val extra = groups.mapNotNull { group ->
            when {
                group.endsWith("es") && group.dropLast(2) in groups -> group to group.dropLast(2)
                group.endsWith("s") && !group.endsWith("ss") && group.dropLast(1) in groups -> group to group.dropLast(1)
                else -> null
            }
        }.toMap()
        return if (extra.isEmpty()) focusAliasMap else focusAliasMap + extra.filterKeys { it !in focusAliasMap }
    }
}

/**
 * Tag chips for Focus: tap to include, tap again to exclude, a third time to clear. [layout] decides the sections;
 * [searching] shows everything unfolded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FocusTagPicker(
    layout: FocusPickerLayout.Layout,
    include: Set<String>,
    exclude: Set<String>,
    searching: Boolean,
    onCycle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun Chips(groups: List<String>) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            groups.forEach { group -> FocusChip(group, group in include, group in exclude) { onCycle(group) } }
        }
    }

    @Composable
    fun Header(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        )
    }

    // Categories opened with "Show all"; Other starts folded entirely
    var expanded by remember { mutableStateOf(emptySet<FocusTagCategory>()) }

    LazyColumn(modifier = modifier) {
        layout.dimensions.forEach { (dimension, groups) ->
            item(key = "dim-${dimension.name}") {
                Header(stringResource(dimension.focusLabelRes()))
                Chips(groups)
            }
        }
        if (searching) {
            item(key = "search") {
                Header(stringResource(KMR.strings.rec_for_you_focus_dimension_genre))
                Chips(layout.allGenres)
            }
            return@LazyColumn
        }
        if (layout.yourTags.isNotEmpty()) {
            item(key = "yours") {
                Header(stringResource(KMR.strings.rec_focus_your_tags))
                Chips(layout.yourTags)
            }
        }
        layout.categories.forEach { (category, groups) ->
            val isOther = category == FocusTagCategory.OTHER
            val open = category in expanded
            item(key = "cat-${category.name}") {
                if (isOther) {
                    FoldHeader(
                        text = stringResource(KMR.strings.rec_focus_more_tags, groups.size),
                        open = open,
                        onToggle = { expanded = if (open) expanded - category else expanded + category },
                    )
                    if (open) Chips(groups)
                } else {
                    Header(stringResource(category.titleRes))
                    val shown = if (open) groups else groups.take(FocusPickerLayout.CATEGORY_PREVIEW)
                    Chips(shown)
                    if (groups.size > FocusPickerLayout.CATEGORY_PREVIEW) {
                        FoldHeader(
                            text = if (open) {
                                stringResource(KMR.strings.rec_focus_show_fewer)
                            } else {
                                stringResource(KMR.strings.rec_focus_show_all, groups.size)
                            },
                            open = open,
                            onToggle = { expanded = if (open) expanded - category else expanded + category },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FoldHeader(text: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun FocusChip(group: String, included: Boolean, excluded: Boolean, onClick: () -> Unit) {
    val excludedColors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onErrorContainer,
    )
    FilterChip(
        selected = included || excluded,
        onClick = onClick,
        label = { Text(group.toFocusDisplayLabel()) },
        leadingIcon = when {
            included -> ({ Icon(Icons.Outlined.Check, contentDescription = stringResource(KMR.strings.rec_for_you_focus_include)) })
            excluded -> ({ Icon(Icons.Outlined.Remove, contentDescription = stringResource(KMR.strings.rec_for_you_focus_exclude)) })
            else -> null
        },
        colors = if (excluded) excludedColors else FilterChipDefaults.filterChipColors(),
    )
}

internal fun FocusDimension.focusLabelRes() = when (this) {
    FocusDimension.FORMAT -> KMR.strings.rec_for_you_focus_dimension_format
    FocusDimension.DEMOGRAPHIC -> KMR.strings.rec_for_you_focus_dimension_demographic
    FocusDimension.PRESENTATION_ACCESS -> KMR.strings.rec_for_you_focus_dimension_presentation_access
    FocusDimension.GENRE -> KMR.strings.rec_for_you_focus_dimension_genre
}

/** neutral -> include -> exclude -> neutral */
internal fun cycleFocusGroup(group: String, include: Set<String>, exclude: Set<String>): Pair<Set<String>, Set<String>> =
    when (group) {
        in include -> (include - group) to (exclude + group)
        in exclude -> include to (exclude - group)
        else -> (include + group) to exclude
    }
// KMK <--
