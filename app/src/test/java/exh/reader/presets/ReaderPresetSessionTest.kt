package exh.reader.presets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * v0.8.25: a series can carry its own reader look. The preset is put in place while its reader is open and the usual settings
 * come back afterwards, even if the app was killed in between.
 */
class ReaderPresetSessionTest {

    private val store = mutableMapOf("crop" to "false", "theme" to "1", "layout" to "0")
    private var pending = ""

    private fun setting(id: String) = ReaderPresetSetting(id, { store.getValue(id) }, { store[id] = it })

    private fun session() = ReaderPresetSession(
        listOf(setting("crop"), setting("theme"), setting("layout")),
        { pending },
        { pending = it },
    )

    @Test
    fun `a preset replaces only the settings it holds and ending puts the usual ones back`() {
        val session = session()
        session.begin(mapOf("crop" to "true", "theme" to "2"))
        assertEquals("true", store["crop"])
        assertEquals("2", store["theme"])
        assertEquals("0", store["layout"], "settings the preset does not hold are not touched")
        session.end()
        assertEquals("false", store["crop"])
        assertEquals("1", store["theme"])
        assertEquals("", pending)
    }

    @Test
    fun `a reader that was killed is repaired by the next session before it applies its own preset`() {
        session().begin(mapOf("crop" to "true"))
        assertEquals("true", store["crop"])
        // the app dies here; the next reader opens a different series
        session().begin(mapOf("theme" to "3"))
        assertEquals("false", store["crop"], "the first series' look did not leak into the second")
        assertEquals("3", store["theme"])
        session().end()
        assertEquals("1", store["theme"])
    }

    @Test
    fun `restoring with nothing pending changes nothing, and unknown ids are ignored`() {
        session().restore()
        session().begin(mapOf("removed-setting" to "x"))
        assertEquals("", pending)
        assertEquals("false", store["crop"])
    }

    @Test
    fun `the reader builds its viewer after the preset is applied and cleans up when it closes`() {
        val vm = File("src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt").readText()
        val commit = vm.substringAfter("commit = { candidate, generation ->").substringBefore("private suspend fun switchReaderRoute")
        assertTrue(commit.indexOf("applyValues(presetValues)") in 1 until commit.indexOf("commitReaderRoute(candidate, generation)"))
        assertTrue(!vm.contains("beginFor(manga)"), "candidate construction must not change live preferences")
        assertTrue(vm.contains("readerPresetsHolder.value?.endSession()"))
        assertTrue(File("src/main/java/eu/kanade/tachiyomi/App.kt").readText().contains("ReaderPresets().restorePending()"))
        val dialog = File("src/main/java/eu/kanade/presentation/reader/settings/ReaderSettingsDialog.kt").readText()
        assertTrue(!dialog.contains("ReaderPresetPage") && !dialog.contains("reader_presets_tab"), "presets are not a tab of their own")
        val page = File("src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt").readText()
        assertTrue(page.contains("ReaderPresetSection(screenModel)"), "presets sit in the first tab, with the format settings")
        assertTrue(
            page.indexOf("pref_category_for_this_series") < page.indexOf("ReaderPresetSection(screenModel)") &&
                page.indexOf("ReaderPresetSection(screenModel)") < page.indexOf("SettingsChipRow(MR.strings.pref_category_reading_mode)"),
            "the preset row is first under For this series, above reading mode",
        )
        assertTrue(File("src/main/java/eu/kanade/presentation/reader/settings/ReaderPresetSection.kt").readText().contains("SettingsChipRow("), "same chip format as reading mode")
    }

    @Test
    fun `failed preset write restores active values and its recovery snapshot`() {
        val session = ReaderPresetSession(
            listOf(
                setting("crop"),
                ReaderPresetSetting("theme", { store.getValue("theme") }, {
                    if (it == "broken") error("controlled write failure")
                    store["theme"] = it
                }),
            ),
            { pending },
            { pending = it },
        )
        session.begin(mapOf("crop" to "true", "theme" to "2"))
        val originalPending = pending
        assertThrows(IllegalStateException::class.java) {
            session.begin(mapOf("crop" to "false", "theme" to "broken"))
        }
        assertEquals("true", store["crop"])
        assertEquals("2", store["theme"])
        assertEquals(originalPending, pending)
        session.end()
        assertEquals("false", store["crop"])
        assertEquals("1", store["theme"])
    }

    @Test
    fun `committing usual settings restores the prior preset baseline`() {
        val session = session()
        session.begin(mapOf("theme" to "2"))
        session.begin(emptyMap())
        assertEquals("1", store["theme"])
        assertEquals("", pending)
    }
}
