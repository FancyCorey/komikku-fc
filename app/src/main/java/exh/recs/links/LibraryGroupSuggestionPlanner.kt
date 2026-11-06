package exh.recs.links

import tachiyomi.domain.manga.model.Manga
import java.text.Normalizer
import java.util.Locale

// KMK v0.8.25 -->
/**
 * Pure planner for "Group library automatically". For every library title that is not in a group yet it finds the group it
 * most likely belongs to, the way Migrate finds the equivalent of a title: same name first, then any other name used by a
 * member of a group, then close names for the user to choose from. It decides nothing by itself; every suggestion is applied
 * only after the user accepts it.
 */
object LibraryGroupSuggestionPlanner {

    /** Every name a group is known by: its members' titles as saved in the link and as they are in the library. */
    data class GroupNames(val groupId: String, val label: String, val names: Set<String>)

    enum class Confidence { EXACT, POSSIBLE }

    sealed interface Target {
        data class Group(val groupId: String, val label: String) : Target

        /** Several ungrouped titles with the same name; [clusterKey] is that normalized name. */
        data class NewGroup(val clusterKey: String, val partnerTitles: List<String>) : Target
    }

    data class Suggestion(
        val manga: Manga,
        val confidence: Confidence,
        /** What is pre-selected. Null when there are several equally good groups and the user has to choose. */
        val target: Target?,
        /** Other groups worth offering in "Change", best first. */
        val alternatives: List<Target.Group>,
    )

    fun normalize(title: String): String {
        val folded = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{Mn}+"), "")
        return folded.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().removePrefix("the ").trim()
    }

    private fun tokens(normalized: String): Set<String> = normalized.split(' ').filter { it.length > 1 }.toSet()

    private fun close(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return false
        if (minOf(a.length, b.length) >= 6 && (a.contains(b) || b.contains(a))) return true
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.size < 2 || tb.size < 2) return false
        val shared = ta.intersect(tb).size.toDouble()
        return shared / ta.union(tb).size >= 0.6
    }

    fun plan(candidates: List<Manga>, groups: List<GroupNames>): List<Suggestion> {
        val normalizedGroups = groups.map { group -> group to group.names.map(::normalize).filter { it.isNotEmpty() }.toSet() }
        val clusters = candidates.groupBy { normalize(it.title) }.filterKeys { it.isNotEmpty() }
        val suggestions = candidates.mapNotNull { manga ->
            val names = setOf(normalize(manga.title), normalize(manga.ogTitle)).filter { it.isNotEmpty() }
            if (names.isEmpty()) return@mapNotNull null
            val exactGroups = normalizedGroups.filter { (_, known) -> names.any { it in known } }.map { it.first }
            val cluster = clusters[normalize(manga.title)].orEmpty()
            val closeGroups = normalizedGroups
                .filter { (group, known) -> group !in exactGroups && names.any { name -> known.any { close(name, it) } } }
                .map { it.first }
            fun asTarget(group: GroupNames) = Target.Group(group.groupId, group.label)
            when {
                exactGroups.size == 1 -> Suggestion(
                    manga,
                    Confidence.EXACT,
                    asTarget(exactGroups.single()),
                    closeGroups.take(3).map(::asTarget),
                )
                exactGroups.size > 1 -> Suggestion(
                    manga,
                    Confidence.POSSIBLE,
                    null,
                    (exactGroups + closeGroups).take(4).map(::asTarget),
                )
                cluster.size >= 2 -> Suggestion(
                    manga,
                    Confidence.EXACT,
                    Target.NewGroup(normalize(manga.title), cluster.filter { it !== manga }.map { it.title }),
                    closeGroups.take(3).map(::asTarget),
                )
                closeGroups.isNotEmpty() -> Suggestion(manga, Confidence.POSSIBLE, null, closeGroups.take(4).map(::asTarget))
                else -> null
            }
        }
        return suggestions.sortedWith(
            compareBy<Suggestion> { it.confidence }.thenBy { it.manga.title.lowercase(Locale.ROOT) },
        )
    }
}
// KMK <--
