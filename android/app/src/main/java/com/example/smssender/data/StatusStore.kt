package com.example.smssender.data

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class ForwardStatus(
    val successful: Boolean?,
    val message: String,
    val timestamp: Long,
)

class StatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun update(successful: Boolean, message: String) {
        preferences.edit()
            .putString(KEY_MESSAGE, message.take(300))
            .putBoolean(KEY_SUCCESS, successful)
            .putBoolean(KEY_HAS_RESULT, true)
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
    }

    fun current() = ForwardStatus(
        successful = if (preferences.getBoolean(KEY_HAS_RESULT, false)) {
            preferences.getBoolean(KEY_SUCCESS, false)
        } else {
            null
        },
        message = preferences.getString(KEY_MESSAGE, "尚无转发记录").orEmpty(),
        timestamp = preferences.getLong(KEY_TIME, 0),
    )

    fun observe(): Flow<ForwardStatus> = callbackFlow {
        trySend(current())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(current())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        const val PREFS = "forward_status"
        const val KEY_MESSAGE = "message"
        const val KEY_SUCCESS = "success"
        const val KEY_HAS_RESULT = "has_result"
        const val KEY_TIME = "time"
    }
}
