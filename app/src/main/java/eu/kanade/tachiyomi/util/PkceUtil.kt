package eu.kanade.tachiyomi.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object PkceUtil {

    fun generateCodeVerifier(): String {
        val codeVerifier = ByteArray(50)
        SecureRandom().nextBytes(codeVerifier)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(codeVerifier)
    }

    // KMK --> v0.8.24: S256 challenge for trackers that require conformant PKCE (MangaBaka), from Mihon
    fun generateS256Codes(): PkceCodes {
        val codeVerifier = generateCodeVerifier()
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        val codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)

        return PkceCodes(codeVerifier = codeVerifier, codeChallenge = codeChallenge)
    }
    // KMK <--
}

// KMK -->
data class PkceCodes(
    val codeVerifier: String,
    val codeChallenge: String,
)
// KMK <--
