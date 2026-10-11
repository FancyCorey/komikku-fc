package eu.kanade.presentation.more.settings.screen.about

import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.ui.compose.util.htmlReadyLicenseContent
import java.io.Serializable

internal data class OpenSourceLicenseEntry(
    val name: String,
    val content: String,
    val url: String?,
) : Serializable

internal data class OpenSourceLicenseNotice(
    val title: String,
    val content: String,
) : Serializable

internal fun Iterable<License>.toOpenSourceLicenseEntries(): List<OpenSourceLicenseEntry> =
    mapNotNull { license ->
        val url = license.url.orEmpty().trim().takeIf {
            it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
        }
        val content = license.htmlReadyLicenseContent.orEmpty().trim()
        val name = license.name.orEmpty().trim().ifBlank { license.spdxId.orEmpty().trim() }
        if (content.isBlank() && url == null) {
            null
        } else {
            OpenSourceLicenseEntry(name = name, content = content, url = url)
        }
    }
