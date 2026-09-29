package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ForYouInstalledCorpusSourceContractTest {
    @Test
    fun `installed source fixture declares a bounded corpus and real filter path`() {
        val source = File(
            "../fixture-sources-to-try-extension/src/main/java/app/komikku/fixture/sources/FixtureSource.kt",
        ).readText()

        assertTrue(source.contains("override fun getFilterList()"))
        assertTrue(source.contains("private fun searchCorpus"))
        assertTrue(source.contains("private object FixtureCorpus"))
        assertTrue(source.contains("preferred-action"))
        assertTrue(source.contains("disliked-comedy"))
        assertTrue(source.contains("/blocked"))
        assertTrue(source.contains("missing-tags"))
        assertTrue(source.contains("novelty"))
        assertTrue(source.contains("listOf(\"Action\", \"Webtoon\")"))
        assertTrue(source.contains("query.contains(\"action\", ignoreCase = true)"))
        assertTrue(source.contains("null, \"alpha/recommendation/missing\""))
        assertEquals(6, Regex("CorpusEntry\\(\\\"/kmk-fixture/recommendation/alpha/").findAll(source).count())
        assertEquals(6, Regex("CorpusEntry\\(\\\"/kmk-fixture/recommendation/beta/").findAll(source).count())
        assertTrue(source.contains("query.contains(\"middle motion\", ignoreCase = true)"))
        assertTrue(source.contains("title = \"Fixture Middle Motion\""))
        assertTrue(source.contains("chapterNumbers = 1..101"))
    }
}
