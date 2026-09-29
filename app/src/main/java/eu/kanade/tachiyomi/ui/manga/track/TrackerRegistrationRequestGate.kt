package eu.kanade.tachiyomi.ui.manga.track

/** Prevents duplicate registration requests while a tracker or its post-bind work is pending. */
internal class TrackerRegistrationRequestGate {
    private var inProgress = false

    @Synchronized
    fun tryBegin(): Boolean {
        if (inProgress) return false
        inProgress = true
        return true
    }

    @Synchronized
    fun finish() {
        inProgress = false
    }
}
