package exh.recs

import java.security.MessageDigest

// KMK -->
/** Stable, non-display digest for the hidden manga keys that affect For You visibility. */
internal object RecommendationHiddenTitleFingerprint {
    fun digest(keys: Set<SeenMangaKey>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        keys.sortedWith(compareBy<SeenMangaKey> { it.sourceId }.thenBy { it.url }).forEach {
            digest.update("${it.sourceId}|${it.url}".toByteArray())
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
// KMK <--
