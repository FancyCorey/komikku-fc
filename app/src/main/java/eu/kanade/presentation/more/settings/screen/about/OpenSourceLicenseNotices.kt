package eu.kanade.presentation.more.settings.screen.about

import eu.kanade.tachiyomi.R

internal data class OpenSourceLicenseNoticeResource(
    val title: String,
    val resourceId: Int,
)

internal object OpenSourceLicenseNotices {
    private val resourcesByLibraryId = mapOf(
        "com.github.komikku-app:UniFile" to OpenSourceLicenseNoticeResource(
            "UniFile NOTICE",
            R.raw.unifile_notice,
        ),
        "commons-codec:commons-codec" to OpenSourceLicenseNoticeResource(
            "Apache Commons Codec NOTICE",
            R.raw.commons_codec_notice,
        ),
        "commons-logging:commons-logging" to OpenSourceLicenseNoticeResource(
            "Apache Commons Logging NOTICE",
            R.raw.commons_logging_notice,
        ),
        "org.apache.httpcomponents:httpclient" to OpenSourceLicenseNoticeResource(
            "Apache HttpClient NOTICE",
            R.raw.apache_httpclient_notice,
        ),
        "org.apache.httpcomponents:httpcore" to OpenSourceLicenseNoticeResource(
            "Apache HttpCore NOTICE",
            R.raw.apache_httpcore_notice,
        ),
    )

    fun forLibrary(libraryId: String): OpenSourceLicenseNoticeResource? = resourcesByLibraryId[libraryId]
}
