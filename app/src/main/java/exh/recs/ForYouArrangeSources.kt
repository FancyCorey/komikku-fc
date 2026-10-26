package exh.recs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.domain.source.service.SourcePreferences
import exh.recs.settings.RecommendationsSettingsScreenModel
import exh.recs.settings.SourcePriorityItem
import exh.recs.sourceprefs.RecommendationSourcePreference
import exh.recs.sourceprefs.RecommendationSourcePreferenceStore
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.25 -->
/** Decides whether leaving the arrange mode changed the stored source order, so For You refreshes only when it did. */
internal object ForYouSourceArrangement {
    fun changed(orderBefore: String, orderAfter: String): Boolean =
        RecommendationSourceOrdering.parse(orderBefore) != RecommendationSourceOrdering.parse(orderAfter)
}

/**
 * Arrange the For You sources on the page itself. The same rows and the same model as Recommendation Settings > For You
 * feed (order, enable switch, like and dislike), so the stored order, its undo entry and the boosted top three behave
 * exactly as there. Done, or Back, leaves the mode and [onDone] reports whether the order changed.
 */
@Composable
internal fun ForYouArrangeSources(
    screenModel: RecommendationsSettingsScreenModel,
    onDone: (orderChanged: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()
    val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
    val orderBefore = remember { sourcePreferences.recommendationSourceOrder().get() }
    val lazyListState = rememberLazyListState()
    val sourcesState: SnapshotStateList<eu.kanade.tachiyomi.source.Source> = remember { state.orderedSources.toMutableStateList() }

    fun moveSource(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in sourcesState.indices || toIndex !in sourcesState.indices || fromIndex == toIndex) return
        val item = sourcesState.removeAt(fromIndex)
        sourcesState.add(toIndex, item)
        screenModel.setSourceOrder(sourcesState.map { it.id })
    }

    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        val fromId = from.key as? Long ?: return@rememberReorderableLazyListState
        val toId = to.key as? Long ?: return@rememberReorderableLazyListState
        val fromIndex = sourcesState.indexOfFirst { it.id == fromId }
        val toIndex = sourcesState.indexOfFirst { it.id == toId }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        moveSource(fromIndex, toIndex.coerceIn(0, sourcesState.lastIndex))
    }

    LaunchedEffect(state.orderedSources) {
        if (!reorderableState.isAnyItemDragging) {
            sourcesState.clear()
            sourcesState.addAll(state.orderedSources)
        }
    }

    val finish = { onDone(ForYouSourceArrangement.changed(orderBefore, sourcePreferences.recommendationSourceOrder().get())) }
    BackHandler(onBack = finish)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        ) {
            Text(
                text = stringResource(KMR.strings.rec_arrange_sources_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = finish) {
                Text(text = stringResource(KMR.strings.rec_arrange_sources_done))
            }
        }
        LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
            items(items = sourcesState, key = { it.id }) { source ->
                val index = sourcesState.indexOfFirst { it.id == source.id }
                val installedKey = RecommendationSourcePreferenceStore.installedKey(source.id)
                val isLiked = installedKey in state.likedSourceKeys
                val isDisliked = installedKey in state.dislikedSourceKeys
                ReorderableItem(reorderableState, source.id) {
                    SourcePriorityItem(
                        source = source,
                        rank = index + 1,
                        isBoosted = source.id in state.boostedSourceIds,
                        enabled = source.id !in state.disabledSourceIds,
                        status = state.sourceStatuses[source.id],
                        fitStats = state.sourceFitStats[source.id],
                        onToggle = { screenModel.toggleSource(source.id) },
                        isLiked = isLiked,
                        isDisliked = isDisliked,
                        onLike = {
                            screenModel.setInstalledSourcePreference(
                                source.id,
                                if (isLiked) RecommendationSourcePreference.NEUTRAL else RecommendationSourcePreference.LIKE,
                            )
                        },
                        onDislike = {
                            screenModel.setInstalledSourcePreference(
                                source.id,
                                if (isDisliked) RecommendationSourcePreference.NEUTRAL else RecommendationSourcePreference.DISLIKE,
                            )
                        },
                        onMoveUp = if (index > 0) {
                            { moveSource(index, index - 1) }
                        } else {
                            null
                        },
                        onMoveDown = if (index < sourcesState.lastIndex) {
                            { moveSource(index, index + 1) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}
// KMK <--
