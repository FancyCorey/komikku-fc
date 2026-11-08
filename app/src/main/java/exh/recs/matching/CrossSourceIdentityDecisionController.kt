package exh.recs.matching

import exh.util.CrossSourceIdentityUndoJournal
import exh.util.CrossSourceIdentityUndoRecorder
import kotlinx.coroutines.CancellationException
import tachiyomi.domain.taste.interactor.GetCrossSourceIdentityDecisions
import tachiyomi.domain.taste.interactor.ReplaceCrossSourceIdentityDecisions
import tachiyomi.domain.taste.model.CrossSourceIdentityDecision
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionPolicy
import tachiyomi.domain.taste.model.CrossSourceIdentityDecisionValue
import tachiyomi.domain.taste.model.CrossSourceIdentityPair
import tachiyomi.domain.taste.model.CrossSourceIdentityReplacement
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class CrossSourceIdentityMutation { CONFIRM, REJECT, CLEAR }

enum class CrossSourceIdentityMutationResult { APPLIED, UNCHANGED, CONFLICT, FAILED }

/**
 * KMK v0.8.25: result of [CrossSourceIdentityDecisionController.confirmUnlessRejected]. [written] are the decisions this call saved
 * (pairs that were already confirmed are not rewritten); [rejected] are the pairs left alone because the user said they are not the
 * same manga.
 */
data class CrossSourceIdentityConfirmOutcome(
    val result: CrossSourceIdentityMutationResult,
    val written: List<CrossSourceIdentityDecision> = emptyList(),
    val rejected: List<CrossSourceIdentityPair> = emptyList(),
)

class CrossSourceIdentityDecisionController(
    private val getDecisions: GetCrossSourceIdentityDecisions = Injekt.get(),
    private val replaceDecisions: ReplaceCrossSourceIdentityDecisions = Injekt.get(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun mutate(
        pair: CrossSourceIdentityPair,
        mutation: CrossSourceIdentityMutation,
    ): CrossSourceIdentityMutationResult {
        return mutateAll(listOf(pair), mutation)
    }

    suspend fun mutateAll(
        pairs: List<CrossSourceIdentityPair>,
        mutation: CrossSourceIdentityMutation,
    ): CrossSourceIdentityMutationResult {
        return try {
            val canonicalPairs = pairs.map { CrossSourceIdentityDecisionPolicy.canonicalPair(it.left, it.right) }.distinct()
            val replacements = canonicalPairs.mapNotNull { pair ->
                val previous = getDecisions.await(pair)
                replacementFor(pair, previous, mutation)?.let { CrossSourceIdentityReplacement(previous, it) }
            }
            if (replacements.isEmpty()) return CrossSourceIdentityMutationResult.UNCHANGED
            val entry = CrossSourceIdentityUndoRecorder.build(replacements, mutation)
            if (!replaceDecisions.await(replacements)) return CrossSourceIdentityMutationResult.CONFLICT
            CrossSourceIdentityUndoJournal.record(entry)
            CrossSourceIdentityMutationResult.APPLIED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrossSourceIdentityMutationResult.FAILED
        }
    }

    /**
     * KMK v0.8.25: confirms what grouping implies without overruling the user. A pair the user marked "Not the same manga" is
     * never turned into a confirmation here; it is returned in [CrossSourceIdentityConfirmOutcome.rejected] so the caller can ask.
     */
    suspend fun confirmUnlessRejected(pairs: List<CrossSourceIdentityPair>): CrossSourceIdentityConfirmOutcome {
        return try {
            val canonicalPairs = pairs.map { CrossSourceIdentityDecisionPolicy.canonicalPair(it.left, it.right) }.distinct()
            val rejected = mutableListOf<CrossSourceIdentityPair>()
            val replacements = canonicalPairs.mapNotNull { pair ->
                val previous = getDecisions.await(pair)
                if (CrossSourceIdentityDecisionPolicy.isCurrentRejection(previous)) {
                    rejected += pair
                    null
                } else {
                    replacementFor(pair, previous, CrossSourceIdentityMutation.CONFIRM)
                        ?.let { CrossSourceIdentityReplacement(previous, it) }
                }
            }
            if (replacements.isEmpty()) {
                return CrossSourceIdentityConfirmOutcome(CrossSourceIdentityMutationResult.UNCHANGED, rejected = rejected)
            }
            val entry = CrossSourceIdentityUndoRecorder.build(replacements, CrossSourceIdentityMutation.CONFIRM)
            if (!replaceDecisions.await(replacements)) {
                return CrossSourceIdentityConfirmOutcome(CrossSourceIdentityMutationResult.CONFLICT, rejected = rejected)
            }
            CrossSourceIdentityUndoJournal.record(entry)
            CrossSourceIdentityConfirmOutcome(
                CrossSourceIdentityMutationResult.APPLIED,
                written = replacements.mapNotNull { it.replacement },
                rejected = rejected,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CrossSourceIdentityConfirmOutcome(CrossSourceIdentityMutationResult.FAILED)
        }
    }

    private fun replacementFor(
        pair: CrossSourceIdentityPair,
        previous: CrossSourceIdentityDecision?,
        mutation: CrossSourceIdentityMutation,
    ): CrossSourceIdentityDecision? = when (mutation) {
        CrossSourceIdentityMutation.CONFIRM -> {
            if (CrossSourceIdentityDecisionPolicy.isAuthoritativeConfirmation(previous)) {
                null
            } else {
                CrossSourceIdentityDecisionPolicy.userDecision(
                    pair,
                    CrossSourceIdentityDecisionValue.USER_CONFIRMED,
                    previous,
                    clock(),
                )
            }
        }
        CrossSourceIdentityMutation.REJECT -> {
            if (CrossSourceIdentityDecisionPolicy.isCurrentRejection(previous)) {
                null
            } else {
                CrossSourceIdentityDecisionPolicy.userDecision(
                    pair,
                    CrossSourceIdentityDecisionValue.USER_REJECTED,
                    previous,
                    clock(),
                )
            }
        }
        CrossSourceIdentityMutation.CLEAR -> {
            previous?.takeIf { it.deletedAt == null }?.let {
                CrossSourceIdentityDecisionPolicy.clearedDecision(it, clock())
            }
        }
    }
}
