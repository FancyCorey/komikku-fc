package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReaderSettingsScreenModelTest {

    @Test
    fun `reader settings stop collecting state when their owner closes`() = runTest {
        val state = MutableStateFlow(ReaderViewModel.State())
        val scope = CoroutineScope(coroutineContext + Job())
        val model = ReaderSettingsScreenModel(
            readerState = state,
            coroutineScope = scope,
            onChangeReadingMode = {},
            onChangeOrientation = {},
            preferences = mockk(),
        )
        try {
            assertEquals(0, state.subscriptionCount.value)
            backgroundScope.launch { model.viewerFlow.collect() }
            backgroundScope.launch { model.mangaFlow.collect() }
            runCurrent()
            assertEquals(2, state.subscriptionCount.value)

            scope.cancel()
            runCurrent()
            assertEquals(0, state.subscriptionCount.value)
        } finally {
            scope.cancel()
        }
    }
}
