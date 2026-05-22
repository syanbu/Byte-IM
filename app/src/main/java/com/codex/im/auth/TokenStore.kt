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
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .apply()
    }

    override fun currentSession(): AuthSession? {
        val token = preferences.getString(KEY_TOKEN, null) ?: return null
        val userId = preferences.getString(KEY_USER_ID, null) ?: return null
        val username = preferences.getString(KEY_USERNAME, null) ?: return null
        return AuthSession(token = token, userId = userId, username = username)
    }

    override fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
    }
}
