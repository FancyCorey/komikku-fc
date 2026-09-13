package app.komikku.fixture.sources

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class FixtureSource : HttpSource(), PagePreviewSource {
    override val id = BuildConfig.SOURCE_ID
    override val name = "Fixture Source"
    override val lang = "en"
    override val supportsLatest = true
    override val baseUrl = LOOPBACK_BASE

    override suspend fun getPopularManga(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int) = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        val fixture = FixtureContent.forSource(id) ?: return MangasPage(emptyList(), false)
        if (page != 1 || query.isBlank() || !query.contains("fixture pair", ignoreCase = true)) {
            return MangasPage(emptyList(), false)
        }

        // Both fixture sources intentionally advertise the same searchable title while
        // retaining source-specific details and chapter URLs for cross-source validation.
        return MangasPage(
            mangas = listOf(
                SManga(
                    url = fixture.mangaUrl,
                    title = "Fixture Pair B",
                    description = "Synthetic offline reader fixture",
                    genre = "Fixture",
                    status = SManga.COMPLETED,
                    initialized = true,
                ),
            ),
            hasNextPage = false,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val fixture = FixtureContent.forSource(id) ?: return SMangaUpdate(manga, chapters)
        if (manga.url != fixture.mangaUrl) return SMangaUpdate(manga, chapters)
        val details = if (fetchDetails) {
            SManga(
                url = fixture.mangaUrl,
                title = fixture.title,
                description = "Synthetic offline reader fixture",
                genre = "Fixture",
                status = SManga.COMPLETED,
                initialized = true,
            )
        } else {
            manga
        }
        return SMangaUpdate(
            manga = details,
            chapters = if (fetchChapters) fixture.chapters else chapters,
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fixture = FixtureContent.forSource(id) ?: return emptyList()
        val chapterNumber = fixture.chapterNumber(chapter.url) ?: return emptyList()
        return (1..PAGE_COUNT).map { pageNumber ->
            Page(
                index = pageNumber - 1,
                imageUrl = if (BuildConfig.FAIL_PREVIEW_PAGE) {
                    previewImageUrl(fixture.serverPath, chapterNumber, pageNumber)
                } else {
                    "$LOOPBACK_BASE/pages/${fixture.serverPath}/chapter-$chapterNumber/page-$pageNumber.png"
                },
            )
        }
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response =
        fixtureImageResponse(page.imageUrl, isPreview = true)

    override suspend fun getPagePreviewList(
        manga: SManga,
        chapters: List<SChapter>,
        page: Int,
    ): PagePreviewPage {
        val chapter = chapters.getOrNull(page) ?: return PagePreviewPage(page, emptyList(), false, 0)
        val fixture = FixtureContent.forSource(id)
        val chapterNumber = fixture?.chapterNumber(chapter.url)
        val previews = if (chapterNumber == null) {
            emptyList()
        } else {
            (1..PAGE_COUNT).map { pageNumber ->
                PagePreviewInfo(
                    index = pageNumber - 1,
                    imageUrl = previewImageUrl(fixture.serverPath, chapterNumber, pageNumber),
                )
            }
        }
        return PagePreviewPage(page, previews, page + 1 < chapters.size, chapters.size)
    }

    private fun previewImageUrl(serverPath: String, chapterNumber: Int, pageNumber: Int): String {
        val suffix = if (BuildConfig.FAIL_PREVIEW_PAGE) "?syntheticPreviewFailure=1" else ""
        return "$LOOPBACK_BASE/pages/$serverPath/chapter-$chapterNumber/page-$pageNumber.png$suffix"
    }

    override suspend fun getImage(page: Page): Response = fixtureImageResponse(requireNotNull(page.imageUrl))

    private fun fixtureImageResponse(url: String, isPreview: Boolean = false): Response {
        val page = url.substringAfterLast("page-").substringBefore('.').toIntOrNull() ?: 1
        if (BuildConfig.FAIL_PREVIEW_PAGE && page == 1) {
            return Response.Builder()
                .request(Request.Builder().url(url).build())
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(503)
                .message("Synthetic preview failure")
                .body("synthetic preview failure".toResponseBody("text/plain".toMediaType()))
                .build()
        }
        val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val chapter = url.substringAfter("chapter-").substringBefore('/').toIntOrNull() ?: 0
        val accent = if (url.contains("alpha")) Color.rgb(82, 112, 184) else Color.rgb(184, 92, 120)
        canvas.drawColor(Color.rgb(242, 238, 230))

        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 40)
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(35, 35, 40)
            textSize = 34f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(70, 70, 80)
            textSize = 26f
        }

        canvas.drawRect(30f, 30f, 570f, 280f, panelPaint)
        canvas.drawRect(30f, 310f, 570f, 590f, panelPaint)
        canvas.drawRect(30f, 620f, 570f, 870f, panelPaint)
        canvas.drawRect(30f, 30f, 570f, 280f, borderPaint)
        canvas.drawRect(30f, 310f, 570f, 590f, borderPaint)
        canvas.drawRect(30f, 620f, 570f, 870f, borderPaint)
        canvas.drawCircle(190f + (page * 35f), 150f, 76f, accentPaint)
        canvas.drawRect(100f, 420f, 500f, 470f, accentPaint)
        canvas.drawRect(140f, 700f, 460f, 750f, accentPaint)
        canvas.drawText("Fixture page $page", 70f, 105f, textPaint)
        canvas.drawText("Chapter $chapter", 70f, 235f, smallTextPaint)
        canvas.drawText("Synthetic reader panel", 70f, 555f, smallTextPaint)
        canvas.drawText("Source-aware test image", 70f, 835f, smallTextPaint)

        val bytes = ByteArrayOutputStream().also { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }.toByteArray()
        bitmap.recycle()
        return Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody(IMAGE_MEDIA_TYPE))
            .build()
    }

    private data class FixtureContent(
        val mangaUrl: String,
        val title: String,
        val serverPath: String,
        val chapterNumbers: IntRange,
    ) {
        val chapters: List<SChapter>
            get() = chapterNumbers.map { number ->
                SChapter(
                    name = "Chapter $number",
                    url = "$mangaUrl/chapter-$number",
                    date_upload = FIXED_UPLOAD_TIME + number,
                    chapter_number = number.toFloat(),
                )
            }

        fun chapterNumber(url: String): Int? = chapterNumbers.singleOrNull { url == "$mangaUrl/chapter-$it" }

        companion object {
            fun forSource(sourceId: Long): FixtureContent? = when (sourceId) {
                ALPHA_SOURCE_ID -> FixtureContent(
                    mangaUrl = "/kmk-fixture/f2/origin",
                    title = "Fixture Pair A",
                    serverPath = "alpha/origin",
                    chapterNumbers = 1..3,
                )
                BETA_SOURCE_ID -> FixtureContent(
                    mangaUrl = "/kmk-fixture/f2/target",
                    title = "Fixture Pair B",
                    serverPath = "beta/target",
                    chapterNumbers = 1..4,
                )
                else -> null
            }
        }
    }

    private companion object {
        const val ALPHA_SOURCE_ID = 910000000000000001L
        const val BETA_SOURCE_ID = 910000000000000002L
        const val LOOPBACK_BASE = "http://127.0.0.1:38291"
        const val PAGE_COUNT = 3
        const val FIXED_UPLOAD_TIME = 1_700_000_000_000L
        val IMAGE_MEDIA_TYPE = "image/png".toMediaType()
    }
}
