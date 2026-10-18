package exh.recs.matching

import exh.util.CrossSourceIdentityUndoJournal
import exh.util.FakeTasteRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey

class CrossSourceIdentityAuthorizationResolverTest {
    @Test
    fun `legacy membership is not authorization but explicit confirmation is`() = runTest {
        val repository = FakeTasteRepository()
        val get = GetCrossSourceIdentityDecisions(repository)
        val resolver = CrossSourceIdentityAuthorizationResolver(get)
        val members = listOf(link(1, "/a"), link(2, "/b"))
        assertEquals(listOf(members.first()), resolver.confirmedGroupMembers(1, "/a", members))
        assertFalse(resolver.isConfirmed(1, "/a", 2, "/b"))

        val controller = CrossSourceIdentityDecisionController(get, ReplaceCrossSourceIdentityDecisions(repository)) { 10_000 }
        controller.mutate(
            CrossSourceIdentityDecisionPolicy.canonicalPair(CrossSourceRecordKey(1, "/a"), CrossSourceRecordKey(2, "/b")),
            CrossSourceIdentityMutation.CONFIRM,
        )
        assertTrue(resolver.isConfirmed(1, "/a", 2, "/b"))
        assertEquals(members, resolver.confirmedGroupMembers(1, "/a", members))
        CrossSourceIdentityUndoJournal.clear()
    }

    @Test
    fun `only confirmed endpoints in the same legacy group become authorized presentation members`() {
        val links = listOf(
            CrossSourceMangaLink(1, "/a", "group", "A", 1, 1),
            CrossSourceMangaLink(2, "/b", "group", "B", 1, 1),
            CrossSourceMangaLink(3, "/c", "group", "C", 1, 1),
            CrossSourceMangaLink(4, "/d", "other", "D", 1, 1),
        )
        val confirmed = CrossSourceIdentityDecisionPolicy.userDecision(
            pair(1, "/a", 2, "/b"),
            CrossSourceIdentityDecisionValue.USER_CONFIRMED,
            previous = null,
            timestamp = 2,
        )
        val crossGroup = CrossSourceIdentityDecisionPolicy.userDecision(
            pair(1, "/a", 4, "/d"),
            CrossSourceIdentityDecisionValue.USER_CONFIRMED,
            previous = null,
            timestamp = 2,
        )

        val result = CrossSourceIdentityAuthorizationResolver.confirmedLinkGroupByKey(
            links,
            listOf(confirmed, crossGroup),
        )

        assertEquals(mapOf("1|/a" to "group", "2|/b" to "group"), result)
        assertFalse("3|/c" in result)
        assertFalse("4|/d" in result)
    }

    // KMK v0.8.24: confirmations chain. The user's real library confirmed every version against one hub version only
    // (Atsumaru = Asura, Manga Demon = Asura, ...); opening a non-hub version must still reach every confirmed version.
    @Test
    fun `confirmed versions are reached through a chain of confirmations`() = runTest {
        val repository = FakeTasteRepository()
        val get = GetCrossSourceIdentityDecisions(repository)
        val resolver = CrossSourceIdentityAuthorizationResolver(get)
        val controller = CrossSourceIdentityDecisionController(get, ReplaceCrossSourceIdentityDecisions(repository)) { 10_000 }
        val hub = link(1, "/hub")
        val a = link(2, "/a")
        val b = link(3, "/b")
        val c = link(4, "/c")
        val unconfirmed = link(5, "/e")
        listOf(a, b, c).forEach { member ->
            controller.mutate(pair(hub.source, hub.url, member.source, member.url), CrossSourceIdentityMutation.CONFIRM)
        }
        val members = listOf(hub, a, b, c, unconfirmed)

        assertEquals(listOf(hub, a, b, c), resolver.confirmedGroupMembers(a.source, a.url, members))
        assertEquals(listOf(hub, a, b, c), resolver.confirmedGroupMembers(c.source, c.url, members))

        // An explicit "not the same" between two versions wins over the chain.
        controller.mutate(pair(a.source, a.url, c.source, c.url), CrossSourceIdentityMutation.REJECT)
        assertEquals(listOf(hub, a, b), resolver.confirmedGroupMembers(a.source, a.url, members))
        CrossSourceIdentityUndoJournal.clear()
    }

    private fun link(source: Long, url: String) = CrossSourceMangaLink(source, url, "legacy", "Title", 1, 1)

    private fun pair(firstSource: Long, firstUrl: String, secondSource: Long, secondUrl: String) =
        CrossSourceIdentityDecisionPolicy.canonicalPair(
            CrossSourceRecordKey(firstSource, firstUrl),
            CrossSourceRecordKey(secondSource, secondUrl),
        )
}
