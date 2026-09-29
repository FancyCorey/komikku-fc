package eu.kanade.presentation.manga

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [30], qualifiers = "w360dp-h640dp-xxhdpi")
class MangaScreenLastReadPositionComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun middleChapterJumpHasNoAnimatedReversalAndEndsCentered() {
        val targetId = 50L
        val chapterIds = mutableStateOf((0L until 100L).toList())
        val capturePresentedFrames = mutableStateOf(false)
        val targetLayoutOffsets = mutableListOf<Float>()
        val presentedTargetOffsets = CopyOnWriteArrayList<Float>()
        lateinit var controller: LastReadChapterJumpController
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState
        var unavailable = false

        composeTestRule.setContent {
            controller = rememberLastReadChapterJumpController()
            listState = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
            ) {
                items(chapterIds.value, key = { "chapter-$it" }) { id ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (id % 3L == 0L) 88.dp else 56.dp)
                            .onGloballyPositioned { coordinates ->
                                if (id == targetId) {
                                    targetLayoutOffsets += coordinates.localToRoot(Offset.Zero).y
                                }
                            },
                    )
                }
            }
            LaunchedEffect(listState) {
                snapshotFlow { capturePresentedFrames.value }.first { it }
                repeat(3) {
                    if (!capturePresentedFrames.value) return@repeat
                    withFrameNanos { }
                    listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key == "chapter-$targetId" }
                        ?.let { presentedTargetOffsets += it.offset.toFloat() }
                }
            }
        }

        lateinit var job: Job
        composeTestRule.runOnIdle {
            capturePresentedFrames.value = true
            job = controller.jump(
                chapterId = targetId,
                state = listState,
                currentIndexOfChapter = { id -> chapterIds.value.indexOf(id).takeIf { it >= 0 } },
                chapterCount = { chapterIds.value.size },
                onUnavailable = { unavailable = true },
            )
        }
        composeTestRule.waitUntil(5_000) { job.isCompleted && presentedTargetOffsets.isNotEmpty() }
        composeTestRule.runOnIdle { capturePresentedFrames.value = false }
        composeTestRule.waitForIdle()

        assertFalse(unavailable)
        assertTargetCentered(listState, targetId)
        assertEquals(
            "the target should not be presented at its top-aligned intermediate position",
            1,
            presentedTargetOffsets.distinctBy { it.toInt() }.size,
        )
        assertTrue("target layout should be observed during the jump", targetLayoutOffsets.isNotEmpty())
        val distinctOffsets = targetLayoutOffsets.distinctBy { it.toInt() }
        assertTrue("direct jump should not animate through a long detour: $distinctOffsets", distinctOffsets.size <= 2)
        if (distinctOffsets.size == 2) {
            assertTrue(
                "target should move directly toward its final position: $distinctOffsets",
                distinctOffsets[1] >= distinctOffsets[0],
            )
        }
    }

    @Test
    fun middleChapterJumpRemainsCenteredAtLargerFontScale() {
        val targetId = 50L
        val chapterIds = mutableStateOf((0L until 100L).toList())
        val fontScale = mutableStateOf(1f)
        lateinit var controller: LastReadChapterJumpController
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            controller = rememberLastReadChapterJumpController()
            listState = rememberLazyListState()
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale.value)) {
                LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                    items(chapterIds.value, key = { "chapter-$it" }) { id ->
                        Text(
                            text = "Chapter $id — " + "A longer title that wraps into additional lines. "
                                .repeat(if (id % 4L == 0L) 2 else 1),
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
                        )
                    }
                }
            }
        }

        fun launchJump(): Job = controller.jump(
            chapterId = targetId,
            state = listState,
            currentIndexOfChapter = { id -> chapterIds.value.indexOf(id).takeIf { it >= 0 } },
            chapterCount = { chapterIds.value.size },
            onUnavailable = { error("Expected target chapter to remain available") },
        )

        lateinit var normalFontJob: Job
        composeTestRule.runOnIdle { normalFontJob = launchJump() }
        composeTestRule.waitUntil(5_000) { normalFontJob.isCompleted }
        var normalTargetSize = 0
        composeTestRule.runOnIdle { normalTargetSize = targetSize(listState, targetId) }
        composeTestRule.runOnIdle { fontScale.value = 1.6f }
        composeTestRule.waitForIdle()

        lateinit var largeFontJob: Job
        composeTestRule.runOnIdle { largeFontJob = launchJump() }
        composeTestRule.waitUntil(5_000) { largeFontJob.isCompleted }
        composeTestRule.waitForIdle()

        var largeTargetSize = 0
        composeTestRule.runOnIdle { largeTargetSize = targetSize(listState, targetId) }
        assertTrue("larger font scale should change the measured row height", largeTargetSize > normalTargetSize)
        assertTargetCentered(listState, targetId)
    }

    @Test
    fun repeatedJumpPressCancelsThePriorUnavailableAction() {
        val chapterIds = mutableStateOf((0L until 100L).toList())
        val unavailableStarted = CompletableDeferred<Unit>()
        lateinit var controller: LastReadChapterJumpController
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            controller = rememberLastReadChapterJumpController()
            listState = rememberLazyListState()
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(chapterIds.value, key = { "chapter-$it" }) { id ->
                    Box(Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }

        lateinit var firstJob: Job
        composeTestRule.runOnIdle {
            firstJob = controller.jump(
                chapterId = 1_000L,
                state = listState,
                currentIndexOfChapter = { null },
                chapterCount = { chapterIds.value.size },
                onUnavailable = {
                    unavailableStarted.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        composeTestRule.waitUntil(5_000) { unavailableStarted.isCompleted }

        lateinit var secondJob: Job
        composeTestRule.runOnIdle {
            secondJob = controller.jump(
                chapterId = 25L,
                state = listState,
                currentIndexOfChapter = { id -> chapterIds.value.indexOf(id).takeIf { it >= 0 } },
                chapterCount = { chapterIds.value.size },
                onUnavailable = { error("Expected replacement target to be available") },
            )
        }
        composeTestRule.waitUntil(5_000) { secondJob.isCompleted }
        composeTestRule.waitForIdle()

        assertTrue("a repeated press must cancel the earlier action", firstJob.isCancelled)
        assertFalse(secondJob.isCancelled)
        assertTargetCentered(listState, 25L)
    }

    @Test
    fun targetIsReResolvedWhenTheDisplayedListChangesDuringTheJump() {
        val targetId = 50L
        val chapterIds = mutableStateOf((0L until 100L).toList())
        val listChanged = CompletableDeferred<Unit>()
        val resolvedPositions = mutableListOf<Int?>()
        lateinit var controller: LastReadChapterJumpController
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            controller = rememberLastReadChapterJumpController()
            listState = rememberLazyListState()
            LaunchedEffect(listState) {
                snapshotFlow { listState.firstVisibleItemIndex }.first { it == 50 }
                val reordered = chapterIds.value.toMutableList().also { ids ->
                    ids.remove(targetId)
                    ids.add(75, targetId)
                }
                chapterIds.value = reordered
                listChanged.complete(Unit)
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(chapterIds.value, key = { "chapter-$it" }) { id ->
                    Box(Modifier.fillMaxWidth().height(if (id % 2L == 0L) 72.dp else 48.dp))
                }
            }
        }

        lateinit var job: Job
        composeTestRule.runOnIdle {
            job = controller.jump(
                chapterId = targetId,
                state = listState,
                currentIndexOfChapter = { id ->
                    chapterIds.value.indexOf(id).takeIf { it >= 0 }.also { resolvedPositions += it }
                },
                chapterCount = { chapterIds.value.size },
                onUnavailable = { error("Target remains in the refreshed list") },
            )
        }
        composeTestRule.waitUntil(5_000) { listChanged.isCompleted && job.isCompleted }
        composeTestRule.waitForIdle()

        assertTrue("target should have been resolved after the list update", resolvedPositions.contains(75))
        assertEquals(75, listState.layoutInfo.visibleItemsInfo.single { it.key == "chapter-$targetId" }.index)
        assertTargetCentered(listState, targetId)
    }

    @Test
    fun leavingTheCompositionCancelsTheJumpWhileItsUnavailableActionIsSuspended() {
        val unavailableStarted = CompletableDeferred<Unit>()
        val showContent = mutableStateOf(true)
        lateinit var controller: LastReadChapterJumpController
        lateinit var listState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            if (showContent.value) {
                controller = rememberLastReadChapterJumpController()
                listState = rememberLazyListState()
                LazyColumn(state = listState) {
                    items(3, key = { "chapter-$it" }) { Box(Modifier.height(56.dp)) }
                }
            }
        }

        lateinit var job: Job
        composeTestRule.runOnIdle {
            job = controller.jump(
                chapterId = 999L,
                state = listState,
                currentIndexOfChapter = { null },
                chapterCount = { 3 },
                onUnavailable = {
                    unavailableStarted.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        composeTestRule.waitUntil(5_000) { unavailableStarted.isCompleted }

        composeTestRule.runOnIdle { showContent.value = false }
        composeTestRule.waitForIdle()
        assertTrue("composition disposal should cancel the jump", job.isCancelled)
    }

    @Test
    fun disposingScreenCancelsPositioningAfterDirectScrollBeforeMeasuredFrame() {
        val chapterIds = (0L until 100L).toList()
        val showScreen = mutableStateOf(true)
        val screenGeneration = mutableStateOf(0)
        val directScrollReached = CompletableDeferred<Unit>()
        val cancellationObserved = CompletableDeferred<Unit>()
        lateinit var currentController: LastReadChapterJumpController
        lateinit var currentListState: androidx.compose.foundation.lazy.LazyListState

        composeTestRule.setContent {
            if (showScreen.value) {
                key(screenGeneration.value) {
                    val controller = rememberLastReadChapterJumpController()
                    val listState = rememberLazyListState()
                    SideEffect {
                        currentController = controller
                        currentListState = listState
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                        items(chapterIds, key = { "chapter-$it" }) { Box(Modifier.height(56.dp)) }
                    }
                }
            }
        }

        val oldController = currentController
        val oldListState = currentListState
        lateinit var job: Job
        composeTestRule.runOnIdle {
            job = oldController.jump(
                chapterId = 50L,
                state = oldListState,
                currentIndexOfChapter = { it.toInt().takeIf { index -> index in chapterIds.indices } },
                chapterCount = { chapterIds.size },
                onUnavailable = { error("Expected target chapter to be available") },
                afterDirectScroll = {
                    directScrollReached.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancellationObserved.complete(Unit)
                    }
                },
            )
        }
        composeTestRule.waitUntil(5_000) { directScrollReached.isCompleted }
        assertEquals(50, oldListState.firstVisibleItemIndex)

        composeTestRule.runOnIdle { showScreen.value = false }
        composeTestRule.waitForIdle()
        assertTrue("the suspension should observe composition cancellation", cancellationObserved.isCompleted)
        assertTrue("composition disposal should cancel positioning", job.isCancelled)

        composeTestRule.runOnIdle {
            screenGeneration.value++
            showScreen.value = true
        }
        composeTestRule.waitForIdle()

        assertTrue("disposal should cancel the job between direct scroll and layout centering", job.isCancelled)
        assertEquals("a new screen should keep its own initial list position", 0, currentListState.firstVisibleItemIndex)
        assertEquals("the cancelled old list should not continue scrolling", 50, oldListState.firstVisibleItemIndex)
    }

    private fun assertTargetCentered(
        state: androidx.compose.foundation.lazy.LazyListState,
        targetId: Long,
    ) {
        val layoutInfo = state.layoutInfo
        val target = layoutInfo.visibleItemsInfo.single { it.key == "chapter-$targetId" }
        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val targetCenter = target.offset + target.size / 2f
        assertTrue("chapter $targetId should finish centered", kotlin.math.abs(targetCenter - viewportCenter) <= 1f)
    }

    private fun targetSize(
        state: androidx.compose.foundation.lazy.LazyListState,
        targetId: Long,
    ): Int = state.layoutInfo.visibleItemsInfo.single { it.key == "chapter-$targetId" }.size
}
