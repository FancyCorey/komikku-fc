package exh.recs.links

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class LinkGroupManagementScreenContractTest {
    private val screen = File("src/main/java/exh/recs/links/LinkGroupManagementScreen.kt").readText()

    @Test
    fun `add and create dialogs persist all checked candidates with identifying details`() {
        assertTrue(screen.contains("dialog.candidates.map { it.manga }.filter { (it.source to it.url) in selected }"))
        assertTrue(screen.contains("Checkbox(checked"))
        assertTrue(screen.contains("candidate.manga.asMangaCover()"))
        assertTrue(screen.contains("candidate.manga.title"))
        assertTrue(screen.contains("candidate.sourceName"))
    }

    @Test
    fun `member and group removal remain separate explicit actions`() {
        assertTrue(screen.contains("model.ungroupMember(member.link.source, member.link.url)"))
        assertTrue(screen.contains("model.ungroupGroup(dialog.group.groupId, onResult)"))
        assertTrue(screen.contains("GroupDialog.ConfirmUngroup"))
    }
}
