package exh.visual

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import exh.recs.SourceFitStats
import exh.recs.settings.SourcePriorityItem
import exh.util.FakePreferenceStore
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

// KMK v0.8.24: the For You feed source rows (Recommendation Settings > For You feed) with every status label shown, at a
// phone width, a tablet pane width and a large font size.
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w800dp-h1280dp-xhdpi")
class SourcePriorityItemRoborazziTest {

    @Before
    fun setUp() {
        Injekt.addSingleton(SourcePreferences(FakePreferenceStore()))
    }

    private fun source(id: Long, name: String): Source = object : Source {
        override val id = id
        override val name = name
        override val lang = "en"
        override val supportsLatest = false
        override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)
        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
            MangasPage(emptyList(), false)
        override suspend fun getMangaUpdate(
            manga: SManga,
            chapters: List<eu.kanade.tachiyomi.source.model.SChapter>,
            fetchDetails: Boolean,
            fetchChapters: Boolean,
        ) = eu.kanade.tachiyomi.source.model.SMangaUpdate(manga, emptyList())
        override suspend fun getPageList(chapter: eu.kanade.tachiyomi.source.model.SChapter) =
            emptyList<eu.kanade.tachiyomi.source.model.Page>()
        override fun getFilterList(): FilterList = FilterList()
    }

    private val greatFit = SourceFitStats(
        sourceId = 1, runCount = 10, shownCount = 9, noMatchCount = 0, filteredOutCount = 0, errorCount = 0,
        hiddenByDuplicateCount = 0, totalVisibleCandidates = 60, lastSuccessAt = 0, lastErrorAt = 0, updatedAt = 0,
        topPicksContributionCount = 4,
    )

    @Composable
    private fun Rows(width: Int) {
        val listState = rememberLazyListState()
        val reorderable = rememberReorderableLazyListState(listState) { _, _ -> }
        MaterialTheme {
            Surface {
                LazyColumn(state = listState, modifier = Modifier.width(width.dp)) {
                    val rows = listOf(
                        Triple(source(1, "Vortex Scans"), true, greatFit),
                        Triple(source(2, "A Source With A Rather Long Name Scans"), true, greatFit.copy(sourceId = 2)),
                        Triple(source(3, "Asura Scans"), false, greatFit.copy(sourceId = 3, topPicksContributionCount = 0)),
                    )
                    items(rows.size, key = { rows[it].first.id }) { index ->
                        val (src, boosted, stats) = rows[index]
                        ReorderableItem(reorderable, src.id) {
                            SourcePriorityItem(
                                source = src, rank = index + 1, isBoosted = boosted, enabled = true, status = null,
                                fitStats = stats, isLiked = false, isDisliked = false, onLike = {}, onDislike = {},
                                onToggle = {}, onMoveUp = null, onMoveDown = null,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun phoneWidth() = captureRoboImage { Rows(360) }

    @Test
    fun tabletPaneWidth() = captureRoboImage { Rows(560) }

    @Test
    fun largeFont() = captureRoboImage {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 1.3f)) { Rows(360) }
    }
}
