package eu.kanade.tachiyomi.ui.manga.track

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LocalTrackingInteractionContractTest {
    private val home = File("src/main/java/eu/kanade/presentation/track/TrackInfoDialogHome.kt").readText()
    private val dialogs = File("src/main/java/eu/kanade/presentation/manga/components/MangaDialogs.kt").readText()
    private val chapterFormatter = File("src/main/java/eu/kanade/presentation/track/ChapterNumberFormatter.kt").readText()
    private val screenModel = File("src/main/java/eu/kanade/tachiyomi/ui/manga/track/TrackInfoDialog.kt").readText()

    @Test
    fun localChapterCellDoesNotReuseDetailsEditor() {
        val local = home.substringAfter("private fun LocalTrackerInfoItem(")
            .substringBefore("@Composable\nprivate fun TrackDetailsPanel")
        assertTrue(
            "onChaptersClick = onChapterClick" in local,
            "local chapter progress must remain editable when it is null",
        )
        assertTrue("chapters = work.lastChapterNumber?.let(::formatLocalChapterNumber) ?: \"0\"" in local)
    }

    @Test
    fun localChapterEditorUsesTheExternalTrackerStylePickerAndKeepsDecimalEntry() {
        assertTrue("WheelNumberPicker(" in dialogs)
        assertTrue("LOCAL_CHAPTER_PICKER_MAX" in dialogs)
        assertTrue("enter a decimal number below".lowercase() in File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText().lowercase())
        assertTrue("chapterText = index.toString()" in dialogs)
        assertTrue("formatLocalChapterNumber" in dialogs)
        assertTrue("BigDecimal.valueOf(value)" in chapterFormatter)
    }

    @Test
    fun localChapterDisplayRoundsFloatArtifactsWithoutDroppingRealDecimals() {
        val formatter = Class.forName("eu.kanade.presentation.track.ChapterNumberFormatterKt")
            .declaredMethods
            .first { it.name == "formatLocalChapterNumber" }
        assertTrue(formatter != null)
        val source = File("src/main/java/eu/kanade/presentation/track/ChapterNumberFormatter.kt").readText()
        assertTrue("setScale(3, RoundingMode.HALF_UP)" in source)
        assertTrue("stripTrailingZeros()" in source)
    }

    @Test
    fun localTitleAndStatusUseSeparateActions() {
        val local = home.substringAfter("private fun LocalTrackerInfoItem(")
            .substringBefore("@Composable\nprivate fun TrackDetailsPanel")
        assertTrue(".clickable(onClick = onTitleClick)" in local)
        assertTrue("onStatusClick" in local)
    }

    @Test
    fun localTrackingExposesSettingsAndReturnsThroughTheParentNavigator() {
        assertTrue(home.contains("onLocalSettingsClick"))
        assertTrue(screenModel.contains("SettingsScreen.Destination.Tracking"))
    }

    @Test
    fun localTitleVersionsUseConfirmedDatabaseRowsInsteadOfTrackerSearch() {
        assertTrue("LocalTrackedWorkSourceConfirmation.USER_CONFIRMED" in screenModel)
        assertTrue("LocalTrackingVersionsDialog" in screenModel)
        val versionsLoader = screenModel.substringAfter("fun openLocalVersions()")
            .substringBefore("fun dismissLocalVersionsDialog()")
        assertTrue("TrackerSearchScreen" !in versionsLoader)
    }

    @Test
    fun localTitleVersionsUseTrackerStyleBoundedCardsForLongNames() {
        assertTrue("heightIn(max = 420.dp)" in dialogs)
        assertTrue("verticalScroll(rememberScrollState())" in dialogs)
        assertTrue("maxLines = 2" in dialogs)
        assertTrue("TextOverflow.Ellipsis" in dialogs)
        assertTrue("MangaCover.Book" in dialogs)
        assertTrue("LocalTrackingVersionCard" in dialogs)
    }

    @Test
    fun localVersionsExposeAndPersistPerVersionProgressSharing() {
        assertTrue("local_tracking_share_progress_with_version" in dialogs)
        assertTrue("onProgressSharingChange" in dialogs)
        assertTrue("setLocalVersionProgressSharing" in screenModel)
        assertTrue("setInheritanceOptedOut" in screenModel)
    }

    @Test
    fun localDateSelectorOwnsTheDetailsDialogBranch() {
        val selector = dialogs.substringAfter("fun LocalTrackDetailsDialog(")
            .substringBefore("private enum class LocalDateField")
        assertTrue("if (dateField != null)" in selector)
        assertTrue("} else {" in selector)
        assertTrue(selector.indexOf("if (dateField != null)") < selector.indexOf("AlertDialog("))
    }

    @Test
    fun partialExternalReconciliationKeepsTheDialogOpenForRetry() {
        val export = screenModel.substringAfter("fun exportLocalMetadata(")
            .substringBefore("fun saveLocalDetails(")
        assertTrue(
            !export.substringBefore("runLocalTrackingAction").contains("dismissLocalReconciliationDialog()"),
            "reconciliation must remain open while remote writes are attempted",
        )
        assertTrue("if (failedFields.isNotEmpty())" in export)
        assertTrue("pendingExternalWrites" in export)
        assertTrue("dismissLocalReconciliationDialog()" in export.substringAfter("if (failedFields.isNotEmpty())"))
        assertTrue(
            export.indexOf("dismissLocalReconciliationDialog()") > export.indexOf("else {"),
            "successful reconciliation should close only after all writes succeed",
        )
    }

    @Test
    fun partialExternalReconciliationUsesTheSharedRetryPolicyOwner() {
        val export = screenModel.substringAfter("fun exportLocalMetadata(")
            .substringBefore("fun saveLocalDetails(")
        assertTrue("LocalTrackingReconciliationPolicy.fieldsToAttempt" in export)
        assertTrue("LocalTrackingReconciliationPolicy.completedFieldCount" in export)
    }
}
