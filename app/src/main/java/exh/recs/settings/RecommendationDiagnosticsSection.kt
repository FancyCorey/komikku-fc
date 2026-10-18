package exh.recs.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.SourceRuntimeHealthIssue
import eu.kanade.tachiyomi.source.SourceRuntimeHealthReporter
import tachiyomi.domain.taste.interactor.GetSourceRecommendationFit
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// KMK v0.8.24 -->
/**
 * Which part of the former single "Advanced and troubleshooting" list a [RecommendationDiagnosticsSettingsScreen]
 * instance shows. The redesign splits that list into task pages (Speed and data, Linked versions and ratings,
 * Troubleshooting, plus the saved focus modes list reached from Your taste) without moving any control: every item
 * keeps its key, screen-model method and preference. [ALL] keeps the old behaviour for deep links that still ask for it.
 */
enum class RecommendationDiagnosticsSection(
    val titleRes: StringResource,
    private val keys: Set<String>?,
) {
    ALL(KMR.strings.rec_settings_index_diagnostics, null),

    SPEED_AND_DATA(
        KMR.strings.rec_hub_speed_data,
        setOf(
            "display_performance_header",
            "result_budget",
            "refresh_hint",
            "discovery_cache_header",
            "enrichment_cap",
            "discovery_effort_header",
            "discovery_effort_content",
            "data_saving_header",
            "data_saving_content",
            "discovery_candidate_budget",
            "clear_discovery_history",
            "group_preview_budget",
        ),
    ),

    LINKED_VERSIONS(
        KMR.strings.rec_hub_linked_versions,
        setOf(
            "versions_quality_header",
            "same_manga_results_per_source",
            "same_manga_preselect",
            "rated_manga_action_placement",
            "chapter_completion_rating_prompt",
            "chapter_completion_rating_other_versions_prompt",
            "confirmed_tracked_version_rating_propagation",
            "confirmed_tracked_version_local_tracking_propagation",
            "automatic_local_tracking_status_inference",
            "automatic_rated_group_primary",
            "identity_review",
            // KMK v0.8.24: Best Version is part of how linked versions work, so it is a section of this page
            "best_version_header",
            "best_version_sample_size",
            "best_version_avoid_first_pages",
            "management_header",
            "quality_signal_history_entry",
        ),
    ),

    FOCUS_MODES(
        KMR.strings.rec_hub_focus_modes,
        setOf("focus_header", "focus_explanation", "focus_saved_modes"),
    ),

    TROUBLESHOOTING(
        KMR.strings.rec_hub_troubleshooting,
        setOf(
            "runtime_health_issues",
            "auto_check_skipped_sources",
            "taste_diagnostics_header",
            "taste_diagnostics_content",
            "source_metadata_tag_diagnostics_header",
            "source_metadata_tag_diagnostics_content",
        ),
    ),
    ;

    fun includes(itemKey: String): Boolean = keys == null || itemKey in keys

    companion object {
        /** [requested] unless it is [ALL] and [anchor] belongs to exactly one task page. */
        fun resolve(requested: RecommendationDiagnosticsSection, anchor: String?): RecommendationDiagnosticsSection {
            if (requested != ALL || anchor == null) return requested
            return entries.firstOrNull { it != ALL && it.includes(anchor) } ?: ALL
        }

        /** Item keys that moved to the Your taste page. */
        val MOVED_TO_TASTE = setOf("recommendation_languages_header", "recommendation_languages_content")
    }
}

/** Process-lifetime source loading problems, live. Shared by the Troubleshooting page and the hub's row summary. */
@Composable
internal fun rememberSourceHealthIssues(): State<List<SourceRuntimeHealthIssue>> {
    val flow = remember { SourceRuntimeHealthReporter.issuesFlow(Injekt.get<ExtensionManager>()) }
    return flow.collectAsState(initial = emptyList())
}

/**
 * Names of sources For You is currently skipping because of the automatic check's results. Empty unless the automatic check
 * and "Use results on For You" are both on. Read once when the page opens; failures read as "none".
 */
@Composable
internal fun rememberAutoCheckSkippedSourceNames(): State<List<String>> =
    produceState(initialValue = emptyList<String>()) {
        val preferences = Injekt.get<eu.kanade.domain.source.service.SourcePreferences>()
        value = if (
            preferences.recommendationAutoRecheckEnabled().get() &&
            preferences.recommendationAutoRecheckApplyToForYou().get()
        ) {
            try {
                val fits = Injekt.get<GetSourceRecommendationFit>().awaitAll()
                val skipped = exh.recs.evaluation.SourceAutoRecheckPolicy.skippedSourceIds(
                    fits,
                    exh.recs.SourceFitStatsStore.parse(preferences.recommendationSourceFitStats().get()),
                    System.currentTimeMillis(),
                )
                fits.filter { it.sourceId in skipped }.map { it.sourceName }.distinct().sorted()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }
// KMK <--
