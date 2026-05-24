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
            .putString(KEY_PHONE, session.phone)
            .putString(KEY_NICKNAME, session.nickname)
            .putString(KEY_AVATAR_URL, session.avatarUrl)
            .putLong(KEY_AVATAR_UPDATED_AT, session.avatarUpdatedAt)
            .putLong(KEY_PROFILE_UPDATED_AT, session.profileUpdatedAt)
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
        val phone = preferences.getString(KEY_PHONE, null) ?: userId
        val nickname = preferences.getString(KEY_NICKNAME, null) ?: username
        val avatarUrl = preferences.getString(KEY_AVATAR_URL, null)
        val avatarUpdatedAt = preferences.getLong(KEY_AVATAR_UPDATED_AT, 0L)
        val profileUpdatedAt = preferences.getLong(KEY_PROFILE_UPDATED_AT, 0L)
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
            phone = phone,
            nickname = nickname,
            avatarUrl = avatarUrl,
            avatarUpdatedAt = avatarUpdatedAt,
            profileUpdatedAt = profileUpdatedAt,
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
        const val KEY_PHONE = "phone"
        const val KEY_NICKNAME = "nickname"
        const val KEY_AVATAR_URL = "avatar_url"
        const val KEY_AVATAR_UPDATED_AT = "avatar_updated_at"
        const val KEY_PROFILE_UPDATED_AT = "profile_updated_at"
        const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        const val KEY_LEGACY_TOKEN = "token"
    }
}
