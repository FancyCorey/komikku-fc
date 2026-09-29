package exh.ocr

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.ArchiveEntry
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.PriorityQueue

// KMK --> OCR v0.1.0

data class OcrPageRef(
    val manga: Manga,
    val chapter: Chapter,
    val pageIndex: Int,
    val pageIdentity: String,
    val pageModifiedAt: Long?,
    val pageSize: Long?,
    val streamProvider: () -> InputStream?,
)

internal fun ownedArchiveEntryStream(
    reader: Closeable,
    entryStream: InputStream,
): InputStream = object : FilterInputStream(entryStream) {
    override fun close() {
        try {
            super.close()
        } finally {
            reader.close()
        }
    }
}

internal fun selectArchiveImageNames(
    entries: Sequence<ArchiveEntry>,
    isImage: (String) -> Boolean,
    maxPages: Int,
): List<String> {
    fun compareNames(first: String, second: String): Int =
        first.compareToCaseInsensitiveNaturalOrder(second)

    if (maxPages <= 0) {
        return entries
            .filter { it.isFile && isImage(it.name) }
            .map { it.name }
            .sortedWith(::compareNames)
            .toList()
    }

    // Keep only the largest selected name as the eviction candidate while the archive is read.
    val selected = PriorityQueue<String> { first, second -> compareNames(second, first) }
    entries.forEach { entry ->
        if (!entry.isFile || !isImage(entry.name)) return@forEach
        if (selected.size < maxPages) {
            selected.add(entry.name)
        } else if (compareNames(entry.name, checkNotNull(selected.peek())) < 0) {
            selected.poll()
            selected.add(entry.name)
        }
    }
    return selected.toList().sortedWith(::compareNames)
}

sealed interface OcrScope {
    data class SingleManga(val mangaId: Long) : OcrScope
    data object AllDownloaded : OcrScope
}

class OcrDownloadPageProvider(
    private val context: Context,
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
) {

    private fun openArchiveEntryStream(
        chapterDir: com.hippo.unifile.UniFile,
        entryName: String,
    ): InputStream? {
        val reader = chapterDir.archiveReader(context)
        return try {
            val entryStream = reader.getInputStream(entryName)
            if (entryStream == null) {
                reader.close()
                null
            } else {
                ownedArchiveEntryStream(reader, entryStream)
            }
        } catch (error: Throwable) {
            reader.close()
            throw error
        }
    }

    suspend fun enumeratePages(scope: OcrScope, maxPages: Int = 0): List<OcrPageRef> {
        val mangaList: List<Manga> = when (scope) {
            is OcrScope.SingleManga -> {
                val allManga = getLibraryManga.await()
                listOfNotNull(allManga.firstOrNull { it.manga.id == scope.mangaId }?.manga)
            }
            OcrScope.AllDownloaded -> getLibraryManga.await().map { it.manga }
        }

        val result = mutableListOf<OcrPageRef>()
        for (manga in mangaList) {
            if (maxPages > 0 && result.size >= maxPages) return result
            val source = sourceManager.get(manga.source) ?: sourceManager.getOrStub(manga.source)
            val chapters = getChaptersByMangaId.await(manga.id)
            val localChapterDirs = if (source.isLocal()) {
                downloadProvider.findChapterDirs(chapters, manga, source)
                    .second
                    .associate { (chapter, directory) -> chapter.id to directory }
            } else {
                emptyMap()
            }
            for (chapter in chapters) {
                if (maxPages > 0 && result.size >= maxPages) return result
                val chapterDir = if (source.isLocal()) {
                    localChapterDirs[chapter.id]
                } else {
                    if (!downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            chapter.url,
                            manga.ogTitle,
                            manga.source,
                        )
                    ) {
                        continue
                    }
                    downloadProvider.findChapterDir(
                        chapter.name,
                        chapter.scanlator,
                        chapter.url,
                        manga.ogTitle,
                        source,
                    )
                } ?: continue

                if (chapterDir?.isFile == true) {
                    // Archive (.cbz) chapter — collect entry names, re-open archive per stream
                    try {
                        val remainingPages = if (maxPages > 0) maxPages - result.size else 0
                        val entryNames: List<String> = chapterDir.archiveReader(context).use { reader ->
                            reader.useEntries { entries ->
                                selectArchiveImageNames(
                                    entries = entries,
                                    isImage = { name -> ImageUtil.isImage(name) { reader.getInputStream(name)!! } },
                                    maxPages = remainingPages,
                                )
                            }
                        }
                        val archiveModifiedAt = chapterDir.lastModified().takeIf { it > 0 }
                        val archiveSize = chapterDir.length().takeIf { it > 0 }
                        entryNames.forEachIndexed { idx, entryName ->
                            val capturedDir = chapterDir
                            val identity = "${capturedDir.uri}!$entryName"
                            result.add(
                                OcrPageRef(
                                    manga = manga,
                                    chapter = chapter,
                                    pageIndex = idx,
                                    pageIdentity = identity,
                                    pageModifiedAt = archiveModifiedAt,
                                    pageSize = archiveSize,
                                    streamProvider = {
                                        runCatching {
                                            openArchiveEntryStream(capturedDir, entryName)
                                        }.getOrNull()
                                    },
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        continue
                    }
                } else {
                    // Directory chapter
                    try {
                        val pages = downloadManager.buildPageList(source, manga, chapter)
                        for (page in pages) {
                            if (maxPages > 0 && result.size >= maxPages) return result
                            val uri = page.uri ?: continue
                            val pageFile = chapterDir?.findFile(uri.lastPathSegment ?: "")
                            val identity = uri.toString()
                            result.add(
                                OcrPageRef(
                                    manga = manga,
                                    chapter = chapter,
                                    pageIndex = page.index,
                                    pageIdentity = identity,
                                    pageModifiedAt = pageFile?.lastModified()?.takeIf { it > 0 },
                                    pageSize = pageFile?.length()?.takeIf { it > 0 },
                                    streamProvider = {
                                        runCatching {
                                            context.contentResolver.openInputStream(uri)
                                        }.getOrNull()
                                    },
                                ),
                            )
                        }
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
        }
        return result
    }
}

// KMK <--
