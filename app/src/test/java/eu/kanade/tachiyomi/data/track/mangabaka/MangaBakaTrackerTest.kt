package eu.kanade.tachiyomi.data.track.mangabaka

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaItemResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaListResult
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaOAuth
import eu.kanade.tachiyomi.data.track.mangabaka.dto.MangaBakaUserProfileResponse
import eu.kanade.tachiyomi.util.PkceUtil
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

// KMK --> v0.8.24: MangaBaka tracker (ported from Mihon). Response shapes, status round trip, token expiry, PKCE and
// the Komikku-specific wiring (own OAuth application, redirect and hidden-when-unconfigured Settings row).
class MangaBakaTrackerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `library entry decodes and every state maps back to the same API value`() {
        val states = listOf("considering", "completed", "dropped", "paused", "plan_to_read", "reading", "rereading")
        states.forEach { state ->
            val entry = json.decodeFromString<MangaBakaListResult>(
                """{"data":{"state":"$state","start_date":"2026-01-02T00:00:00Z","finish_date":null,""" +
                    """"is_private":true,"progress_chapter":12.5,"rating":80,"extra":1}}""",
            ).data
            val track = Track.create(MANGABAKA_ID).apply { status = entry.getStatus() }
            assertEquals(state, track.toApiStatus())
            assertEquals(12.5, entry.progressChapter)
            assertEquals(80L, entry.rating)
            assertTrue(entry.isPrivate)
        }
    }

    @Test
    fun `series title prefers the primary English title, then romanized Japanese`() {
        val item = json.decodeFromString<MangaBakaItemResult>(
            """{"data":{"id":42,"cover":{"x250":{"x1":null}},"authors":null,"artists":null,"description":null,""" +
                """"published":{"start_date":null},"status":"releasing","type":"manhwa","rating":null,"titles":[""" +
                """{"language":"ja","traits":["native"],"title":"ネイティブ","is_primary":false},""" +
                """{"language":"ja-Latn","traits":[],"title":"Romaji","is_primary":false},""" +
                """{"language":"en","traits":[],"title":"English alt","is_primary":false},""" +
                """{"language":"en","traits":["official"],"title":"English official","is_primary":false}]}}""",
        ).data
        assertEquals("English official", item.chooseBestTitle())

        val noEnglish = item.copy(titles = item.titles!!.filter { it.language != "en" })
        assertEquals("Romaji", noEnglish.chooseBestTitle())
    }

    @Test
    fun `profile uses nickname, then preferred username, then id as the shown account name`() {
        fun profile(nickname: String?, preferred: String?) = json.decodeFromString<MangaBakaUserProfileResponse>(
            """{"data":{"id":"u1","rating_steps":5,"nickname":${nickname.q()},"preferred_username":${preferred.q()}}}""",
        ).data
        assertEquals("Nick", profile("Nick", "pref").displayName())
        assertEquals("pref", profile(null, "pref").displayName())
        assertEquals("u1", profile(null, null).displayName())
    }

    @Test
    fun `token counts as expired a minute before its expiry`() {
        val now = System.currentTimeMillis() / 1000
        assertTrue(MangaBakaOAuth("a", "r", now + 30).isExpired())
        assertFalse(MangaBakaOAuth("a", "r", now + 600).isExpired())
    }

    @Test
    fun `PKCE challenge is the unpadded base64url SHA-256 of the verifier`() {
        val codes = PkceUtil.generateS256Codes()
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(codes.codeVerifier.toByteArray()))
        assertEquals(expected, codes.codeChallenge)
        assertFalse(codes.codeChallenge.contains('='))
    }

    @Test
    fun `Komikku uses its own MangaBaka application, redirect and Mihon-compatible tracker id`() {
        val api = read("src/main/java/eu/kanade/tachiyomi/data/track/mangabaka/MangaBakaApi.kt")
        assertTrue(api.contains("BuildConfig.MANGABAKA_CLIENT_ID"))
        assertFalse(api.contains("zEZYMHXLWsLsafgbvJHXqzGvqQNOdkpo"), "Mihon's client ID must not be reused")
        assertTrue(api.contains("\"komikku://mangabaka-auth\""))
        assertFalse(api.contains("Mihon/v"))

        assertTrue(read("src/main/AndroidManifest.xml").contains("android:host=\"mangabaka-auth\""))
        assertTrue(read("src/main/java/eu/kanade/tachiyomi/data/track/TrackerManager.kt").contains("MANGABAKA = 11L"))
        assertTrue(
            read("src/main/java/eu/kanade/tachiyomi/ui/setting/track/TrackLoginActivity.kt")
                .contains("\"mangabaka-auth\" -> handleMangaBaka(data[\"code\"], data[\"state\"])"),
        )
        assertTrue(
            read("src/main/java/eu/kanade/presentation/more/settings/screen/SettingsTrackingScreen.kt")
                .contains(".takeIf { MangaBakaApi.isConfigured }"),
            "without a client ID the Settings row is hidden instead of offering a sign-in that cannot work",
        )
    }

    private fun String?.q() = this?.let { "\"$it\"" } ?: "null"

    private fun read(path: String) = File(path).readText().replace("\r\n", "\n")

    private companion object {
        const val MANGABAKA_ID = 11L
    }
}
// KMK <--
