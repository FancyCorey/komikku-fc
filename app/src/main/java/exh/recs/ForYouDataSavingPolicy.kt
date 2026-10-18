package exh.recs

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// KMK -->
/**
 * v0.8.24 For You data saving: how aggressively a refresh spends network.
 *
 * [AUTO] saves data only when the active connection is metered or the OS reports a slow downstream link; [ALWAYS]
 * and [NEVER] force it. Saving trims the optional work around the per-source page-one search (the extra discovery
 * page and the Latest-lane exploration), tries fewer query strategies per source, and stops earlier once enough
 * rows are useful. Filtering, scoring and the cache are unchanged, so results stay correct, only less exploratory.
 */
enum class ForYouDataSaving(val storedValue: String) {
    AUTO("auto"),
    ALWAYS("on"),
    NEVER("off"),
    ;

    companion object {
        val DEFAULT = AUTO

        fun resolve(storedValue: String): ForYouDataSaving =
            entries.firstOrNull { it.storedValue == storedValue } ?: DEFAULT
    }
}

internal object ForYouDataSavingPolicy {

    /** Provisional: a downstream estimate below this many kbit/s (about 2 Mbit/s) counts as a slow connection. */
    const val SLOW_DOWNSTREAM_KBPS = 2_000

    /** Provisional: with saving on, stop once this many source rows are useful (the normal limit is 20). */
    const val SAVING_MAX_VISIBLE_SOURCE_ROWS = 10

    /**
     * Provisional: with saving on, try at most this many query strategies per source. A source whose first
     * strategy returns too few results is normally retried with the next one (up to three); the 2026-10-17
     * device trace showed those page-one retries are most of a refresh's requests (39 of 44).
     */
    const val SAVING_MAX_PLANS_PER_SOURCE = 2

    fun isActive(mode: ForYouDataSaving, networkConstrained: Boolean): Boolean = when (mode) {
        ForYouDataSaving.ALWAYS -> true
        ForYouDataSaving.NEVER -> false
        ForYouDataSaving.AUTO -> networkConstrained
    }

    /** Metered, or a known (non-zero) downstream estimate below [SLOW_DOWNSTREAM_KBPS]. */
    fun isConstrained(metered: Boolean, downstreamKbps: Int): Boolean =
        metered || (downstreamKbps in 1 until SLOW_DOWNSTREAM_KBPS)

    /** Reads the active network once; any failure or missing information is treated as "not constrained". */
    fun isConstrained(context: Context): Boolean = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        if (capabilities == null) {
            false
        } else {
            isConstrained(
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                downstreamKbps = capabilities.linkDownstreamBandwidthKbps,
            )
        }
    } catch (_: Exception) {
        false
    }
}
// KMK <--
