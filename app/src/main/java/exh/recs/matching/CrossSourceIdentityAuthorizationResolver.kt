package exh.recs.matching

import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecision
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceMangaLink
import tachiyomi.domain.taste.model.CrossSourceRecordKey
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CrossSourceIdentityAuthorizationResolver(
    private val getDecisions: GetCrossSourceIdentityDecisions = Injekt.get(),
) {
    companion object {
        fun confirmedLinkGroupByKey(
            links: List<CrossSourceMangaLink>,
            decisions: List<CrossSourceIdentityDecision>,
        ): Map<String, String> {
            val linkByRecord = links.associateBy { CrossSourceRecordKey(it.source, it.url) }
            return buildMap {
                decisions.asSequence()
                    .filter(CrossSourceIdentityDecisionPolicy::isAuthoritativeConfirmation)
                    .forEach { decision ->
                        val first = linkByRecord[decision.pair.left] ?: return@forEach
                        val second = linkByRecord[decision.pair.right] ?: return@forEach
                        if (first.groupId != second.groupId) return@forEach
                        put("${first.source}|${first.url}", first.groupId)
                        put("${second.source}|${second.url}", second.groupId)
                    }
            }
        }
    }

    suspend fun isConfirmed(
        firstSource: Long,
        firstUrl: String,
        secondSource: Long,
        secondUrl: String,
    ): Boolean {
        val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(
            CrossSourceRecordKey(firstSource, firstUrl),
            CrossSourceRecordKey(secondSource, secondUrl),
        )
        return CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(getDecisions.await(pair))
    }

    // KMK --> v0.8.24
    private suspend fun isRejected(firstSource: Long, firstUrl: String, secondSource: Long, secondUrl: String): Boolean {
        val pair = CrossSourceIdentityDecisionPolicy.canonicalPair(
            CrossSourceRecordKey(firstSource, firstUrl),
            CrossSourceRecordKey(secondSource, secondUrl),
        )
        return CrossSourceIdentityDecisionPolicy.isCurrentRejection(getDecisions.await(pair))
    }
    // KMK <--

    /**
     * Members of [origin]'s legacy group that the user confirmed as the same manga, in [members] order.
     *
     * KMK v0.8.24: confirmations chain. Users typically confirm every version against one version (A = hub, B = hub), so a
     * member counts when it is reachable from the origin through confirmed pairs, not only when it was confirmed directly
     * against the origin. A current explicit rejection between the origin and a member always excludes that member. Legacy
     * group membership alone still never authorizes anything.
     */
    suspend fun confirmedGroupMembers(
        originSource: Long,
        originUrl: String,
        members: List<CrossSourceMangaLink>,
    ): List<CrossSourceMangaLink> {
        fun key(link: CrossSourceMangaLink) = link.source to link.url
        val origin = originSource to originUrl
        val reached = linkedSetOf(origin)
        val frontier = ArrayDeque(listOf(origin))
        while (frontier.isNotEmpty()) {
            val (currentSource, currentUrl) = frontier.removeFirst()
            for (member in members) {
                val memberKey = key(member)
                if (memberKey in reached) continue
                if (isConfirmed(currentSource, currentUrl, member.source, member.url)) {
                    reached += memberKey
                    frontier += memberKey
                }
            }
        }
        return members.filter { member ->
            val memberKey = key(member)
            memberKey == origin ||
                (memberKey in reached && !isRejected(originSource, originUrl, member.source, member.url))
        }
    }
}
