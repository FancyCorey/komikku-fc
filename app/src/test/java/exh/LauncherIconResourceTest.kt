package exh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class LauncherIconResourceTest {

    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    @Test
    fun `adaptive launcher uses the branded artwork in color and monochrome`() {
        val adaptiveIcon = resource("mipmap-anydpi-v26/ic_launcher_fc.xml").documentElement

        assertEquals(
            "@drawable/ic_launcher_fc_background",
            (adaptiveIcon.getElementsByTagName("background").item(0) as Element)
                .androidAttribute("drawable"),
        )
        assertEquals(
            "@drawable/ic_launcher_fc_adaptive_foreground_content",
            (adaptiveIcon.getElementsByTagName("foreground").item(0) as Element)
                .androidAttribute("drawable"),
        )
        assertEquals(
            "@drawable/ic_launcher_fc_monochrome_vector",
            (adaptiveIcon.getElementsByTagName("monochrome").item(0) as Element)
                .androidAttribute("drawable"),
        )

        assertTrue(File("src/main/res/drawable-nodpi/ic_launcher_fc_full.png").isFile)
        assertTrue(File("src/main/res/drawable/ic_launcher_fc_monochrome_vector.xml").isFile)

        val foreground = resource("drawable/ic_launcher_fc_adaptive_foreground_content.xml").documentElement
        assertEquals("@drawable/ic_launcher_fc_full", foreground.androidAttribute("drawable"))
        assertEquals("18%", foreground.androidAttribute("inset"))

        val application = resource("../AndroidManifest.xml")
            .getElementsByTagName("application")
            .item(0) as Element
        assertEquals("@mipmap/ic_launcher_fc", application.androidAttribute("icon"))
        assertEquals("@mipmap/ic_launcher_fc", application.androidAttribute("roundIcon"))
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(androidNamespace, name)

    private fun resource(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(File("src/main/res/$path"))
}
