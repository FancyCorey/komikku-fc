package exh.taste

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.taste.interactor.TasteWeighting
import tachiyomi.domain.taste.interactor.TasteWeighting.RatedTitle

class TasteWeightingTest {

    private val now = 1_800_000_000_000L
    private val month = 30L * 24 * 60 * 60 * 1000

    private fun title(weight: Double, vararg groups: String, monthsAgo: Int = 0) =
        RatedTitle(weight, if (monthsAgo == 0) now else now - monthsAgo * month, groups.toSet())

    @Test
    fun `weights stay inside the old range and keep growing with more support instead of tying at the cap`() {
        val five = TasteWeighting.learnedWeights((1..5).map { title(2.0, "a", "b$it") }, now)["a"]!!
        val twenty = TasteWeighting.learnedWeights((1..20).map { title(2.0, "a", "b$it") }, now)["a"]!!
        assertTrue(five in 0.0..10.0)
        assertTrue(twenty in 0.0..10.0)
        assertTrue(twenty > five, "twenty loved titles ($twenty) must outrank five ($five); a hard clip would tie them")
    }

    @Test
    fun `a rare tag counts for more than a tag on every rated title`() {
        val titles = (1..10).map { title(2.0, "common", if (it == 1) "rare" else "other$it") }
        val weights = TasteWeighting.learnedWeights(titles, now)
        // "rare" is on 1 title, "common" on all 10; give both the same raw support by comparing per-title contribution
        val rareFactor = TasteWeighting.rarityFactor(1, 10)
        val commonFactor = TasteWeighting.rarityFactor(10, 10)
        assertTrue(rareFactor > commonFactor)
        assertEquals(TasteWeighting.RARITY_MIN, commonFactor, 1e-9)
        assertTrue(rareFactor <= TasteWeighting.RARITY_MAX)
        assertTrue(weights["common"]!! > 0.0)
    }

    @Test
    fun `rarity never leaves its bounds`() {
        for (total in listOf(1, 2, 5, 50, 500)) {
            for (with in 1..total) {
                val f = TasteWeighting.rarityFactor(with, total)
                assertTrue(f in TasteWeighting.RARITY_MIN..TasteWeighting.RARITY_MAX, "total=$total with=$with factor=$f")
            }
        }
    }

    @Test
    fun `an old rating counts for less but never below the floor, and an unknown age is not aged`() {
        assertEquals(1.0, TasteWeighting.ageFactor(0L, now), 0.0)
        assertEquals(1.0, TasteWeighting.ageFactor(now, now), 0.0)
        assertEquals(1.0, TasteWeighting.ageFactor(now - 15 * 24 * 60 * 60 * 1000L, now), 0.0) // under a month: whole months only
        assertEquals(0.5, TasteWeighting.ageFactor(now - 6 * month, now), 1e-9)
        assertEquals(TasteWeighting.DECAY_FLOOR, TasteWeighting.ageFactor(now - 120 * month, now), 0.0)
        val withAge = TasteWeighting.Options(age = true)
        val fresh = TasteWeighting.learnedWeights(listOf(title(2.0, "x")), now, withAge)["x"]!!
        val old = TasteWeighting.learnedWeights(listOf(title(2.0, "x", monthsAgo = 12)), now, withAge)["x"]!!
        assertTrue(old < fresh)
    }

    @Test
    fun `age is off by default, so an old rating weighs the same as a new one`() {
        assertEquals(false, TasteWeighting.Options.DEFAULT.age)
        val fresh = TasteWeighting.learnedWeights(listOf(title(2.0, "x")), now)["x"]!!
        val old = TasteWeighting.learnedWeights(listOf(title(2.0, "x", monthsAgo = 36)), now)["x"]!!
        assertEquals(fresh, old, 0.0)
    }

    @Test
    fun `age steps are whole months so the weights do not change from one day to the next`() {
        val withAge = TasteWeighting.Options(age = true)
        val a = TasteWeighting.learnedWeights(listOf(title(2.0, "x", monthsAgo = 3)), now, withAge)["x"]!!
        val b = TasteWeighting.learnedWeights(listOf(title(2.0, "x", monthsAgo = 3)), now + 10 * 24 * 60 * 60 * 1000L, withAge)["x"]!!
        assertEquals(a, b, 0.0)
    }

    @Test
    fun `one Dislike on a tag counts for half and a second one counts fully`() {
        val one = TasteWeighting.learnedWeights(listOf(title(-2.0, "t", "u1"), title(2.0, "u2", "u3")), now)["t"]!!
        val two = TasteWeighting.learnedWeights(
            listOf(title(-2.0, "t", "u1"), title(-2.0, "t", "u2"), title(2.0, "u3", "u4")),
            now,
        )["t"]!!
        assertTrue(one < 0.0)
        // two dislikes: full strength each, so well more than double the single (halved) one
        assertTrue(two < one * 2.0, "one=$one two=$two")
    }

    @Test
    fun `a Dislike can still be outweighed by Loves on the same tag and an empty list gives no weights`() {
        val mixed = TasteWeighting.learnedWeights(listOf(title(2.0, "t", "a"), title(2.0, "t", "b"), title(-2.0, "t", "c")), now)["t"]!!
        assertTrue(mixed > 0.0)
        assertTrue(TasteWeighting.learnedWeights(emptyList(), now).isEmpty())
    }
}
