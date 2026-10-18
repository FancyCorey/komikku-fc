package exh.recs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

// KMK v0.8.9 -->
/**
 * Tests for [KmkRecsReleaseNotes]. `MARKDOWN` remains a plain string (not a structured model) — see
 * the v0.8.9 implementation report and the in-code comment on `KmkRecsReleaseNotes.MARKDOWN` for why
 * that was the safer choice given `WhatsNewScreen`'s existing `MarkdownRender`/`GFMFlavourDescriptor`
 * renderer already fully supports the required hierarchy. These tests parse the same heading pattern
 * the renderer treats as a version boundary (`## KMK-Recs vX.Y.Z`) so regressions in history
 * ordering/completeness/duplication are still caught mechanically, without a structured data model.
 */
class KmkRecsReleaseNotesTest {

    private val recommendationStrings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()
    private val publicReleaseNotes = File("../docs/kmk/release-notes.md").readText()

    private val headingRegex = Regex("(?m)^\\s*## KMK-Recs (v\\S+)\\s*$")

    private fun headingMatches(): List<MatchResult> = headingRegex.findAll(KmkRecsReleaseNotes.MARKDOWN).toList()

    private fun headings(): List<String> = headingMatches().map { it.groupValues[1] }

    @Test
    fun `the current VERSION_NAME appears as the first (newest) heading`() {
        val first = headings().first()
        assertEquals(KmkRecsReleaseNotes.VERSION_NAME.removePrefix("KMK-Recs "), first)
    }

    @Test
    fun `the display version uses the approved forward-facing product name`() {
        assertEquals("Komikku FC v0.8.24", KmkRecsReleaseNotes.DISPLAY_VERSION_NAME)
        assertTrue(KmkRecsReleaseNotes.VERSION_NAME.startsWith("KMK-Recs "))
    }

    @Test
    fun `rendered release notes use the forward-facing product name`() {
        val displayMarkdown = KmkRecsReleaseNotes.displayMarkdown()

        assertTrue(displayMarkdown.contains("## Komikku FC v0.8.24"))
        assertTrue(displayMarkdown.contains("## Komikku FC v0.8.23.1"))
        assertFalse(displayMarkdown.contains("KMK-Recs"))
        assertTrue(KmkRecsReleaseNotes.MARKDOWN.contains("## KMK-Recs v0.8.23.1"))
    }

    @Test
    fun `no duplicate version headings`() {
        val all = headings()
        assertEquals(all.size, all.toSet().size, "duplicate version heading(s) found: ${all.groupingBy { it }.eachCount().filterValues { it > 1 }}")
    }

    @Test
    fun `every v0_8_x version from v0_8_0 through the previous release is present`() {
        val all = headings().toSet()
        val expected = listOf(
            "v0.8.0", "v0.8.1-fix1", "v0.8.1-fix2", "v0.8.1-fix3", "v0.8.1-fix4",
            "v0.8.2", "v0.8.3", "v0.8.4", "v0.8.5", "v0.8.6", "v0.8.7", "v0.8.8", "v0.8.9",
        )
        val missing = expected.filterNot { it in all }
        assertTrue(missing.isEmpty(), "missing historical v0.8.x entries: $missing")
    }

    // KMK v0.8.10-fix9 -->
    @Test
    fun `known historical versions across the full history still exist after the changelog conversion`() {
        val all = headings().toSet()
        val expected = listOf(
            "v0.8.11", "v0.8.10-fix9", "v0.8.10", "v0.8.9", "v0.8.8", "v0.8.0",
            "v0.7.47", "v0.7.45", "v0.7.0", "v0.6.8", "v0.5.0", "v0.4.2",
        )
        val missing = expected.filterNot { it in all }
        assertTrue(missing.isEmpty(), "missing historical entries: $missing")
    }

    private fun sectionBodies(): List<Pair<String, String>> {
        val matches = headingMatches()
        return matches.mapIndexed { i, m ->
            val start = m.range.first
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else KmkRecsReleaseNotes.MARKDOWN.length
            m.groupValues[1] to KmkRecsReleaseNotes.MARKDOWN.substring(start, end)
        }
    }

