package eu.kanade.tachiyomi.data.updater

import android.content.Context

internal class AppUpdateAttemptStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun begin(attemptId: String) = synchronized(lock) {
        saveNewAttempt(attemptId)
    }

    fun beginAndRun(attemptId: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (!saveNewAttempt(attemptId)) return@synchronized false
        try {
            action()
            true
        } catch (error: Exception) {
            clear(attemptId)
            throw error
        }
    }

    private fun saveNewAttempt(attemptId: String): Boolean =
        preferences.edit()
            .putString(KEY_ACTIVE_ATTEMPT, attemptId)
            .remove(KEY_COMPLETED_ATTEMPT)
            .remove(KEY_COMPLETED_SESSION)
            .commit()

    fun adoptIfEmpty(attemptId: String): Boolean = synchronized(lock) {
        val activeAttempt = preferences.getString(KEY_ACTIVE_ATTEMPT, null)
        if (activeAttempt != null) return@synchronized activeAttempt == attemptId
        preferences.edit().putString(KEY_ACTIVE_ATTEMPT, attemptId).commit()
    }

    fun isCurrent(attemptId: String): Boolean = synchronized(lock) {
        preferences.getString(KEY_ACTIVE_ATTEMPT, null) == attemptId
    }

    fun runIfCurrent(attemptId: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (preferences.getString(KEY_ACTIVE_ATTEMPT, null) != attemptId) return@synchronized false
        action()
        true
    }

    /** Run a matching callback once, while excluding a concurrent new attempt. */
    fun handleCallback(attemptId: String, sessionId: Int, terminal: Boolean, action: () -> Unit): Boolean =
        synchronized(lock) {
            if (preferences.getString(KEY_ACTIVE_ATTEMPT, null) != attemptId) return@synchronized false
            if (terminal) {
                val alreadyCompleted =
                    preferences.getString(KEY_COMPLETED_ATTEMPT, null) == attemptId &&
                        preferences.getInt(KEY_COMPLETED_SESSION, NO_SESSION) == sessionId
                if (alreadyCompleted) return@synchronized false
                if (!preferences.edit()
                        .putString(KEY_COMPLETED_ATTEMPT, attemptId)
                        .putInt(KEY_COMPLETED_SESSION, sessionId)
                        .commit()
                ) {
                    return@synchronized false
                }
            }
            action()
            true
        }

    fun clear(attemptId: String? = null) = synchronized(lock) {
        if (attemptId != null && preferences.getString(KEY_ACTIVE_ATTEMPT, null) != attemptId) {
            return@synchronized
        }
        preferences.edit()
            .remove(KEY_ACTIVE_ATTEMPT)
            .remove(KEY_COMPLETED_ATTEMPT)
            .remove(KEY_COMPLETED_SESSION)
            .commit()
    }

    fun clearAndRun(action: () -> Unit) = synchronized(lock) {
        preferences.edit()
            .remove(KEY_ACTIVE_ATTEMPT)
            .remove(KEY_COMPLETED_ATTEMPT)
            .remove(KEY_COMPLETED_SESSION)
            .commit()
        action()
    }

    fun runIfCurrentAndRun(attemptId: String, action: () -> Unit): Boolean = synchronized(lock) {
        if (preferences.getString(KEY_ACTIVE_ATTEMPT, null) != attemptId) return@synchronized false
        action()
        true
    }

    private companion object {
        const val PREFERENCES_NAME = "app_update_attempt"
        const val KEY_ACTIVE_ATTEMPT = "active_attempt"
        const val KEY_COMPLETED_ATTEMPT = "completed_attempt"
        const val KEY_COMPLETED_SESSION = "completed_session"
        const val NO_SESSION = -1
        val lock = Any()
    }
}
