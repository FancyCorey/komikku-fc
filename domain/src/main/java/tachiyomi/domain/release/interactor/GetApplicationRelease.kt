package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        val nextCheckTime = Instant.ofEpochMilli(lastChecked.get()).plus(2, ChronoUnit.DAYS)
        if (!arguments.forceCheck && now.isBefore(nextCheckTime)) {
            return Result.NoNewUpdate
        }

        // KMK -->
        val releases = service.releaseNotes(arguments)
            .filter {
                !it.preRelease && !it.draft &&
                    isNewVersion(
                        arguments.isPreview,
                        arguments.commitCount,
                        arguments.versionName,
                        it.version,
                    )
            }

        lastChecked.set(now.toEpochMilli())
        val latest = releases.getLatest() ?: return Result.NoNewUpdate
        // KMK <--

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            isPreview = arguments.isPreview,
            commitCount = arguments.commitCount,
            versionName = arguments.versionName,
            versionTag = latest.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(latest)
            else -> Result.NoNewUpdate
        }
    }

    // KMK -->
    suspend fun awaitReleaseNotes(arguments: Arguments): Result {
        val releases = service.releaseNotes(arguments)
            .filter { !it.preRelease && !it.draft }

        val latest = releases.getLatest() ?: return Result.NoNewUpdate
        return Result.NewUpdate(latest)
    }
    // KMK <--

    /**
     * [isPreview] is if current version is Preview (beta) build
     *
     * [versionTag] is the version of new release
     *
     * Release (stable) version will compare with current's [versionName] ("v0.1.2")
     *
     * Preview (beta) version will compare with current's [commitCount] ("r1234")
     */
    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        return if (isPreview) {
            val count = Regex("^r(\\d+)$").matchEntire(versionTag)?.groupValues?.get(1)?.toIntOrNull()
            count != null && count > commitCount
        } else {
            val newVersion = parseStableVersion(versionTag) ?: return false
            val oldVersion = parseStableVersion(versionName) ?: return false
            for (index in 0 until maxOf(newVersion.first.size, oldVersion.first.size)) {
                val comparison = (newVersion.first.getOrNull(index) ?: 0)
                    .compareTo(oldVersion.first.getOrNull(index) ?: 0)
                if (comparison != 0) return comparison > 0
            }
            newVersion.second > oldVersion.second
        }
    }

    private fun parseStableVersion(version: String): Pair<List<Int>, Int>? {
        val match = Regex("^v?(\\d+(?:\\.\\d+)*)(?:-fix(\\d+))?$").matchEntire(version) ?: return null
        val parts = match.groupValues[1].split(".").map { it.toIntOrNull() ?: return null }
        val fix = match.groupValues[2].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: return null } ?: 0
        return parts to fix
    }

    data class Arguments(
        val isFoss: Boolean,
        /** If current version is Preview (beta) build */
        val isPreview: Boolean,
        /** Commit count of current version */
        val commitCount: Int,
        /** Current version name, could be version tag (v0.1.2) or commit count (r1234) */
        val versionName: String,
        /** Repository name */
        val repository: String,
        /** Force check for new update */
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}

// KMK --.
internal fun List<Release>.getLatest(): Release? {
    return firstOrNull()
        ?.copy(
            info = joinToString("\r-----\r") {
                "## ${it.version}\r\r" +
                    it.info
            },
        )
}
// KMK <--