    @Test
    fun `the v0_8_23 entry documents linked group accessibility`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.23.1" }.second

        assertTrue(currentSection.contains("**Linked-version accessibility:**"))
        assertTrue(currentSection.contains("screen-reader labels and expanded or collapsed state"))
    }

    @Test
    fun `the v0_8_23 entry documents observable screen state fixes`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.23.1" }.second

        assertTrue(currentSection.contains("**Live screen state:**"))
        assertTrue(currentSection.contains("source-feed sorting availability in sync with saved searches"))
        assertTrue(currentSection.contains("debug-menu label formatting when the app language changes"))
    }

    @Test
    fun `the v0_8_23 entry documents URL-only open-source license access`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.23.1" }.second

        assertTrue(currentSection.contains("**Open-source licenses:**"))
        assertTrue(currentSection.contains("browser link when a library lists its license by URL"))
    }

    @Test
    fun `the v0_8_23 entry documents retry for fullscreen image load failures`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.23.1" }.second

        assertTrue(currentSection.contains("**Best Version fullscreen previews:**"))
        assertTrue(currentSection.contains("retry a page when its image download or decoding fails"))
        assertTrue(currentSection.contains("keeping already loaded pages intact"))
    }

    @Test
    fun `every version section contains a What's Changed heading`() {
        val missing = sectionBodies().filterNot { (_, body) -> body.contains("#### What's Changed") }.map { it.first }
        assertTrue(missing.isEmpty(), "sections missing '#### What's Changed': $missing")
    }

    @Test
    fun `every version section contains at least one New, Improve, or Fix category heading`() {
        val categoryRegex = Regex("##### (New|Improve|Fix)")
        val missing = sectionBodies().filterNot { (_, body) -> categoryRegex.containsMatchIn(body) }.map { it.first }
        assertTrue(missing.isEmpty(), "sections missing a category heading: $missing")
    }

    @Test
    fun `no version section has a flat bullet between its summary and What's Changed`() {
        val offenders = sectionBodies().filter { (_, body) ->
            val pre = body.substringBefore("#### What's Changed")
            Regex("(?m)^\\s*- ").containsMatchIn(pre)
        }.map { it.first }
        assertTrue(offenders.isEmpty(), "sections with a flat bullet before '#### What's Changed': $offenders")
    }

    @Test
    fun `the rendered changelog no longer contains stale wording about historical formatting decisions`() {
        val stale = listOf(
            "preserved exactly as it was written",
            "flat-bullet format",
            "reconfirmed this decision",
        )
        stale.forEach { phrase ->
            assertFalse(
                KmkRecsReleaseNotes.MARKDOWN.contains(phrase),
                "stale phrase still present in rendered changelog: \"$phrase\"",
            )
        }
    }

    @Test
    fun `v0 8 23 describes fail-closed For You source filters`() {
        val current = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(current.contains("For You source filters"))
        assertTrue(current.contains("profile restrictions active"))
    }

    @Test
    fun `v0 8 23 describes batched For You source results without a speed claim`() {
        val current = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(current.contains("For You refresh"))
        assertTrue(current.contains("successful source results are applied in batches as source requests finish"))
        assertTrue(current.contains("Refresh time still depends on how quickly your sources respond"))
        assertFalse(current.contains("waiting for the slowest source"))
    }
    // KMK <--

    @Test
    fun `history is substantial - no accidental truncation of older entries`() {
        // Regression guard: as of v0.8.11 this file has 87 entries going back to v0.4.2 (confirmed
        // by counting real "## KMK-Recs vX.Y.Z" headings). A truncation bug (e.g. an accidentally-
        // closed triple-quoted string) would silently drop most of them; the >= bound intentionally
        // still passes as future versions add more entries, without needing to be bumped every
        // release.
        assertTrue(headings().size >= 87, "expected at least 87 historical entries, found ${headings().size}")
    }

    @Test
    fun `headings are in strictly descending chronological order as written (newest-first)`() {
        // The renderer relies on source order for "newest first" -- verify the file wasn't
        // accidentally reordered. v0.8.24 is expected to be exactly first.
        val all = headings()
        assertEquals("v0.8.24", all[0])
        assertEquals("v0.8.23.1", all[1])
        assertEquals("v0.8.22", all[2])
        assertEquals("v0.8.21-fix2", all[3])
        assertEquals("v0.8.21", all[4])
        assertEquals("v0.8.20-fix5", all[5])
        assertEquals("v0.8.20-fix4", all[6])
        assertEquals("v0.8.20-fix3", all[7])
        assertEquals("v0.8.20-fix2", all[8])
        assertEquals("v0.8.20-fix1", all[9])
        assertEquals("v0.8.20", all[10])
        assertEquals("v0.8.19", all[11])
        assertEquals("v0.8.18-fix1", all[12])
    }

    @Test
    fun `the new v0_8_9 entry uses the official What's Changed structure`() {
        val v089Section = KmkRecsReleaseNotes.MARKDOWN.substringAfter("## KMK-Recs v0.8.9").substringBefore("## KMK-Recs v0.8.8")
        assertTrue(v089Section.contains("#### What's Changed"))
        assertTrue(v089Section.contains("##### New"))
        assertTrue(v089Section.contains("##### Improve"))
    }

    @Test
    fun `the v0_8_9 entry omits an empty Fix heading rather than rendering a blank section`() {
        val v089Section = KmkRecsReleaseNotes.MARKDOWN.substringAfter("## KMK-Recs v0.8.9").substringBefore("## KMK-Recs v0.8.8")
        assertFalse(v089Section.contains("##### Fix"))
    }

    @Test
    fun `the new v0_8_10 entry uses the official What's Changed structure with all three sub-headings`() {
        val v0810Section = KmkRecsReleaseNotes.MARKDOWN.substringAfter("## KMK-Recs v0.8.10").substringBefore("## KMK-Recs v0.8.9")
        assertTrue(v0810Section.contains("#### What's Changed"))
        assertTrue(v0810Section.contains("##### New"))
        assertTrue(v0810Section.contains("##### Improve"))
        assertTrue(v0810Section.contains("##### Fix"))
    }

    @Test
    fun `no internal build-channel terminology appears anywhere in the rendered changelog`() {
        val lower = KmkRecsReleaseNotes.MARKDOWN.lowercase()
        val forbidden = listOf("private build", "public build", "development artifact", "internal build")
        forbidden.forEach { term ->
            assertFalse(lower.contains(term), "forbidden build-channel term found: \"$term\"")
        }
    }

    // KMK v0.8.16: Find best version was moved out of the taste/rating dropdown into its own visible
    // manga action. Historical entries (e.g. v0.7.8, v0.6.20) legitimately used "rating menu" wording
    // for their own era and must not be rewritten -- only the current (newest) entry must not describe
    // the *current* placement that way.
    @Test
    fun `the current newest entry never describes Find best version as part of a rating menu`() {
        val sections = sectionBodies()
        val currentSection = sections.first().second.lowercase()
        assertFalse(currentSection.contains("rating menu"), "current changelog entry should not describe Find best version as inside a rating menu")
    }

    /** v0.8.21 itself (not "first" -- v0.8.21-fix2 is now first) retains its own historical claims unchanged. */
    private fun v0821Section(): String = sectionBodies().first { it.first == "v0.8.21" }.second

    @Test
    fun `the v0_8_21 entry covers the accepted focused recommendation and alignment changes`() {
        val section = v0821Section()
        assertTrue(section.contains("For You focus"))
        assertTrue(section.contains("16 KB ELF"))
        assertTrue(section.contains("Komikku FC"))
    }

    @Test
    fun `the v0_8_21 entry explains historical plans without internal review language`() {
        val section = v0821Section()
        listOf("ratings", "recommendation filters", "settings navigation", "comparing versions", "source recovery", "linked manga", "chapter matching", "tag information").forEach { topic ->
            assertTrue(section.contains(topic), "v0.8.21 entry omitted reader topic: $topic")
        }
        assertTrue(section.contains("planned at this point in the history"))
        assertTrue(section.contains("v0.8.21-fix2 and v0.8.22"))
        listOf("Accepted current scope", "registered host", "applicability-gated", "separately contracted", "unopened gate").forEach { phrase ->
            assertFalse(section.contains(phrase), "internal review wording remains: $phrase")
        }
    }

    @Test
    fun `alternate chapter and repeat switching fixes are documented`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.22" }.second
        assertTrue(currentSection.contains("Alternate-source chapters"))
        assertTrue(currentSection.contains("scanlation groups"))
        assertTrue(currentSection.contains("Switching sources"))
        assertTrue(currentSection.contains("View in Reader"))
    }

    // KMK v0.8.23.1 -->
    @Test
    fun `the v0_8_24 entry is the current newest entry`() {
        assertEquals("v0.8.24", sectionBodies().first().first)
    }

    // KMK --> v0.8.24: every accepted user-visible change maps to a line in the current entry, in reader language and
    // without a speed claim (no refresh-speed improvement was measured).
    @Test
    fun `the v0_8_24 entry maps each accepted change`() {
        val section = sectionBodies().first { it.first == "v0.8.24" }.second
        listOf(
            // recent-reading recommendations and its settings
            "**Recent-reading recommendations:**", "window of 1 to 30 days", "minimum chapters counted",
            "Blocked and disliked tags are never boosted",
            // linked-version ratings
            "**Group rating for linked versions:**", "or Not rated", "without overwriting a rating you set yourself",
            "**Rating linked versions:**", "**Best Version:**",
            // data saving
            "**Use less data on For You:**", "Automatic, Always and Never", "tries fewer search variations per source",
            "Every source is still searched and your filters are unchanged",
            // tracker updates
            "**Tracker refresh:**", "even with \"Sync reading progress from connected trackers\" off", "**Tracker retries:**",
            // For You scheduling and the Hide-known fix
            "**For You source scheduling:**", "instead of waiting for a whole group of five sources",
            "**Hide manga you already know:**", "no longer reappears after Refresh",
            "**Komikku updates:**", "**Reading timer:**", "**Local tracking across linked versions:**", "A = B and B = C",
            "**Reading from an alternate source:**", "returns to the reader instead of your library", "**Linked versions list:**",
            "**Alternate sources in Reader settings:**", "Continue from another source",
            "**Tracker progress on other versions:**", "same tracker entry",
            // settings redesign and the optional automatic source check
            "**Automatic source check:**", "off until you turn it on", "never installs or removes a source or changes your source order", "Use results on For You",
            "**Recommendation Settings:**", "one entry per major feature", "Every setting is still there",
        ).forEach { phrase -> assertTrue(section.contains(phrase), "v0.8.24 What's New is missing: $phrase") }
    }

    @Test
    fun `the v0_8_24 entry makes no speed claim and uses the official structure`() {
        val section = sectionBodies().first { it.first == "v0.8.24" }.second
        listOf("faster", "speed up", "sped up", "quicker", "improves loading", "instantly").forEach { word ->
            assertFalse(section.lowercase().contains(word), "unsupported speed wording in v0.8.24: $word")
        }
        assertTrue(section.contains("#### What's Changed"))
        assertTrue(section.contains("##### New") && section.contains("##### Improve") && section.contains("##### Fix"))
    }
    // KMK <--

    @Test
    fun `the v0_8_23 entry maps 16 KB Android memory page compatibility`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("16 KB memory pages"))
        assertTrue(currentSection.contains("improve compatibility"))
    }

    @Test
    fun `the v0_8_23 entry documents continuing with partial Best Version results`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("Best Version partial results"))
        assertTrue(currentSection.contains("later results cannot change the comparison"))
        assertTrue(publicReleaseNotes.contains("Best Version lets you continue with matches already found"))
    }

    @Test
    fun `the v0_8_23 entry describes shared same-manga source search scope`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("Same-manga source search"))
        assertTrue(currentSection.contains("version matching, and alternate-source reading"))
        assertTrue(publicReleaseNotes.contains("Same-manga source search"))
        assertTrue(publicReleaseNotes.contains("version matching, and alternate-source reading"))
    }

    @Test
    fun `the v0_8_23 entry documents retrying alternate-source failures`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("Fix one — alternate-source retry"))
        assertTrue(currentSection.contains("retry a failed source search or chapter lookup from the Reader"))
        assertTrue(publicReleaseNotes.contains("Retry a failed alternate-source search or chapter lookup from the Reader"))
    }

    @Test
    fun `the v0_8_23 entry maps Jump to last read list updates and screen cancellation`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("Jump to last read"))
        assertTrue(currentSection.contains("recheck the saved chapter if the displayed list changes during the jump"))
        assertTrue(currentSection.contains("cancel positioning when you leave the manga screen"))
    }

    @Test
    fun `the v0_8_23 Best Version search entry preserves configured settings`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("configured results-per-source limit"))
        assertTrue(currentSection.contains("title-selection and preview settings"))
        assertTrue(publicReleaseNotes.contains("respecting your Results per source limit"))
    }

    @Test
    fun `the v0_8_23 entry maps shorter-source progress and link-group changes`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        assertTrue(currentSection.contains("Shorter linked sources"))
        assertTrue(currentSection.contains("higher saved progress"))
        assertTrue(currentSection.contains("duplicate scanlation rows resolved consistently"))
        assertTrue(currentSection.contains("Confirmed versions"))
        assertTrue(currentSection.contains("only after identity confirmation succeeds"))
        assertTrue(currentSection.contains("Link group management"))
        assertTrue(currentSection.contains("undo a saved change"))
        assertTrue(publicReleaseNotes.contains("fewer chapters than the saved Local Tracking progress"))
        assertTrue(publicReleaseNotes.contains("fallback chapter numbers resolve duplicate scanlation rows consistently"))
        assertTrue(publicReleaseNotes.contains("supports adding, moving, merging, removing, ungrouping, and undoing changes"))
    }

    @Test
    fun `the v0_8_23 entry covers link-group cancellation and count-aware history summaries`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("Cancelling an ungroup keeps the existing group intact"))
        assertTrue(currentSection.contains("Action History"))
        assertTrue(currentSection.contains("correct number of versions added to or moved between groups"))
        assertTrue(publicReleaseNotes.contains("accurate counts for group additions and moves"))
        assertTrue(publicReleaseNotes.contains("Cancelling an ungroup keeps the existing group intact"))
    }

    @Test
    fun `the v0_8_23 entry maps direct Recommendation Settings access`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("Recommendation Settings access"))
        assertTrue(currentSection.contains("directly from the main Settings screen"))
    }

    @Test
    fun `the v0_8_23 entry describes product behavior and keeps pre-release status accurate`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("The v0.8.23 feature set, with follow-up fixes for updates, reading, and extension recovery"))
        assertFalse(currentSection.contains("release-readiness"))
        assertFalse(currentSection.contains("Release workflow"))
        assertTrue(publicReleaseNotes.contains("The next Komikku FC feature version is **v0.8.23.1**"))
        assertTrue(publicReleaseNotes.contains("The latest published snapshot remains **v0.8.22**"))
    }

    @Test
    fun `the v0_8_23 entry describes OCR and updater behavior in reader-facing language`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("search text in downloaded pages"))
        assertTrue(currentSection.contains("follow indexing progress"))
        assertTrue(currentSection.contains("resume interrupted app updates and extension operations"))
        assertTrue(currentSection.contains("clean up incomplete downloads or installs"))
        assertFalse(currentSection.contains("bounded work"))
        assertFalse(currentSection.contains("stale temporary files or worker state"))
        assertFalse(currentSection.contains("Cross-feature evidence"))
    }

    @Test
    fun `the v0_8_23 in-app extension recovery note uses reader-facing language`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("the app restores the previous version before retrying"))
        assertTrue(currentSection.contains("Fix one — extension recovery"))
        assertTrue(currentSection.contains("a second recovery attempt no longer interrupts an installation already in progress"))
        assertTrue(currentSection.contains("Cleanup problems with one extension do not block others"))
        assertTrue(currentSection.contains("files the app cannot identify are left untouched"))
        assertFalse(currentSection.contains("owned rollback files"))
        assertFalse(currentSection.contains("Unknown backup files"))
    }

    @Test
    fun `the public release notes describe OCR and extension recovery in user terms`() {
        assertTrue(publicReleaseNotes.contains("indexed pages can be removed by manga or chapter"))
        assertTrue(publicReleaseNotes.contains("the app restores the previous version before retrying"))
        assertTrue(publicReleaseNotes.contains("Prevent a second extension-recovery attempt from interrupting an install already in progress"))
        assertTrue(publicReleaseNotes.contains("files the app cannot identify are left untouched"))
        assertFalse(publicReleaseNotes.contains("scoped cleanup"))
        assertFalse(publicReleaseNotes.contains("owned rollback files"))
        assertFalse(publicReleaseNotes.contains("unknown backup files"))
    }

    @Test
    fun `the current release separates v0_8_23 additions from fix-one corrections`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.23.1" }.second
        val addedSection = currentSection.substringAfter("##### New").substringBefore("##### Improve")
        val fixSection = currentSection.substringAfter("##### Fix").substringBefore("## KMK-Recs v0.8.22")

        assertTrue(addedSection.contains("OCR search"))
        assertTrue(addedSection.contains("Updater recovery"))
        assertTrue(fixSection.contains("Fix one — app-update retry"))
        assertTrue(fixSection.contains("Fix one — alternate-source retry"))
        assertTrue(fixSection.contains("Fix one — reading timer"))
        assertTrue(fixSection.contains("Fix one — extension recovery"))
        assertFalse(currentSection.contains("v0.8.23-fix1"))
        assertTrue(publicReleaseNotes.contains("### Added in v0.8.23"))
        assertTrue(publicReleaseNotes.contains("### Fix one updates in v0.8.23.1"))
    }

    @Test
    fun `the v0_8_23 entry documents safe external tracker registration`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("External tracking"))
        assertTrue(currentSection.contains("prevent repeated add requests"))
        assertTrue(currentSection.contains("keep a saved link when follow-up synchronization fails"))
        assertTrue(currentSection.contains("warns you to check the tracker before trying again"))
        assertTrue(publicReleaseNotes.contains("prevents repeated submissions while linking"))
        assertTrue(publicReleaseNotes.contains("reports the partial result and keeps the link"))
        assertTrue(publicReleaseNotes.contains("warns you to check the tracker before trying again"))
    }

    @Test
    fun `the v0_8_23 entry documents reader-specific error messages`() {
        val currentSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")

        assertTrue(currentSection.contains("Reader errors"))
        assertTrue(currentSection.contains("reader-specific messages for network, file, and extension failures"))
        assertTrue(currentSection.contains("page-load message for other reader errors"))
        assertTrue(publicReleaseNotes.contains("Reader errors show reader-specific messages for network, file, and extension failures"))
        assertTrue(publicReleaseNotes.contains("page-load message for other reader errors"))
    }

    @Test
    fun `the v0_8_23 entry documents the Local Tracking settings shortcut before tracking exists`() {
        val currentInAppSection = KmkRecsReleaseNotes.MARKDOWN
            .substringAfter("## KMK-Recs v0.8.23.1")
            .substringBefore("## KMK-Recs v0.8.22")
        val currentPublicSection = publicReleaseNotes
            .substringAfter("## Included feature families")
            .substringBefore("## Guides")

        assertTrue(currentInAppSection.contains("Local Tracking row has a shortcut to Tracking settings before local tracking exists"))
        assertTrue(currentInAppSection.contains("available width without an unrelated settings category pane"))
        assertTrue(currentPublicSection.contains("Local Tracking row has a shortcut to Tracking settings before local tracking exists"))
        assertTrue(currentPublicSection.contains("available width without an unrelated settings category pane"))
    }

    @Test
    fun `the v0_8_22 entry documents automatic local tracking status`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.22" }.second
        assertTrue(currentSection.contains("Automatic local status"))
        assertTrue(currentSection.contains("Plan to read"))
        assertTrue(currentSection.contains("On hold and Dropped remain manual"))
        assertTrue(currentSection.contains("Local progress correction"))
        assertTrue(currentSection.contains("Confirmed local versions"))
        assertTrue(currentSection.contains("Progress from connected trackers"))
        assertTrue(currentSection.contains("Tracker refresh"))
        assertTrue(currentSection.contains("Different chapter numbering"))
        assertTrue(currentSection.contains("Alternate-source boundary"))
        assertTrue(currentSection.contains("selection-mode search long-presses"))
        assertTrue(currentSection.contains("Alternate-source library choice"))
        assertTrue(currentSection.contains("Tracking navigation"))
        assertTrue(currentSection.contains("Tracking settings layout"))
        assertTrue(currentSection.contains("off by default"))
        assertTrue(currentSection.contains("Always, Ask, or Never"))
    }

    @Test
    fun `the v0_8_22 entry documents clearer settings wording`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.22" }.second
        assertTrue(currentSection.contains("Clearer settings"))
        assertTrue(currentSection.contains("everyday language"))
    }

    @Test
    fun `recommendation settings use user goals instead of implementation jargon`() {
        val expectedPlainLabels = listOf(
            "Hide manga you already know",
            "Extra details per source",
            "Search more pages",
            "Extra results to check",
            "Results shown first per source",
            "Review same-manga matches",
        )
        expectedPlainLabels.forEach { label ->
            assertTrue(recommendationStrings.contains(">$label<"), "missing plain-language label: $label")
        }

        val removedJargon = listOf(
            "Enrichment cap (per source)",
            "Discovery effort",
            "Additional candidate budget",
            "Initial results per extension",
            "Propagate ratings to confirmed tracked versions",
            "Infer local tracking status",
        )
        removedJargon.forEach { label ->
            assertFalse(recommendationStrings.contains(">$label<"), "technical label still exposed: $label")
        }
    }

    @Test
    fun `the v0_8_22 entry documents the completion rating correction`() {
        val currentSection = sectionBodies().first { it.first == "v0.8.22" }.second
        assertTrue(currentSection.contains("Completion rating flow"))
        assertTrue(currentSection.contains("rereads"))
        assertTrue(currentSection.contains("For You chapter minimum"))
        assertTrue(currentSection.contains("Alternate-version selection"))
        assertTrue(currentSection.contains("Tracking preference boundaries"))
        assertTrue(currentSection.contains("rated manga can still start its own local tracking"))
        assertTrue(currentSection.contains("Restored progress"))
        assertTrue(currentSection.contains("Local source identity"))
        assertTrue(currentSection.contains("Chapter-number matching control"))
        assertTrue(currentSection.contains("Automatic primary version"))
    }

    @Test
    fun `the public v0_8_22 summary retains the current user-facing change families`() {
        val expectedPhrases = listOf(
            "Automatic local status",
            "primary version",
            "linked-version local tracking",
            "scrollable chapter picker",
            "Gapped and fractional sequences",
            "Restoring Local Tracking",
            "Completion rating",
            "Alternate-source reading",
            "built-in Local source",
        )
        expectedPhrases.forEach { phrase ->
            assertTrue(publicReleaseNotes.contains(phrase, ignoreCase = true), "missing public release-note coverage: $phrase")
        }
        assertTrue(publicReleaseNotes.contains("Komikku FC is an independent fork"))
        assertFalse(publicReleaseNotes.contains("Komikku KMK is an independent fork"))
    }

    @Test
    fun `the v0_8_21-fix2 entry does not restate v0_8_21's accepted-scope list`() {
        // A -fix release documents only what changed in that patch, not the whole parent
        // version's scope -- avoids stale duplication as later fixes are added.
        val fixSection = sectionBodies().first { it.first == "v0.8.21-fix2" }.second
        assertFalse(fixSection.contains("remains a separately contracted feature"))
    }
    // KMK <--
}
// KMK <--
