// KMK --> v0.8.24: MangaBaka tracker ported from Mihon (mihonapp/mihon, Apache-2.0) and adapted to Komikku.
package eu.kanade.tachiyomi.data.track.mangabaka.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MangaBakaOAuth(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("expires_at")
    val expiresAt: Long,
) {
    // Refresh a minute early so a request does not race the expiry.
    fun isExpired(): Boolean = System.currentTimeMillis() / 1000 + 60 > expiresAt
}
// KMK <--
