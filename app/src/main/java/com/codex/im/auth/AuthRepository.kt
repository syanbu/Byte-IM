package com.codex.im.auth

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun login(phone: String, password: String): AuthResult {
        return persistOnSuccess(authApi.login(phone, password))
    }

    suspend fun register(phone: String, password: String): AuthResult {
        return persistOnSuccess(authApi.register(phone, password))
    }

    fun currentSession(): AuthSession? {
        val session = tokenStore.currentSession() ?: return null
        if (!session.hasRestorableIdentity() || session.refreshExpiresAtMillis <= nowMillis()) {
            tokenStore.clear()
            return null
        }
        if (session.accessExpiresAtMillis <= nowMillis()) {
            return null
        }
        return session
    }

    fun hasStoredSession(): Boolean = tokenStore.currentSession() != null

    suspend fun restoreSession(): AuthSession? {
        val session = tokenStore.currentSession() ?: return null
        if (!session.hasRestorableIdentity() || session.refreshExpiresAtMillis <= nowMillis()) {
            tokenStore.clear()
            return null
        }
        if (session.accessExpiresAtMillis > nowMillis()) {
            return session
        }
        return when (val result = authApi.refresh(session.refreshToken)) {
            is AuthResult.Success -> {
                if (!result.session.isRestorable(nowMillis())) {
                    tokenStore.clear()
                    null
                } else {
                    tokenStore.save(result.session)
                    result.session
                }
            }
            is AuthResult.Failure,
            AuthResult.LoggedOut -> {
                tokenStore.clear()
                null
            }
        }
    }

    suspend fun logout() {
        val refreshToken = tokenStore.currentSession()?.refreshToken
        tokenStore.clear()
        if (!refreshToken.isNullOrBlank()) {
            authApi.logout(refreshToken)
        }
    }

    private fun persistOnSuccess(result: AuthResult): AuthResult {
        if (result is AuthResult.Success) {
            if (!result.session.isRestorable(nowMillis())) {
                return AuthResult.Failure("Invalid authentication response")
            }
            tokenStore.save(result.session)
        }
        return result
    }

    private fun AuthSession.isRestorable(now: Long): Boolean {
        return hasRestorableIdentity() && accessExpiresAtMillis > now && refreshExpiresAtMillis > now
    }

    private fun AuthSession.hasRestorableIdentity(): Boolean {
        return userId.matches(MAINLAND_CHINA_PHONE) && username.matches(MAINLAND_CHINA_PHONE) && refreshToken.isNotBlank()
    }

    private companion object {
        val MAINLAND_CHINA_PHONE = Regex("^1[3-9]\\d{9}$")
    }
}
