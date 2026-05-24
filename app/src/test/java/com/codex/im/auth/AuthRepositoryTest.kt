package com.codex.im.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun loginStoresReturnedSession() = runTest {
        val api = FakeAuthApi(loginResult = AuthResult.Success(AuthSession("token-a", "13800138000", "13800138000", expiresAtMillis = 2_000L)))
        val tokenStore = InMemoryTokenStore()
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        val result = repository.login("13800138000", "password")

        assertTrue(result is AuthResult.Success)
        assertEquals("token-a", tokenStore.currentSession()?.token)
        assertEquals("13800138000", tokenStore.currentSession()?.userId)
    }

    @Test
    fun loginStoresReturnedSessionWhenNicknameIsNotPhoneNumber() = runTest {
        val session = AuthSession(
            accessToken = "token-a",
            refreshToken = "refresh-a",
            userId = "13900139000",
            username = "13900139000",
            phone = "13900139000",
            nickname = "Megumi",
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 2_000L
        )
        val api = FakeAuthApi(loginResult = AuthResult.Success(session))
        val tokenStore = InMemoryTokenStore()
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        val result = repository.login("13900139000", "password")

        assertTrue(result is AuthResult.Success)
        assertEquals("13900139000", tokenStore.currentSession()?.username)
        assertEquals("Megumi", tokenStore.currentSession()?.nickname)
    }

    @Test
    fun loginFailureDoesNotOverwriteExistingSession() = runTest {
        val api = FakeAuthApi(loginResult = AuthResult.Failure("bad credentials"))
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("old-token", "13700137000", "13700137000", expiresAtMillis = 2_000L))
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        val result = repository.login("13800138000", "wrong")

        assertTrue(result is AuthResult.Failure)
        assertEquals("old-token", tokenStore.currentSession()?.token)
    }

    @Test
    fun logoutClearsStoredSession() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("token-a", "13800138000", "13800138000", expiresAtMillis = 2_000L))
        val repository = AuthRepository(FakeAuthApi(), tokenStore, nowMillis = { 1_000L })

        repository.logout()

        assertNull(tokenStore.currentSession())
    }

    @Test
    fun currentSessionClearsLegacyNonPhoneSession() {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("mock-token-legacy", "legacy-user", "legacy-user", expiresAtMillis = 2_000L))
        val repository = AuthRepository(FakeAuthApi(), tokenStore, nowMillis = { 1_000L })

        assertNull(repository.currentSession())
        assertNull(tokenStore.currentSession())
    }

    @Test
    fun currentSessionClearsExpiredSession() {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("token-a", "13800138000", "13800138000", expiresAtMillis = 999L))
        val repository = AuthRepository(FakeAuthApi(), tokenStore, nowMillis = { 1_000L })

        assertNull(repository.currentSession())
        assertNull(tokenStore.currentSession())
    }

    @Test
    fun restoreSessionRefreshesExpiredAccessTokenWhenRefreshTokenIsValid() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 999L,
                refreshExpiresAtMillis = 2_000L
            )
        )
        val refreshed = AuthSession(
            accessToken = "fresh-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 2_000L
        )
        val repository = AuthRepository(FakeAuthApi(refreshResult = AuthResult.Success(refreshed)), tokenStore, nowMillis = { 1_000L })

        assertEquals(refreshed, repository.restoreSession())
        assertEquals("fresh-access", tokenStore.currentSession()?.accessToken)
    }

    @Test
    fun restoreSessionClearsWhenRefreshFails() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 999L,
                refreshExpiresAtMillis = 2_000L
            )
        )
        val repository = AuthRepository(FakeAuthApi(refreshResult = AuthResult.Failure("Refresh token expired or revoked")), tokenStore, nowMillis = { 1_000L })

        assertNull(repository.restoreSession())
        assertNull(tokenStore.currentSession())
    }

    @Test
    fun logoutRevokesAndClearsStoredSession() = runTest {
        val api = FakeAuthApi()
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "access-a",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 2_000L,
                refreshExpiresAtMillis = 3_000L
            )
        )
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        repository.logout()

        assertEquals("refresh-a", api.loggedOutRefreshToken)
        assertNull(tokenStore.currentSession())
    }

    private class FakeAuthApi(
        private val loginResult: AuthResult = AuthResult.Success(AuthSession("token", "13800138000", "13800138000", expiresAtMillis = 2_000L)),
        private val registerResult: AuthResult = AuthResult.Success(AuthSession("token", "13800138000", "13800138000", expiresAtMillis = 2_000L)),
        private val refreshResult: AuthResult = AuthResult.Success(AuthSession("token", "13800138000", "13800138000", expiresAtMillis = 2_000L))
    ) : AuthApi {
        var loggedOutRefreshToken: String? = null

        override suspend fun login(phone: String, password: String): AuthResult = loginResult

        override suspend fun register(phone: String, password: String): AuthResult = registerResult

        override suspend fun refresh(refreshToken: String): AuthResult = refreshResult

        override suspend fun logout(refreshToken: String): AuthResult {
            loggedOutRefreshToken = refreshToken
            return AuthResult.Success(AuthSession("token", "13800138000", "13800138000", expiresAtMillis = 2_000L))
        }
    }
}
