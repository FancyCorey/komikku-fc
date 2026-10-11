package eu.kanade.tachiyomi.ui.reader.bridge

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** Keeps failed alternate-source journeys actionable at both chooser levels. */
class AlternateSourceReaderRecoverySourceTest {

    @Test
    fun `chapter chooser exposes retry for a failed chapter discovery`() {
        val dialog = source("app/src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt")
        val chapter = dialog.substringAfter("is AlternateSourceReaderPresentation.ChoosingChapter")
            .substringBefore("is AlternateSourceReaderPresentation.ConfirmPair")

        assertTrue(chapter.contains("retryAction = onRetry"))
        assertTrue(dialog.contains("content is AlternateSourceReaderLoadState.Failed ||"))
        assertTrue(dialog.contains("MR.strings.action_retry"))
    }

    @Test
    fun `source chooser exposes retry for failed live discovery`() {
        val dialog = source("app/src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt")
        val sourceChooser = dialog.substringAfter("is AlternateSourceReaderPresentation.ChoosingSource")
            .substringBefore("is AlternateSourceReaderPresentation.ChoosingChapter")

        assertTrue(sourceChooser.contains("retryAction = onRetry"))
        assertTrue(dialog.contains("content is AlternateSourceReaderLoadState.Failed ||"))
        assertTrue(dialog.contains("MR.strings.action_retry"))
    }

    @Test
    fun `superseded chooser work does not join blocked cleanup and dismiss cancels the owned producer`() {
        val viewModel = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val candidateWork = viewModel.substringAfter("private fun launchAlternateSourceCandidateWork")
            .substringBefore("private fun launchAlternateSourceCommand")
        val dismiss = viewModel.substringAfter("fun dismissAlternateSourcePresentation()")
            .substringBefore("fun retryAlternateSourcePresentation()")
        val routeChange = viewModel.substringAfter("private fun invalidateAlternateSourceCandidateStateForRouteChange()")
            .substringBefore("suspend fun enterAlternateSourceBridge")

        assertTrue(candidateWork.contains("previous?.cancel()"))
        assertTrue(candidateWork.contains("if (generation != alternateSourceCandidateGeneration)"))
        assertTrue(!candidateWork.contains("cancelAndJoin"))
        assertTrue(dismiss.contains("alternateSourceReaderCandidateGateway.cancelLiveSearch()"))
        assertTrue(routeChange.contains("alternateSourceReaderCandidateGateway.cancelLiveSearch()"))
    }

    @Test
    fun `chapter uncertainty is shown before selection confirmation and that confirmation accepts it`() {
        val dialog = source("app/src/main/java/eu/kanade/presentation/reader/AlternateSourceReaderDialog.kt")
        val activity = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt")
        val viewModel = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val chooser = dialog.substringAfter("is AlternateSourceReaderPresentation.ChoosingChapter")
            .substringBefore("is AlternateSourceReaderPresentation.ConfirmPair")
        val confirm = viewModel.substringAfter("fun confirmAlternateSourceChapterSelection()")
            .substringBefore("fun confirmAlternateSourcePairSelection()")

        assertTrue(dialog.contains("selectedToken = presentation.selectedToken"))
        assertTrue(chooser.contains("subtitle = stringResource(KMR.strings.alternate_source_reader_confirm_provisional_message)"))
        assertTrue(chooser.contains("onConfirm = onConfirmChapter"))
        assertTrue(activity.contains("onConfirmChapter = viewModel::confirmAlternateSourceChapterSelection"))
        assertTrue(confirm.contains("selection.copy(confirmProvisional = true)"))
        assertTrue(viewModel.contains("confirmProvisional = selection.confirmProvisional"))
    }

    @Test
    fun `chapter consent remains retained through pair confirmation and retry`() {
        val viewModel = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val confirmChapter = viewModel.substringAfter("fun confirmAlternateSourceChapterSelection()")
            .substringBefore("fun confirmAlternateSourcePairSelection()")
        val confirmPair = viewModel.substringAfter("fun confirmAlternateSourcePairSelection()")
            .substringBefore("fun confirmProvisionalAlternateSourceSelection()")
        val retry = viewModel.substringAfter("fun retryAlternateSourcePresentation()")
            .substringBefore("fun acceptAlternateSourceSearchResult")

        assertTrue(confirmChapter.contains("retainedAlternateSourceSelection = confirmedSelection"))
        assertTrue(confirmChapter.contains("runAlternateSourceSelection(confirmedSelection)"))
        assertTrue(confirmPair.contains("val selection = retainedAlternateSourceSelection ?: return"))
        assertTrue(confirmPair.contains("selectAlternateSourceChapter(selection)"))
        assertTrue(retry.contains("runAlternateSourceSelection(it, explicitUserRetry = true)"))
    }

    @Test
    fun `invalid global search returns become recoverable instead of silent no-op`() {
        val viewModel = source("app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt")
        val searchReturn = viewModel.substringAfter("fun acceptAlternateSourceSearchResult")
            .substringBefore("fun toggleAlternateSourceCandidate")

        assertTrue(searchReturn.contains("val manga = getManga.await(mangaId)"))
        assertTrue(searchReturn.contains("AlternateSourceReaderPresentation.RecoverableFailure"))
        assertTrue(searchReturn.contains("canRetry = true"))
    }

    private fun source(relativePath: String): String {
        val direct = Path.of(relativePath)
        val fromParent = Path.of("..").resolve(relativePath)
        val path = listOf(direct, fromParent)
            .map(Path::toAbsolutePath)
            .firstOrNull(Files::isRegularFile)
            ?: error("Unable to resolve source file: $relativePath")
        return String(Files.readAllBytes(path), Charsets.UTF_8)
    }
}
