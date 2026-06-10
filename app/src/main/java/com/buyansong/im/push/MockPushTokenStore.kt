package com.buyansong.im.push

import android.content.Context
import java.util.UUID

class MockPushTokenStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun getOrCreateToken(): String {
        val existing = preferences.getString(KEY_PUSH_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val token = UUID.randomUUID().toString()
        preferences.edit().putString(KEY_PUSH_TOKEN, token).apply()
        return token
    }

    fun clearToken() {
        preferences.edit().remove(KEY_PUSH_TOKEN).apply()
    }

    fun lastSeenPushId(userId: String): Long {
        return preferences.getLong(lastSeenKey(userId), 0L)
    }

    fun saveLastSeenPushId(userId: String, pushId: Long) {
        preferences.edit().putLong(lastSeenKey(userId), pushId).apply()
    }

    fun saveLastKnownUserId(userId: String) {
        preferences.edit().putString(KEY_LAST_KNOWN_USER_ID, userId).apply()
    }

    fun lastKnownUserId(): String? {
        return preferences.getString(KEY_LAST_KNOWN_USER_ID, null)?.takeIf { it.isNotBlank() }
    }

    fun clearLastKnownUserId(userId: String) {
        if (lastKnownUserId() == userId) {
            preferences.edit().remove(KEY_LAST_KNOWN_USER_ID).apply()
        }
    }

    private fun lastSeenKey(userId: String): String = "$KEY_LAST_SEEN_PREFIX$userId"

    private companion object {
        const val PREFERENCES_NAME = "mock_push"
        const val KEY_PUSH_TOKEN = "mock_push_token"
        const val KEY_LAST_KNOWN_USER_ID = "mock_push_last_known_user_id"
        const val KEY_LAST_SEEN_PREFIX = "push_last_seen_push_id_"
    }
}
