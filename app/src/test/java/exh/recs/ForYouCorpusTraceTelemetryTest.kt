package exh.recs

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ForYouCorpusTraceTelemetryTest {
    private fun manga(url: String, title: String, thumbnail: String? = null) = SManga.create().apply {
        this.url = url
        this.title = title
        thumbnail_url = thumbnail
    }

    @Test
    fun `fixture payload bytes count utf-8 url title and thumbnail and nothing else`() {
        val page = MangasPage(
            listOf(
                manga("/a", "Ab"),
                manga("/b", "é", "http://x/y"),
            ),
            hasNextPage = false,
        )

        // "/a"(2) + "Ab"(2); "/b"(2) + "é"(2 in UTF-8) + "http://x/y"(10)
        assertEquals(2 + 2 + 2 + 2 + 10, ForYouDebugFixture.fixturePayloadBytes(page))
        assertEquals(0, ForYouDebugFixture.fixturePayloadBytes(MangasPage(emptyList(), hasNextPage = false)))
    }

    @Test
    fun `result publication and stale rejection are traced at every commit and guard`() {
        val model = File("src/main/java/exh/recs/BrowsePersonalRecommendationsScreenModel.kt").readText()
        val telemetry = File("src/main/java/exh/recs/ForYouRefreshTelemetry.kt").readText()

        // updateItem has five stale exits (observation path x2, early guard, in-lock guard, refused commit).
        assertEquals(5, Regex("""ForYouRefreshTelemetry\.staleResultRejected\(""").findAll(model).count())
        assertEquals(2, Regex("""ForYouRefreshTelemetry\.resultPublished\(""").findAll(model).count())
        assertTrue(model.contains("if (committed) {"))
        assertTrue(telemetry.contains("fun resultPublished(generation: Long, sourceId: Long, candidates: Int)"))
        assertTrue(telemetry.contains("fun staleResultRejected(candidateGeneration: Long, activeGeneration: Long, sourceId: Long)"))
        // Corpus-only: the events never log outside the installed-corpus fixture modes.
        assertTrue(telemetry.contains("corpusTraceEnabled.set(mode.usesInstalledCorpus)"))
        assertTrue(telemetry.contains("if (!corpusTraceEnabled.get()) return"))
    }

    @Test
    fun `slow source reports cancellation before delegation and completion with payload size`() {
        val fixture = File("src/main/java/exh/recs/ForYouDebugFixture.kt").readText()

        assertTrue(fixture.contains("slow_source_request_cancelled"))
        assertTrue(fixture.contains("delegated=false"))
        assertTrue(fixture.contains("slow_source_request_delegated"))
        assertTrue(fixture.contains("fixture_payload_bytes="))
        // A cancelled wait must rethrow so structured cancellation is preserved.
        assertTrue(Regex("""catch \(e: CancellationException\) \{[\s\S]*?throw e""").containsMatchIn(fixture))
    }
}
