package exh.recs

// KMK v0.8.16 -->
/**
 * Splits [KmkRecsReleaseNotes.MARKDOWN] into per-version sections and groups them by major.minor
 * family (e.g. "v0.8", "v0.7") so [eu.kanade.tachiyomi.ui.more.KmkRecsWhatsNewScreen] can keep the
 * current family expanded and collapse older families by default, without losing or reformatting
 * any historical entry. `KmkRecsReleaseNotes.MARKDOWN` stays the single source of truth; this policy
 * only reads it.
 */
object KmkRecsReleaseNotesGroupingPolicy {

    data class Section(val version: String, val body: String)

    data class Group(
        val family: String,
        val sections: List<Section>,
        val expandedByDefault: Boolean,
        val summary: String,
    )

    private val headingRegex = Regex("(?m)^\\s*## KMK-Recs (v\\S+)\\s*$")

    // KMK v0.8.16: short, app-facing era summaries shown on a collapsed family header. Purely
    // descriptive -- never referenced by tests as the source of truth for what shipped, only the
    // full Markdown body (revealed on expand) is that.
    private val familySummaries = mapOf(
        "v0.8" to "Reading controls, rated collections, settings and search polish, source-runtime stabilization, and For You/Source Evaluation quality improvements.",
        "v0.7" to "Source Evaluation, grouped recommendations, Best Version foundations, and discovery memory.",
        "v0.6" to "Source Evaluation foundation, install/cleanup safety, and Sources to Try.",
        "v0.5" to "Early recommendation, rating, and Top Picks foundations.",
        "v0.4" to "Early recommendation, rating, and Top Picks foundations.",
    )

    fun parseSections(markdown: String): List<Section> {
        val matches = headingRegex.findAll(markdown).toList()
        return matches.mapIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else markdown.length
            Section(version = match.groupValues[1], body = markdown.substring(start, end).trimEnd())
        }
    }

    /** v0.8.25: a version card's notes without the "## KMK-Recs vX" heading line, which the card shows as its title. */
    fun contentOf(section: Section): String =
        section.body.trimIndent().lines().drop(1).joinToString("\n").trim()

    /**
     * v0.8.25: a one-line summary for a collapsed version card: the first paragraph of [markdown] outside headings and
     * lists, or the first list item when there is no paragraph. Markdown marks are removed and long text is shortened.
     */
    fun summaryOf(markdown: String, maxLength: Int = 140): String {
        val lines = markdown.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val paragraph = lines.firstOrNull { line ->
            !line.startsWith("#") && !line.startsWith("-") && !line.startsWith("*") && !line.startsWith("<") &&
                !line.startsWith(">") && !line.startsWith("|") && !line.startsWith("```")
        }
        val text = paragraph ?: lines.firstOrNull { it.startsWith("- ") || it.startsWith("* ") }?.drop(2) ?: return ""
        val plain = text
            .replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace("**", "")
            .replace("`", "")
            .replace("KMK-Recs", "Komikku FC")
            .trim()
        return if (plain.length <= maxLength) plain else plain.take(maxLength - 1).trimEnd() + "…"
    }

    /** "v0.8.16" / "v0.8.15-fix1" -> "v0.8"; anything unparsable falls back to the raw version string. */
    fun familyOf(version: String): String {
        val core = version.removePrefix("v").substringBefore("-")
        val parts = core.split(".")
        val major = parts.getOrNull(0) ?: return version
        val minor = parts.getOrNull(1) ?: return "v$major"
        return "v$major.$minor"
    }

    fun group(markdown: String): List<Group> {
        val sections = parseSections(markdown)
        if (sections.isEmpty()) return emptyList()
        val latestFamily = familyOf(sections.first().version)
        val orderedFamilies = LinkedHashSet<String>()
        sections.forEach { orderedFamilies.add(familyOf(it.version)) }
        val byFamily = sections.groupBy { familyOf(it.version) }
        return orderedFamilies.map { family ->
            Group(
                family = family,
                sections = byFamily[family].orEmpty(),
                expandedByDefault = family == latestFamily,
                summary = familySummaries[family] ?: "Historical changes for $family.",
            )
        }
    }
}
// KMK <--
