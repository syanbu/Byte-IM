package com.codex.im.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val sessionRefreshMutex = Mutex()
    private val mutableSessionState = MutableStateFlow<AuthSession?>(null)
    val sessionState: StateFlow<AuthSession?> = mutableSessionState.asStateFlow()

    init {
        mutableSessionState.value = readCurrentSession()
    }

    suspend fun login(phone: String, password: String): AuthResult {
        return persistOnSuccess(authApi.login(phone, password))
    }

    suspend fun register(phone: String, password: String): AuthResult {
        return persistOnSuccess(authApi.register(phone, password))
    }

    fun currentSession(): AuthSession? {
        val session = readCurrentSession()
        mutableSessionState.value = session
        return session
    }

    fun hasStoredSession(): Boolean = tokenStore.currentSession() != null

    suspend fun restoreSession(): AuthSession? {
        val session = readLocalSession()
        mutableSessionState.value = session
        return session
    }

    suspend fun ensureValidSession(): AuthSession? {
        return sessionRefreshMutex.withLock {
            val session = readLocalSession() ?: return@withLock null
            if (session.accessExpiresAtMillis > nowMillis()) {
                mutableSessionState.value = session
                return@withLock session
            }
            when (val result = authApi.refresh(session.refreshToken)) {
                is AuthResult.Success -> {
                    if (!result.session.isRestorable(nowMillis())) {
                        clearStoredSession()
                        null
                    } else {
                        saveSession(result.session)
                        result.session
                    }
                }
                is AuthResult.Failure -> {
                    if (result.shouldClearStoredSession()) {
                        clearStoredSession()
                    } else {
                        mutableSessionState.value = session
                    }
                    null
                }
                AuthResult.LoggedOut -> {
                    clearStoredSession()
                    null
                }
            }
        }
    }

    suspend fun logout() {
        val refreshToken = tokenStore.currentSession()?.refreshToken
        clearStoredSession()
        if (!refreshToken.isNullOrBlank()) {
            authApi.logout(refreshToken)
        }
    }

    private fun persistOnSuccess(result: AuthResult): AuthResult {
        if (result is AuthResult.Success) {
            if (!result.session.isRestorable(nowMillis())) {
                return AuthResult.Failure("Invalid authentication response")
            }
            saveSession(result.session)
        }
        return result
    }

    private fun readCurrentSession(): AuthSession? {
        val session = readLocalSession() ?: return null
        if (session.accessExpiresAtMillis <= nowMillis()) {
            return null
        }
        return session
    }

    private fun readLocalSession(): AuthSession? {
        val session = tokenStore.currentSession() ?: return null
        if (!session.hasRestorableIdentity() || session.refreshExpiresAtMillis <= nowMillis()) {
            clearStoredSession()
            return null
        }
        return session
    }

    private fun saveSession(session: AuthSession) {
        tokenStore.save(session)
        mutableSessionState.value = session
    }

    private fun clearStoredSession() {
        tokenStore.clear()
        mutableSessionState.value = null
    }

    private fun AuthSession.isRestorable(now: Long): Boolean {
        return hasRestorableIdentity() && accessExpiresAtMillis > now && refreshExpiresAtMillis > now
    }

    private fun AuthSession.hasRestorableIdentity(): Boolean {
        return userId.matches(MAINLAND_CHINA_PHONE) && username.matches(MAINLAND_CHINA_PHONE) && refreshToken.isNotBlank()
    }

    private fun AuthResult.Failure.shouldClearStoredSession(): Boolean {
        return when (kind) {
            AuthFailureKind.SESSION_EXPIRED,
            AuthFailureKind.INVALID_CREDENTIALS -> true
            AuthFailureKind.NETWORK,
            AuthFailureKind.SERVER -> false
            AuthFailureKind.UNKNOWN -> {
                val normalized = message.lowercase()
                normalized.contains("refresh token expired") ||
                    normalized.contains("refresh token revoked") ||
                    normalized.contains("expired or revoked") ||
                    normalized.contains("session expired") ||
                    normalized.contains("login expired") ||
                    normalized.contains("token revoked")
            }
        }
    }

    private companion object {
        val MAINLAND_CHINA_PHONE = Regex("^1[3-9]\\d{9}$")
    }
}
