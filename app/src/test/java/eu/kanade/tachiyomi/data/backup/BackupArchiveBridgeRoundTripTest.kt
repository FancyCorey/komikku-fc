package eu.kanade.tachiyomi.data.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.TasteBackupCreator
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreOutcome
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.TasteRestorer
import eu.kanade.tachiyomi.data.track.TrackerManager
import exh.ocr.OcrIndexRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.taste.AlternateSourceBridgeRepositoryImpl
import tachiyomi.data.taste.TasteRepositoryImpl
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.taste.interactor.GetCrossSourceGroupPrimary
import tachiyomi.domain.taste.interactor.GetCrossSourceMangaLinks
import tachiyomi.domain.taste.interactor.GetMangaTaste
import tachiyomi.domain.taste.model.AlternateSourceBridgePolicy
import tachiyomi.domain.taste.model.AlternateSourceBridgeState
import tachiyomi.domain.taste.model.AlternateSourceBridgeTargetResolution
import tachiyomi.domain.taste.model.bridge
import tachiyomi.domain.taste.model.bridgeMapping
import tachiyomi.domain.taste.repository.AlternateSourceBridgeRepository
import tachiyomi.domain.taste.repository.TasteRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class BackupArchiveBridgeRoundTripTest {

    private data class Repositories(
        val driver: JdbcSqliteDriver,
        val taste: TasteRepositoryImpl,
        val manga: MangaRepositoryImpl,
        val bridge: AlternateSourceBridgeRepositoryImpl,
    )

    private fun freshRepositories(): Repositories {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        val database = Database(
            driver = driver,
            historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
            mangasAdapter = Mangas.Adapter(
                genreAdapter = StringListColumnAdapter,
                update_strategyAdapter = UpdateStrategyColumnAdapter,
                memoAdapter = MemoColumnAdapter,
            ),
            chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
        )
        val handler = AndroidDatabaseHandler(database, driver)
        return Repositories(
            driver = driver,
            taste = TasteRepositoryImpl(handler),
            manga = MangaRepositoryImpl(handler),
            bridge = AlternateSourceBridgeRepositoryImpl(handler),
        )
    }

    private fun tasteBackupCreator(
        tasteRepository: TasteRepository,
        bridgeRepository: AlternateSourceBridgeRepository,
        sourcePreferences: SourcePreferences,
    ) = TasteBackupCreator(
        tasteRepository = tasteRepository,
        alternateSourceBridgeRepository = bridgeRepository,
        getMangaTaste = GetMangaTaste(tasteRepository),
        getTagTaste = mockk(relaxed = true),
        getTagAliases = mockk(relaxed = true),
        getDisabledSources = mockk(relaxed = true),
        getCrossSourceMangaLinks = GetCrossSourceMangaLinks(tasteRepository),
        getCrossSourceGroupPrimary = GetCrossSourceGroupPrimary(tasteRepository),
        getMangaSourceQualitySignals = mockk(relaxed = true),
        sourcePreferences = sourcePreferences,
    )

    private fun tasteRestorer(
        tasteRepository: TasteRepository,
        mangaRepository: MangaRepository,
        bridgeRepository: AlternateSourceBridgeRepository,
        sourcePreferences: SourcePreferences,
    ) = TasteRestorer(
        tasteRepository = tasteRepository,
        alternateSourceBridgeRepository = bridgeRepository,
        mangaRepository = mangaRepository,
        getManga = GetManga(mangaRepository),
        getMangaTaste = GetMangaTaste(tasteRepository),
        getTagTaste = mockk(relaxed = true),
        getTagAliases = mockk(relaxed = true),
        getDisabledSources = mockk(relaxed = true),
        setSourceEnabled = mockk(relaxed = true),
        upsertTagAlias = mockk(relaxed = true),
        getCrossSourceMangaLinks = GetCrossSourceMangaLinks(tasteRepository),
        upsertCrossSourceMangaLinks =
        tachiyomi.domain.taste.interactor.UpsertCrossSourceMangaLinks(tasteRepository),
        getCrossSourceGroupPrimary = GetCrossSourceGroupPrimary(tasteRepository),
        getMangaSourceQualitySignals = mockk(relaxed = true),
        upsertMangaSourceQualitySignal = mockk(relaxed = true),
        sourcePreferences = sourcePreferences,
    )

    @Test
    fun `populated bridge archive round trips through creation decode and full restore`() = runTest {
        bindArchiveDecoderAndValidationDependencies()

        val source = freshRepositories()
        val target = freshRepositories()
        val archiveDirectory = Files.createTempDirectory("bridge-archive-round-trip").toFile()
        val archiveFile = File(archiveDirectory, "populated-bridge.tachibk").apply { createNewFile() }
        val context = RuntimeEnvironment.getApplication() as Context
        val sourcePreferences = SourcePreferences(
            AndroidPreferenceStore(
                context,
                context.getSharedPreferences("bridge-archive-source-preferences", Context.MODE_PRIVATE),
            ),
        )
        val targetPreferences = SourcePreferences(
            AndroidPreferenceStore(
                context,
                context.getSharedPreferences("bridge-archive-target-preferences", Context.MODE_PRIVATE),
            ),
        )

        try {
            val route = bridge(
                continuationUrl = "/chapter/next",
                returnAfterUrl = "/chapter/alternate",
                automaticReturn = true,
            )
            val mapping = bridgeMapping()
            source.bridge.upsert(AlternateSourceBridgeState(route, listOf(mapping)))

            val getFavorites = mockk<GetFavorites>()
            coEvery { getFavorites.await() } returns emptyList()
            val getMergedManga = mockk<GetMergedManga>()
            coEvery { getMergedManga.await() } returns emptyList()
            val sourceManager = mockk<SourceManager>(relaxed = true)
            val creator = BackupCreator(
                context = context,
                isAutoBackup = false,
                parser = ProtoBuf,
                getFavorites = getFavorites,
                backupPreferences = mockk(relaxed = true),
                mangaRepository = mockk(relaxed = true),
                categoriesBackupCreator = mockk(relaxed = true),
                mangaBackupCreator = mockk(relaxed = true),
                preferenceBackupCreator = mockk(relaxed = true),
                extensionStoresBackupCreator = mockk(relaxed = true),
                sourcesBackupCreator = SourcesBackupCreator(sourceManager),
                feedBackupCreator = mockk(relaxed = true),
                tasteBackupCreator = tasteBackupCreator(source.taste, source.bridge, sourcePreferences),
                localTrackerBackupCreator = mockk(relaxed = true),
                savedSearchBackupCreator = mockk(relaxed = true),
                getMergedManga = getMergedManga,
            )
            val uri = Uri.fromFile(archiveFile)
            val createdUri = creator.backup(uri, backupOptions())

            assertEquals(uri.toString(), createdUri)
            val prefix = Files.newInputStream(archiveFile.toPath()).use { input ->
                byteArrayOf(input.read().toByte(), input.read().toByte())
            }
            assertEquals("BackupCreator must write gzip bytes", 0x1f, prefix[0].toInt() and 0xff)
            assertEquals("BackupCreator must write gzip bytes", 0x8b, prefix[1].toInt() and 0xff)

            val archived = BackupDecoder(context, ProtoBuf).decode(uri)
            assertEquals(1, archived.backupAlternateSourceBridges.size)
            assertEquals(1, archived.backupAlternateSourceBridgeMappings.size)

            val restored = BackupRestorer(
                context = context,
                notifier = mockk(relaxed = true),
                isSync = false,
                categoriesRestorer = mockk<CategoriesRestorer>(relaxed = true),
                preferenceRestorer = mockk<PreferenceRestorer>(relaxed = true),
                extensionStoreRestorer = mockk<ExtensionStoreRestorer>(relaxed = true),
                mangaRestorer = mockk<MangaRestorer>(relaxed = true),
                savedSearchRestorer = mockk<SavedSearchRestorer>(relaxed = true),
                feedRestorer = mockk<FeedRestorer>(relaxed = true),
                tasteRestorer = tasteRestorer(target.taste, target.manga, target.bridge, targetPreferences),
                localTrackerBackupRestorer = mockk(relaxed = true),
                ocrIndexRepository = mockk<OcrIndexRepository>(relaxed = true),
            ).restore(uri, restoreOptions())

            assertEquals(BackupRestoreOutcome.Success(restoredCount = 1), restored)
            assertTrue("source and target repositories must be independent", source.bridge !== target.bridge)
            val restoredState = requireNotNull(target.bridge.get(route.key))
            assertEquals(route, restoredState.bridge)
            assertEquals(listOf(mapping), restoredState.mappings)
            assertEquals(
                AlternateSourceBridgeTargetResolution.Target(
                    "/chapter/alternate",
                    mapping.state,
                    mapping.relation,
                ),
                AlternateSourceBridgePolicy.resolveAnchoredGapTarget(
                    restoredState.bridge,
                    restoredState.mappings,
                    "/chapter/before",
                    "/chapter/after",
                ),
            )
        } finally {
            source.driver.close()
            target.driver.close()
            archiveDirectory.deleteRecursively()
        }
    }

    private fun backupOptions() = BackupOptions(
        libraryEntries = false,
        categories = false,
        chapters = false,
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
        localTracker = false,
    )

    private fun restoreOptions() = RestoreOptions(
        libraryEntries = false,
        categories = false,
        appSettings = false,
        extensionStores = false,
        sourceSettings = false,
        savedSearchesFeeds = false,
        tasteProfile = true,
        localTracker = false,
    )

    private fun bindArchiveDecoderAndValidationDependencies() {
        Injekt.importModule(
            object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory<ProtoBuf> { ProtoBuf }
                    addSingletonFactory<SourceManager> { mockk(relaxed = true) }
                    addSingletonFactory { mockk<TrackerManager>(relaxed = true) }
                }
            },
        )
    }
}
