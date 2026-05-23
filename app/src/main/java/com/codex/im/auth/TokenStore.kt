package com.codex.im.auth

import android.content.Context

interface TokenStore {
    fun save(session: AuthSession)

    fun currentSession(): AuthSession?

    fun clear()
}

class InMemoryTokenStore : TokenStore {
    private var session: AuthSession? = null

    override fun save(session: AuthSession) {
        this.session = session
    }

    override fun currentSession(): AuthSession? = session

    override fun clear() {
        session = null
    }
}

class SharedPreferencesTokenStore(context: Context) : TokenStore {
    private val preferences = context.getSharedPreferences("auth_token_store", Context.MODE_PRIVATE)

    override fun save(session: AuthSession) {
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putLong(KEY_ACCESS_EXPIRES_AT, session.accessExpiresAtMillis)
            .putLong(KEY_REFRESH_EXPIRES_AT, session.refreshExpiresAtMillis)
            .apply()
    }

    override fun currentSession(): AuthSession? {
        val accessToken = preferences.getString(KEY_ACCESS_TOKEN, null) ?: preferences.getString(KEY_LEGACY_TOKEN, null) ?: return null
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null) ?: run {
            clear()
            return null
        }
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        val username = preferences.getString(KEY_USERNAME, null) ?: return null
        if (!preferences.contains(KEY_ACCESS_EXPIRES_AT) || !preferences.contains(KEY_REFRESH_EXPIRES_AT)) {
            clear()
            return null
        }
        val accessExpiresAtMillis = preferences.getLong(KEY_ACCESS_EXPIRES_AT, Long.MIN_VALUE)
        val refreshExpiresAtMillis = preferences.getLong(KEY_REFRESH_EXPIRES_AT, Long.MIN_VALUE)
        return AuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            username = username,
            accessExpiresAtMillis = accessExpiresAtMillis,
            refreshExpiresAtMillis = refreshExpiresAtMillis
        )
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val KEY_LEGACY_TOKEN = "token"
    }
}
