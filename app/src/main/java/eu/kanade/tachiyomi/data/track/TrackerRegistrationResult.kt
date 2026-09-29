package eu.kanade.tachiyomi.data.track

/** Describes whether a remote tracker binding also became locally available in the app. */
enum class TrackerRegistrationResult {
    Linked,
    RemoteAcceptedLocalSaveFailed,
}
