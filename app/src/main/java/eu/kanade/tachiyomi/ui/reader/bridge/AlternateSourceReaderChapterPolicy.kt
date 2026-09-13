package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.tachiyomi.source.model.SChapter
import tachiyomi.domain.chapter.service.ChapterRecognition
import kotlin.math.abs

/** Shapes a source chapter response for the alternate-source chooser. */
object AlternateSourceReaderChapterPolicy {

    /** Selects the exact current number first, then the nearest available lower-tied number. */
    fun recommendedChapter(
        chapters: List<AlternateSourceReaderChapterCandidate>,
        currentChapterNumber: Float,
    ): AlternateSourceReaderChapterCandidate? {
        if (!currentChapterNumber.isFinite() || currentChapterNumber < 0f) return null
        return chapters.firstOrNull {
            it.chapterNumber.isFinite() &&
                it.chapterNumber >= 0f &&
                it.chapterNumber == currentChapterNumber
        }
            ?: chapters
                .asSequence()
                .filter { it.chapterNumber.isFinite() && it.chapterNumber >= 0f }
                .minWithOrNull(
                    compareBy<AlternateSourceReaderChapterCandidate> {
                        abs(it.chapterNumber.toDouble() - currentChapterNumber.toDouble())
                    }.thenBy { it.chapterNumber },
                )
    }

    /**
     * Resolves source-number sentinels from chapter labels, removes only duplicate URLs, and
     * returns a stable newest-first list. Same-number chapters remain available because they can
     * be distinct scanlation variants or named specials.
     */
    fun normalizeAndSort(mangaTitle: String, chapters: List<SChapter>): List<SChapter> =
        chapters
            .asSequence()
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .mapNotNull { chapter ->
                val rawNumber = chapter.chapter_number.toDouble()
                val resolvedNumber = ChapterRecognition.parseChapterNumber(
                    mangaTitle = mangaTitle,
                    chapterName = chapter.name,
                    chapterNumber = rawNumber.takeIf { it.isFinite() },
                )
                // The shared parser uses -1.0 for an unrecognized label. That sentinel is valid
                // when it came from a source, but must not turn an invalid NaN/Infinity value into
                // a fabricated chapter when the source supplied no usable number.
                if (!resolvedNumber.isFinite() || (!rawNumber.isFinite() && resolvedNumber == -1.0)) {
                    null
                } else if (resolvedNumber != rawNumber) {
                    SChapter.create().also {
                        it.copyFrom(chapter)
                        it.chapter_number = resolvedNumber.toFloat()
                    }
                } else {
                    chapter
                }
            }
            .sortedWith(
                compareByDescending<SChapter> { it.chapter_number }
                    .thenBy { it.name }
                    .thenBy { it.url },
            )
            .toList()
}
