package com.codex.im.auth

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore
) {
    suspend fun login(username: String, password: String): AuthResult {
        return persistOnSuccess(authApi.login(username, password))
    }

    suspend fun register(username: String, password: String): AuthResult {
        return persistOnSuccess(authApi.register(username, password))
    }

    fun currentSession(): AuthSession? = tokenStore.currentSession()

    fun logout() {
        tokenStore.clear()
    }

    private fun persistOnSuccess(result: AuthResult): AuthResult {
        if (result is AuthResult.Success) {
            tokenStore.save(result.session)
        }
        return result
    }
}
