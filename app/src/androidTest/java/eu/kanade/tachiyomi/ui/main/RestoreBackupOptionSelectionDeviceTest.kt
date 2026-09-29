package eu.kanade.tachiyomi.ui.main

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.testutil.DisposableTestEnvironmentGuard
import eu.kanade.tachiyomi.util.storage.getUriCompat
import exh.ocr.OcrIndexRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.taste.model.CrossSourceGroupPrimary
import tachiyomi.domain.taste.model.CrossSourceIdentityDecision
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue
import tachiyomi.domain.taste.model.CrossSourceIdentityReasonCode
import tachiyomi.domain.taste.model.CrossSourceIdentityReviewState
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import tachiyomi.domain.taste.model.MangaRating
import tachiyomi.domain.taste.model.MangaTaste
import tachiyomi.domain.taste.model.TagPreference
import tachiyomi.domain.taste.model.TagTaste
import tachiyomi.domain.taste.model.normalizeTag
import tachiyomi.domain.taste.repository.TasteRepository
import tachiyomi.domain.tracker.model.LocalTrackedWork
import tachiyomi.domain.tracker.model.LocalTrackedWorkList
import tachiyomi.domain.tracker.model.LocalTrackedWorkSource
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceConfirmation
import tachiyomi.domain.tracker.model.LocalTrackedWorkSourceProgress
import tachiyomi.domain.tracker.model.LocalTrackedWorkStatus
import tachiyomi.domain.tracker.repository.LocalTrackerRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

@RunWith(AndroidJUnit4::class)
class RestoreBackupOptionSelectionDeviceTest {

    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun appCreatedBackupRestoresPopulatedRelationshipsThroughReturnedProviderUri() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        DisposableTestEnvironmentGuard.assumeDisposableEnvironment(context)
        assertFalse("a previous restore must not overlap this isolated round trip", BackupRestoreJob.isRunning(context))

        val mangaRepository = Injekt.get<MangaRepository>()
        val chapterRepository = Injekt.get<ChapterRepository>()
        val tasteRepository = Injekt.get<TasteRepository>()
        val trackerRepository = Injekt.get<LocalTrackerRepository>()
        val ocrRepository = Injekt.get<OcrIndexRepository>()

        val suffix = SystemClock.elapsedRealtimeNanos()
        val sourceA = 910_555_000_000_000_011L
        val sourceB = 910_555_000_000_000_012L
        val urlA = "/kmk-b6-device/$suffix/a"
        val urlB = "/kmk-b6-device/$suffix/b"
        val changedUrlA = "$urlA-mutated-after-backup"
        val groupId = "kmk-b6-device-$suffix"
        val workId = "kmk-b6-device-$suffix"
        val tagDisplayName = "KMK B6 preference $suffix"
        val tag = tagDisplayName.normalizeTag()
        val now = System.currentTimeMillis()
        val backupFile = File(context.cacheDir, "$workId.tachibk")
        var decision: CrossSourceIdentityDecision? = null

