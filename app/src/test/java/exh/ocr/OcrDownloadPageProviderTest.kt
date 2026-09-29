package exh.ocr

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mihon.core.archive.ArchiveEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class OcrDownloadPageProviderTest {

    @Test
    fun `owned archive stream closes reader when entry close fails`() {
        val reader = mockk<Closeable>(relaxed = true)
        val entryStream = object : InputStream() {
            override fun read(): Int = -1

            override fun close() {
                throw IOException("entry-close-failed")
            }
        }

        val stream = ownedArchiveEntryStream(reader, entryStream)

        assertThrows(IOException::class.java) { stream.close() }
        verify(exactly = 1) { reader.close() }
    }

    @Test
    fun `capped archive selection keeps sorted image names without retaining every entry`() {
        val entries = sequenceOf(
            ArchiveEntry("page-04.jpg", true, false),
            ArchiveEntry("notes.txt", true, false),
            ArchiveEntry("page-02.jpg", true, false),
            ArchiveEntry("page-01.jpg", true, false),
            ArchiveEntry("page-03.jpg", true, false),
        )

        assertEquals(
            listOf("page-01.jpg", "page-02.jpg"),
            selectArchiveImageNames(entries, isImage = { it.endsWith(".jpg") }, maxPages = 2),
        )
    }

    @Test
    fun `archive selector accepts the remaining global page capacity`() {
        val remainingPages = 5 - 3
        val entries = sequenceOf(
            ArchiveEntry("page-04.jpg", true, false),
            ArchiveEntry("page-02.jpg", true, false),
            ArchiveEntry("page-01.jpg", true, false),
        )

        assertEquals(
            listOf("page-01.jpg", "page-02.jpg"),
            selectArchiveImageNames(entries, isImage = { it.endsWith(".jpg") }, maxPages = remainingPages),
        )
    }

    @Test
    fun `archive selector follows canonical reader natural ordering`() {
        val entries = sequenceOf(
            ArchiveEntry("page-10.jpg", true, false),
            ArchiveEntry("page-2.jpg", true, false),
            ArchiveEntry("page-1.jpg", true, false),
        )

        assertEquals(
            listOf("page-1.jpg", "page-2.jpg", "page-10.jpg"),
            selectArchiveImageNames(
                entries,
                isImage = { it.endsWith(".jpg") },
                maxPages = 0,
            ),
        )
    }

    @Test
    fun `enumeration applies one page cap across downloaded chapters`() = runTest {
        mockkStatic(Uri::class)
        val pageUri = mockk<Uri>(relaxed = true)
        every { Uri.parse(any()) } returns pageUri
        val context = mockk<Context>(relaxed = true)
        val downloadManager = mockk<DownloadManager>(relaxed = true)
        val downloadProvider = mockk<DownloadProvider>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val getLibraryManga = mockk<GetLibraryManga>()
        val getChapters = mockk<GetChaptersByMangaId>()
        val source = mockk<Source>(relaxed = true)
        val chapterDir = mockk<com.hippo.unifile.UniFile>(relaxed = true)
        val manga = Manga.create().copy(id = 1, source = 10, ogTitle = "Manga")
        val first = Chapter.create().copy(id = 11, mangaId = manga.id, name = "Chapter 1", url = "/1")
        val second = Chapter.create().copy(id = 12, mangaId = manga.id, name = "Chapter 2", url = "/2")

        every { sourceManager.get(manga.source) } returns null
        every { sourceManager.getOrStub(manga.source) } returns source
        every { source.id } returns 10
        coEvery { getLibraryManga.await() } returns listOf(
            LibraryManga(manga, emptyList(), 2, 0, 0, 0, 0, 0, 0, 0),
        )
        coEvery { getChapters.await(manga.id) } returns listOf(first, second)
        every { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) } returns true
        every { downloadProvider.findChapterDir(any(), any(), any(), any(), any()) } returns chapterDir
        every { chapterDir.isFile } returns false
        every { downloadManager.buildPageList(source, manga, first) } returns listOf(
            Page(0, uri = Uri.parse("content://chapter-1/page-1")),
            Page(1, uri = Uri.parse("content://chapter-1/page-2")),
        )
        every { downloadManager.buildPageList(source, manga, second) } returns listOf(
            Page(0, uri = Uri.parse("content://chapter-2/page-1")),
            Page(1, uri = Uri.parse("content://chapter-2/page-2")),
        )

        val pages = OcrDownloadPageProvider(
            context = context,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
            getLibraryManga = getLibraryManga,
            getChaptersByMangaId = getChapters,
        ).enumeratePages(OcrScope.AllDownloaded, maxPages = 3)

        assertEquals(3, pages.size)
        assertEquals(listOf(first.id, first.id, second.id), pages.map { it.chapter.id })
        assertEquals(listOf(0, 1, 0), pages.map { it.pageIndex })
        unmockkStatic(Uri::class)
    }

    @Test
    fun `enumeration includes local chapters resolved from local source paths`() = runTest {
        mockkStatic(Uri::class)
        val pageUri = mockk<Uri>(relaxed = true)
        every { Uri.parse(any()) } returns pageUri
        val context = mockk<Context>(relaxed = true)
        val downloadManager = mockk<DownloadManager>(relaxed = true)
        val downloadProvider = mockk<DownloadProvider>(relaxed = true)
        val sourceManager = mockk<SourceManager>(relaxed = true)
        val getLibraryManga = mockk<GetLibraryManga>()
        val getChapters = mockk<GetChaptersByMangaId>()
        val source = mockk<Source>(relaxed = true)
        val chapterDir = mockk<com.hippo.unifile.UniFile>(relaxed = true)
        val manga = Manga.create().copy(id = 2, source = 0, ogTitle = "Local Manga")
        val chapter = Chapter.create().copy(id = 21, mangaId = manga.id, name = "Chapter 1", url = "fixture/chapter-1")

        every { sourceManager.get(manga.source) } returns source
        every { source.id } returns 0
        coEvery { getLibraryManga.await() } returns listOf(
            LibraryManga(manga, emptyList(), 1, 0, 0, 0, 0, 0, 0, 0),
        )
        coEvery { getChapters.await(manga.id) } returns listOf(chapter)
        every { downloadProvider.findChapterDirs(listOf(chapter), manga, source) } returns
            (chapterDir to listOf(chapter to chapterDir))
        every { chapterDir.isFile } returns false
        every { downloadManager.buildPageList(source, manga, chapter) } returns listOf(
            Page(0, uri = Uri.parse("content://local/chapter-1/page-1")),
        )

        val pages = OcrDownloadPageProvider(
            context = context,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
            sourceManager = sourceManager,
            getLibraryManga = getLibraryManga,
            getChaptersByMangaId = getChapters,
        ).enumeratePages(OcrScope.AllDownloaded)

        assertEquals(1, pages.size)
        assertEquals(chapter.id, pages.single().chapter.id)
        verify(exactly = 0) { downloadManager.isChapterDownloaded(any(), any(), any(), any(), any()) }
        unmockkStatic(Uri::class)
    }
}
