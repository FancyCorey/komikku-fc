package eu.kanade.presentation.more

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** v0.8.25: the sponsorship button names the original developer and keeps the original destination. */
class SponsorWordingSourceTest {

    private val more = File("src/main/java/eu/kanade/presentation/more/MoreScreen.kt").readText()
    private val strings = File("../i18n-kmk/src/commonMain/moko-resources/base/strings.xml").readText()
    private val constants = File("../core/common/src/main/kotlin/tachiyomi/core/common/Constants.kt").readText()

    @Test
    fun `the sponsor button says it supports the original developer`() {
        assertTrue(strings.contains("""name="sponsor_original_developer">Sponsor the original developer<"""))
        val sponsor = more.substringAfter("fun Sponsor()").substringBefore("@PreviewLightDark")
        assertEquals(2, Regex("KMR.strings.sponsor_original_developer").findAll(sponsor).count())
        assertFalse(sponsor.contains("sponsor_me"), "the old wording implied the fork's maintainer")
    }

    @Test
    fun `the sponsorship destination is unchanged`() {
        assertTrue(constants.contains("""const val SPONSOR = "https://github.com/sponsors/cuong-tran""""))
        assertTrue(more.contains("context.openInBrowser(Constants.SPONSOR)"))
    }
}