        try {
            val inserted = mangaRepository.insertNetworkManga(
                listOf(
                    Manga.create().copy(
                        source = sourceA,
                        url = urlA,
                        ogTitle = "KMK B6 disposable A $suffix",
                        favorite = true,
                        initialized = true,
                        dateAdded = now,
                    ),
                    Manga.create().copy(
                        source = sourceB,
                        url = urlB,
                        ogTitle = "KMK B6 disposable B $suffix",
                        favorite = true,
                        initialized = true,
                        dateAdded = now,
                    ),
                ),
                updateInfo = false,
            )
            assertEquals("both uniquely identified disposable manga rows must be inserted", 2, inserted.size)
            val mangaA = inserted.single { it.source == sourceA && it.url == urlA }
            val mangaB = inserted.single { it.source == sourceB && it.url == urlB }

            val chapters = chapterRepository.addAll(
                listOf(
                    Chapter.create().copy(
                        mangaId = mangaA.id,
                        url = "$urlA/chapter-1",
                        name = "Chapter 1",
                        read = true,
                        lastPageRead = 5,
                        dateFetch = now,
                        dateUpload = now,
                        chapterNumber = 1.0,
                    ),
                    Chapter.create().copy(
                        mangaId = mangaB.id,
                        url = "$urlB/chapter-2",
                        name = "Chapter 2",
                        read = true,
                        lastPageRead = 12,
                        dateFetch = now,
                        dateUpload = now,
                        chapterNumber = 2.0,
                    ),
                ),
            )
            assertEquals("both fixture chapters must be inserted", 2, chapters.size)
            val chapterA = chapters.single { it.mangaId == mangaA.id }
            val chapterB = chapters.single { it.mangaId == mangaB.id }

            tasteRepository.upsertMangaTaste(
                MangaTaste(mangaA.id, sourceA, urlA, mangaA.ogTitle, MangaRating.LOVE.value, now, now),
            )
            tasteRepository.upsertMangaTaste(
                MangaTaste(mangaB.id, sourceB, urlB, mangaB.ogTitle, MangaRating.LIKE.value, now, now),
            )
            tasteRepository.upsertTagTaste(
                TagTaste(tag, tagDisplayName, TagPreference.PREFER.value, now, now),
            )
            tasteRepository.upsertCrossSourceMangaLinks(
                listOf(
                    CrossSourceMangaLink(sourceA, urlA, groupId, mangaA.ogTitle, now, now),
                    CrossSourceMangaLink(sourceB, urlB, groupId, mangaB.ogTitle, now, now),
                ),
            )
            tasteRepository.upsertCrossSourceGroupPrimary(CrossSourceGroupPrimary(groupId, sourceA, urlA, now))
            val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(
                CrossSourceRecordKey(sourceA, urlA),
                CrossSourceRecordKey(sourceB, urlB),
            )
            decision = CrossSourceIdentityDecision(
                pair = pair,
                decision = CrossSourceIdentityDecisionValue.USER_CONFIRMED,
                decisionVersion = CrossSourceIdentityDecisionPolicy.CURRENT_DECISION_VERSION,
                evidenceVersion = CrossSourceIdentityDecisionPolicy.CURRENT_EVIDENCE_VERSION,
                reasonCodes = setOf(CrossSourceIdentityReasonCode.USER_CONFIRMATION),
                reviewState = CrossSourceIdentityReviewState.CURRENT,
                createdAt = now,
                updatedAt = now,
            )
            tasteRepository.upsertCrossSourceIdentityDecisions(listOf(decision!!))

            val trackerWork = LocalTrackedWork(
                id = workId,
                title = "KMK B6 disposable tracked work",
                normalizedTitle = "kmk b6 disposable tracked work",
                status = LocalTrackedWorkStatus.READING,
                lastChapterSource = sourceA,
                lastChapterNumber = 1.0,
                lastChapterUrl = "$urlA/chapter-1",
                lastChapterLabel = "Chapter 1",
                lastProgressAt = now,
                score = 87.0,
                startDate = now - 1_000,
                finishDate = null,
                createdAt = now,
                updatedAt = now,
            )
            val trackerSource = LocalTrackedWorkSource(
                workId = workId,
                source = sourceA,
                url = urlA,
                title = trackerWork.title,
                confidence = 100,
                confirmation = LocalTrackedWorkSourceConfirmation.USER_CONFIRMED,
                inheritanceOptedOut = false,
                createdAt = now,
                updatedAt = now,
            )
            val trackerProgress = LocalTrackedWorkSourceProgress(
                workId = workId,
                source = sourceA,
                url = urlA,
                chapterNumber = 1.0,
                chapterUrl = "$urlA/chapter-1",
                chapterLabel = "Chapter 1",
                progressAt = now,
                inheritedFromSource = null,
                inheritedFromUrl = null,
                updatedAt = now,
            )
            trackerRepository.upsertWork(trackerWork)
            trackerRepository.upsertSource(trackerSource)
            trackerRepository.upsertSourceProgress(trackerProgress)
            trackerRepository.replaceListEntries(workId, listOf(LocalTrackedWorkList("Reading", now)))

            val runGeneration = ocrRepository.beginIndexing()
            assertTrue(
                "the valid OCR row must be created through the repository",
                ocrRepository.upsertPage(
                    mangaId = mangaB.id,
                    sourceId = sourceB,
                    mangaUrl = urlB,
                    mangaTitle = mangaB.ogTitle,
                    chapterId = chapterB.id,
                    chapterUrl = chapterB.url,
                    chapterName = chapterB.name,
                    pageIndex = 0,
                    pageUri = null,
                    pageIdentity = "b6-valid-$suffix",
                    pageModifiedAt = null,
                    pageSize = null,
                    engineKey = "b6-device",
                    engineVersion = "b6-$suffix",
                    languageHint = null,
                    rawText = "valid b6 fixture text",
                    normalizedText = "valid b6 fixture text",
                    indexedAt = now,
                    errorMessage = null,
                    recognizedTextLength = 21,
                    recognizedWordCount = 4,
                    ocrStatus = "success",
                    runGeneration = runGeneration,
                ),
            )
            assertTrue(
                "the OCR cleanup row must be created through the repository",
                ocrRepository.upsertPage(
                    mangaId = mangaA.id,
                    sourceId = sourceA,
                    mangaUrl = urlA,
                    mangaTitle = mangaA.ogTitle,
                    chapterId = chapterA.id,
                    chapterUrl = chapterA.url,
                    chapterName = chapterA.name,
                    pageIndex = 0,
                    pageUri = null,
                    pageIdentity = "b6-stale-$suffix",
                    pageModifiedAt = null,
                    pageSize = null,
                    engineKey = "b6-device",
                    engineVersion = "b6-$suffix",
                    languageHint = null,
                    rawText = "stale b6 fixture text",
                    normalizedText = "stale b6 fixture text",
                    indexedAt = now,
                    errorMessage = null,
                    recognizedTextLength = 21,
                    recognizedWordCount = 4,
                    ocrStatus = "success",
                    runGeneration = runGeneration,
                ),
            )

            assertNotNull(tasteRepository.getMangaTaste(sourceA, urlA))
            assertNotNull(tasteRepository.getMangaTaste(sourceB, urlB))
            assertEquals(2, tasteRepository.getCrossSourceMangaLinksByGroupId(groupId).size)
            assertNotNull(tasteRepository.getCrossSourceGroupPrimary(groupId))
            assertNotNull(tasteRepository.getCrossSourceIdentityDecision(pair))
            assertEquals(trackerProgress, trackerRepository.getSourceProgress(workId, sourceA, urlA))
            assertEquals(12L, chapterRepository.getChapterById(chapterB.id)?.lastPageRead)

            check(backupFile.createNewFile()) { "could not pre-create the app-created backup file" }
            val returnedUri = Uri.parse(
                BackupCreator(context = context, isAutoBackup = false).backup(
                    backupFile.getUriCompat(context),
                    BackupOptions(
                        libraryEntries = true,
                        categories = false,
                        chapters = true,
                        tracking = false,
                        history = false,
                        readEntries = false,
                        appSettings = false,
                        extensionStores = false,
                        sourceSettings = false,
                        privateSettings = false,
                        customInfo = false,
                        savedSearchesFeeds = false,
                        tasteProfile = true,
                        localTracker = true,
                    ),
                ),
            )
            assertEquals("app-created backup must return the exact provider URI", "content", returnedUri.scheme)
            assertEquals("backup must use the app FileProvider", "${context.packageName}.provider", returnedUri.authority)
            assertTrue("the app-created archive must be non-empty", backupFile.length() > 0)

            val decodedBackup = BackupDecoder(context).decode(returnedUri)
            assertTrue("the app-created archive must contain both fixture manga", decodedBackup.backupManga.any { it.source == sourceA && it.url == urlA } && decodedBackup.backupManga.any { it.source == sourceB && it.url == urlB })
            assertTrue("the app-created archive must contain both fixture ratings", decodedBackup.backupMangaTastes.any { it.source == sourceA && it.url == urlA && it.rating == MangaRating.LOVE.value } && decodedBackup.backupMangaTastes.any { it.source == sourceB && it.url == urlB && it.rating == MangaRating.LIKE.value })
            assertTrue("the app-created archive must contain the fixture tag preference", decodedBackup.backupTagTastes.any { it.displayName == tagDisplayName && it.preference == TagPreference.PREFER.value })
            assertEquals("the app-created archive must contain both cross-source group members", 2, decodedBackup.backupCrossSourceMangaLinks.count { it.groupId == groupId })
            assertTrue("the app-created archive must contain the selected group primary", decodedBackup.backupCrossSourceGroupPrimaries.any { it.groupId == groupId && it.source == sourceA && it.url == urlA })
            assertEquals("the app-created archive must contain the fixture identity decision", 1, decodedBackup.backupCrossSourceIdentityDecisions.count { it.leftSource == pair.left.source && it.leftUrl == pair.left.url && it.rightSource == pair.right.source && it.rightUrl == pair.right.url })
            assertTrue("the app-created archive must contain local tracker progress", decodedBackup.backupLocalTrackedWorks.any { it.id == workId && it.sourceProgress.any { progress -> progress.source == sourceA && progress.url == urlA && progress.chapterNumber == 1.0 } })
            assertTrue("the app-created archive must contain chapter progress", decodedBackup.backupManga.first { it.source == sourceB && it.url == urlB }.chapters.any { it.url == chapterB.url && it.lastPageRead == 12L })

            tasteRepository.deleteMangaTaste(sourceA, urlA)
            tasteRepository.deleteMangaTaste(sourceB, urlB)
            tasteRepository.deleteTagTaste(tag)
            tasteRepository.deleteCrossSourceMangaLink(sourceA, urlA)
            tasteRepository.deleteCrossSourceMangaLink(sourceB, urlB)
            tasteRepository.deleteCrossSourceGroupPrimary(groupId)
            assertTrue("the uniquely seeded identity decision must be removed before restore", tasteRepository.replaceCrossSourceIdentityDecision(decision!!, null))
            trackerRepository.deleteWork(workId)
            chapterRepository.update(ChapterUpdate(id = chapterB.id, read = false, lastPageRead = 0))
            assertTrue("the manga URL mutation must make only the seeded OCR page orphaned", mangaRepository.update(MangaUpdate(id = mangaA.id, url = changedUrlA)))
            assertNull(tasteRepository.getMangaTaste(sourceA, urlA))
            assertNull(trackerRepository.getWork(workId))
            assertEquals(0, tasteRepository.getCrossSourceMangaLinksByGroupId(groupId).size)
            assertEquals(0L, chapterRepository.getChapterById(chapterB.id)?.lastPageRead)
            assertNotNull(
                "the stale OCR row remains until the app restore performs its orphan cleanup",
                ocrRepository.getExistingPageState(chapterA.id, 0, "b6-stale-$suffix", "b6-device", "b6-$suffix"),
            )

            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = returnedUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                val option = device.wait(Until.findObject(By.text("Taste profile")), 30_000)
                    ?: error("Restore option did not appear for the app-returned provider URI")
                assertTrue("Taste profile option must be enabled", option.isEnabled)

                val restoreAction = device.wait(Until.findObject(By.text("Restore")), 10_000)
                    ?: error("Restore action must remain visible with default options selected")
                assertTrue("Restore action must be enabled", restoreAction.isEnabled)
                restoreAction.click()
            }

            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val restoredTag = tasteRepository.getTagTaste(tag)?.preference == TagPreference.PREFER.value
                val restoredTracker = trackerRepository.getWork(workId) != null
                if (restoredTag && restoredTracker && !BackupRestoreJob.isRunning(context)) break
                delay(100)
            }

            assertEquals(MangaRating.LOVE.value, tasteRepository.getMangaTaste(sourceA, urlA)?.rating)
            assertEquals(MangaRating.LIKE.value, tasteRepository.getMangaTaste(sourceB, urlB)?.rating)
            assertEquals(TagPreference.PREFER.value, tasteRepository.getTagTaste(tag)?.preference)
            assertEquals(2, tasteRepository.getCrossSourceMangaLinksByGroupId(groupId).size)
            assertEquals(sourceA, tasteRepository.getCrossSourceGroupPrimary(groupId)?.source)
            assertEquals(decision, tasteRepository.getCrossSourceIdentityDecision(decision!!.pair))
            assertEquals(trackerWork, trackerRepository.getWork(workId))
            assertEquals(trackerSource, trackerRepository.getSources(workId).single())
            assertEquals(trackerProgress, trackerRepository.getSourceProgress(workId, sourceA, urlA))
            assertEquals(listOf(LocalTrackedWorkList("Reading", now)), trackerRepository.getListEntries(workId))
            assertEquals(12L, chapterRepository.getChapterByUrlAndMangaId(chapterB.url, mangaB.id)?.lastPageRead)
            assertNull(
                "restore must delete only the OCR page whose manga identity no longer matches",
                ocrRepository.getExistingPageState(chapterA.id, 0, "b6-stale-$suffix", "b6-device", "b6-$suffix"),
            )
            assertNotNull(
                "restore must retain the valid OCR page belonging to the unchanged manga/chapter",
                ocrRepository.getExistingPageState(chapterB.id, 0, "b6-valid-$suffix", "b6-device", "b6-$suffix"),
            )
        } finally {
            if (BackupRestoreJob.isRunning(context)) {
                val deadline = SystemClock.elapsedRealtime() + 30_000
                while (BackupRestoreJob.isRunning(context) && SystemClock.elapsedRealtime() < deadline) delay(100)
            }
            trackerRepository.getWork(workId)?.let { trackerRepository.deleteWork(workId) }
            tasteRepository.deleteTagTaste(tag)
            tasteRepository.deleteCrossSourceGroupCompletely(groupId)
            tasteRepository.deleteMangaTaste(sourceA, urlA)
            tasteRepository.deleteMangaTaste(sourceA, changedUrlA)
            tasteRepository.deleteMangaTaste(sourceB, urlB)
            decision?.let { seeded ->
                tasteRepository.getCrossSourceIdentityDecision(seeded.pair)?.let { current ->
                    tasteRepository.replaceCrossSourceIdentityDecision(current, null)
                }
            }
            for ((source, urls) in listOf(sourceA to setOf(urlA, changedUrlA), sourceB to setOf(urlB))) {
                mangaRepository.getMangaBySourceId(source)
                    .filter { it.url in urls }
                    .forEach { manga ->
                        ocrRepository.deleteByManga(manga.id)
                        chapterRepository.removeChaptersWithIds(chapterRepository.getChapterByMangaId(manga.id).map { it.id })
                        mangaRepository.deleteManga(manga.id)
                    }
            }
            if (backupFile.exists()) check(backupFile.delete()) { "could not remove the scoped temporary backup" }
        }
    }
}
