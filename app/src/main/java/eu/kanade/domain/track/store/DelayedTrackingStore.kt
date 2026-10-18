package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class DelayedTrackingStore(context: Context) {

    /**
     * Preference file where queued tracking updates are stored.
     */
    private val preferences = context.getSharedPreferences("tracking_queue", Context.MODE_PRIVATE)

    fun add(trackId: Long, lastChapterRead: Double) {
        synchronized(preferences) {
            val key = trackId.toString()
            val previousLastChapterRead = preferences.getFloat(key, 0f)
            if (lastChapterRead > previousLastChapterRead) {
                logcat(LogPriority.DEBUG) { "Tracking item queued" }
                preferences.edit {
                    putFloat(key, lastChapterRead.toFloat())
                }
            }
        }
    }

    fun remove(trackId: Long) {
        synchronized(preferences) {
            preferences.edit {
                remove(trackId.toString())
            }
        }
    }

    fun getTarget(trackId: Long): Double? = synchronized(preferences) {
        val key = trackId.toString()
        if (preferences.contains(key)) preferences.getFloat(key, 0f).toDouble() else null
    }

    /** Remove a queued target only when the persisted progress has satisfied it. */
    fun removeIfAtMost(trackId: Long, satisfiedThrough: Double) {
        synchronized(preferences) {
            val key = trackId.toString()
            if (preferences.contains(key) && preferences.getFloat(key, 0f).toDouble() <= satisfiedThrough) {
                preferences.edit {
                    remove(key)
                }
            }
        }
    }

    fun getItems(): List<DelayedTrackingItem> {
        return synchronized(preferences) {
            preferences.all.mapNotNull {
                DelayedTrackingItem(
                    trackId = it.key.toLong(),
                    lastChapterRead = it.value.toString().toFloat(),
                )
            }
        }
    }

    data class DelayedTrackingItem(
        val trackId: Long,
        val lastChapterRead: Float,
    )
}
