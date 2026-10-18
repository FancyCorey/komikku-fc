package eu.kanade.presentation.reader

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.24: the alternate-source offer at the end of a chapter is explained (settings section, one-line hint, one-time
 * explanation on first use) and configurable (missing chapters, end of source).
 */
class AlternateSourceSettingsSourceTest {
    private fun read(path: String) = File(path).readText()

    @Test
    fun `settings explain the offer and control where it appears`() {
        val settings = read("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt")
        assertTrue(settings.contains("getAlternateSourceGroup(readerPreferences = readerPref)"))
        assertTrue(settings.contains("KMR.strings.alternate_source_settings_info"))
        assertTrue(settings.contains("readerPreferences.alternateSourceOfferAtGaps()"))
        assertTrue(settings.contains("readerPreferences.alternateSourceOfferAtEnd()"))
    }

    @Test
    fun `the transition honours both switches and explains itself before the first switch`() {
        val transition = read("src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt")
        assertTrue(transition.contains("onAlternateSourceGap = onChapterGapAction.takeIf { offerAtGaps }"))
        assertTrue(transition.contains("onAlternateSourceGap = onChapterGapAction.takeIf { offerAtEnd }"))
        assertTrue(transition.contains("KMR.strings.alternate_source_gap_hint"))
        assertTrue(transition.contains("if (introSeenPreference.get()) action() else showIntro = true"))
        val view = read("src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt")
        assertTrue(view.contains("offerAtGaps = readerPreferences.alternateSourceOfferAtGaps().get()"))
    }
}
