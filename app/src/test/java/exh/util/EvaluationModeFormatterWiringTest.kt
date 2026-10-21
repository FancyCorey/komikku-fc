package exh.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class EvaluationModeFormatterWiringTest {

    @Test
    fun `for you source actions hide confirmation labels and suggested export filenames`() {
        val source = File("src/main/java/exh/recs/BrowsePersonalRecommendationsTab.kt").readText()
        assertTrue(source.contains("val hideSourceNamesForExport = rememberEvaluationModeEnabled()"))
        assertTrue(source.contains("if (hideSourceNamesForExport) \"kmk_recommendations.json\""))
        assertTrue(source.contains("val displaySourceName = if (rememberEvaluationModeEnabled()) EvaluationModeFormatter.sourceLabel(source.id) else source.name"))
        assertTrue(source.contains("rec_source_failure_stopped, displaySourceName"))
        assertTrue(source.contains("newIntent(context, siteUrl, source.id, displaySourceName)"))
    }

    @Test
    fun `linked version management renders opaque source ids when name hiding is enabled`() {
        val group = File("src/main/java/exh/recs/links/LinkGroupManagementScreen.kt").readText()
        assertTrue(group.contains("EvaluationModeFormatter.sourceLabel(candidate.manga.source) else candidate.sourceName"))
        assertTrue(group.contains("EvaluationModeFormatter.sourceLabel(member.link.source) else member.sourceName"))
        val list = File("src/main/java/exh/recs/links/LinkedVersionListScreen.kt").readText()
        assertTrue(list.contains("val hideSourceNames = exh.util.rememberEvaluationModeEnabled()"))
        assertTrue(list.contains("if (hideSourceNames) {"))
        assertTrue(list.contains("EvaluationModeFormatter.sourceLabel(row.key.source)"))
        assertTrue(list.contains("row.sourceName"))
    }

    @Test
    fun `skipped source diagnostics restart with empty display state when name hiding changes`() {
        val source = File("src/main/java/exh/recs/settings/RecommendationDiagnosticsSection.kt").readText()
        assertTrue(source.contains("rememberEvaluationModeEnabled()"))
        assertTrue(source.contains("key(hideSourceNames)"))
        assertTrue(source.contains("produceState(initialValue = emptyList<String>())"))
        assertTrue(source.contains("val sourceId = fit.sourceId ?: return@mapNotNull null"))
        assertTrue(source.contains("if (hideSourceNames) exh.util.EvaluationModeFormatter.sourceLabel(sourceId) else fit.sourceName"))
    }

    @Test
    fun `application startup installs the Android label resolver before dependency setup`() {
        val source = File("src/main/java/eu/kanade/tachiyomi/App.kt").readText()
        val superCall = source.indexOf("super<Application>.onCreate()")
        val resolverInstall = source.indexOf("EvaluationModeFormatter.initialize(this)")
        val dependencySetup = source.indexOf("patchInjekt()")

        assertTrue(superCall >= 0)
        assertTrue(resolverInstall > superCall)
        assertTrue(dependencySetup > resolverInstall)
    }

    @Test
    fun `generic label templates have typed placeholders and only the formatter resolves them`() {
        val resources = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resources)
        val strings = document.getElementsByTagName("string")
        val values = buildMap {
            repeat(strings.length) { index ->
                val node = strings.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }

        assertEquals("Source %1\$s", values["evaluation_mode_source_label"])
        assertEquals("Repo %1\$d", values["evaluation_mode_repo_label"])
        assertEquals("Like Tag %1\$d", values["evaluation_mode_liked_tag_label"])
        assertEquals("Dislike Tag %1\$d", values["evaluation_mode_blocked_tag_label"])

        val directReferences = File("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (GENERIC_LABEL_RESOURCE_NAMES.any(line::contains)) "${file.invariantSeparatorsPath}:${index + 1}" else null
                }
            }
            .toList()

        assertEquals(4, directReferences.size)
        assertTrue(directReferences.all { it.contains("exh/util/EvaluationModeFormatter.kt") })
    }

    private companion object {
        val GENERIC_LABEL_RESOURCE_NAMES = listOf(
            "evaluation_mode_source_label",
            "evaluation_mode_repo_label",
            "evaluation_mode_liked_tag_label",
            "evaluation_mode_blocked_tag_label",
        )
    }
}
