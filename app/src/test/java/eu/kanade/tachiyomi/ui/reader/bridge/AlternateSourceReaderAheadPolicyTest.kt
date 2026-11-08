package eu.kanade.tachiyomi.ui.reader.bridge

import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderAheadPolicy.Offer
import eu.kanade.tachiyomi.ui.reader.bridge.AlternateSourceReaderAheadPolicy.VersionChapters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// KMK v0.8.25 -->
class AlternateSourceReaderAheadPolicyTest {

    private fun version(key: String, vararg numbers: Float) = VersionChapters(key, numbers.toList())

    @Test
    fun `a version with a chapter after the finished one is ahead by its newest chapter and names the next one`() {
        val ahead = AlternateSourceReaderAheadPolicy.ahead(34f, listOf(version("A", 33f, 34f, 35f, 36f, 41f)))

        val a = ahead.single()
        assertEquals(35f, a.nextChapter)
        assertEquals(41f, a.latestChapter)
        assertEquals(7, a.chaptersAhead)
    }

    @Test
    fun `the same chapter number or an older one is not ahead`() {
        assertTrue(AlternateSourceReaderAheadPolicy.ahead(34f, listOf(version("A", 33f, 34f), version("B", 12f))).isEmpty())
    }

    @Test
    fun `a decimal chapter counts as ahead by at least one`() {
        val a = AlternateSourceReaderAheadPolicy.ahead(34f, listOf(version("A", 34f, 34.5f))).single()
        assertEquals(34.5f, a.nextChapter)
        assertEquals(1, a.chaptersAhead)
    }

    @Test
    fun `numbers that are not recognised never make a version ahead`() {
        val versions = listOf(version("A", -1f, Float.NaN, Float.POSITIVE_INFINITY), version("B"))
        assertTrue(AlternateSourceReaderAheadPolicy.ahead(34f, versions).isEmpty())
        assertTrue(AlternateSourceReaderAheadPolicy.ahead(Float.NaN, listOf(version("A", 40f))).isEmpty())
        assertTrue(AlternateSourceReaderAheadPolicy.ahead(-1f, listOf(version("A", 40f))).isEmpty())
    }

    @Test
    fun `versions that are ahead come most ahead first and ties keep a stable order`() {
        val ahead = AlternateSourceReaderAheadPolicy.ahead(
            34f,
            listOf(version("C", 35f), version("A", 41f), version("B", 36f), version("D", 35f)),
        )
        assertEquals(listOf("A", "B", "C", "D"), ahead.map { it.key })
    }

    @Test
    fun `the offer is none, one or several`() {
        assertEquals(Offer.None, AlternateSourceReaderAheadPolicy.offer(emptyList()))
        val one = AlternateSourceReaderAheadPolicy.ahead(34f, listOf(version("A", 35f)))
        assertTrue(AlternateSourceReaderAheadPolicy.offer(one) is Offer.One)
        val several = AlternateSourceReaderAheadPolicy.ahead(34f, listOf(version("A", 35f), version("B", 36f)))
        assertEquals(2, (AlternateSourceReaderAheadPolicy.offer(several) as Offer.Several).versions.size)
    }

    @Test
    fun `the latest chapter of every version is known even when none is ahead`() {
        val latest = AlternateSourceReaderAheadPolicy.latestByVersion(
            listOf(version("A", 30f, 34f), version("B", 33f), version("C", -1f), version("D")),
        )
        assertEquals(mapOf("A" to 34f, "B" to 33f), latest)
    }

    @Test
    fun `the chooser lists versions that are ahead first and keeps the rest in order`() {
        val ordered = AlternateSourceReaderAheadPolicy.orderAheadFirst(
            listOf("X", "A", "Y", "B"),
            aheadKeys = listOf("B", "A"),
            keyOf = { it },
        )
        assertEquals(listOf("B", "A", "X", "Y"), ordered)
        assertEquals(listOf("X", "Y"), AlternateSourceReaderAheadPolicy.orderAheadFirst(listOf("X", "Y"), emptyList()) { it })
    }

    @Test
    fun `the check is limited to six versions two at a time and a half hour of reuse`() {
        assertEquals(6, AlternateSourceReaderAheadPolicy.MAX_VERSIONS)
        assertEquals(2, AlternateSourceReaderAheadPolicy.MAX_CONCURRENT)
        assertEquals(30L * 60L * 1000L, AlternateSourceReaderAheadPolicy.CACHE_MILLIS)
    }
}
// KMK <--
