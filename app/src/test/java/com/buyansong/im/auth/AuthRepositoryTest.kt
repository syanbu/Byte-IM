package com.buyansong.im.auth

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
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
    fun restoreSessionReturnsLocalSessionWhenAccessTokenIsExpiredButRefreshTokenIsStillValid() = runTest {
        val tokenStore = InMemoryTokenStore()
        val storedSession = AuthSession(
            accessToken = "fresh-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 999L,
            refreshExpiresAtMillis = 2_000L
        )
        tokenStore.save(storedSession)
        val api = FakeAuthApi(refreshResult = AuthResult.Success(storedSession))
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        assertEquals(storedSession, repository.restoreSession())
        assertEquals("fresh-access", tokenStore.currentSession()?.accessToken)
        assertEquals(0, api.refreshCallCount)
    }

    @Test
    fun restoreSessionClearsWhenRefreshTokenHasExpiredLocally() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 999L,
                refreshExpiresAtMillis = 999L
            )
        )
        val repository = AuthRepository(FakeAuthApi(), tokenStore, nowMillis = { 1_000L })

        assertNull(repository.restoreSession())
        assertNull(tokenStore.currentSession())
    }

    @Test
    fun restoreSessionKeepsLocalSessionWhenRefreshFailsDueToNetwork() = runTest {
        val storedSession = AuthSession(
            accessToken = "expired-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 999L,
            refreshExpiresAtMillis = 2_000L
        )
        val tokenStore = InMemoryTokenStore().apply {
            save(storedSession)
        }
        val repository = AuthRepository(
            FakeAuthApi(refreshResult = AuthResult.Failure("网络异常")),
            tokenStore,
            nowMillis = { 1_000L }
        )

        assertEquals(storedSession, repository.restoreSession())
        assertEquals(storedSession, tokenStore.currentSession())
    }

    @Test
    fun ensureValidSessionReturnsCurrentSessionWhenAccessTokenIsStillValid() = runTest {
        val session = AuthSession(
            accessToken = "access-a",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 3_000L
        )
        val repository = AuthRepository(FakeAuthApi(), InMemoryTokenStore().apply { save(session) }, nowMillis = { 1_000L })

        assertEquals(session, repository.ensureValidSession())
    }

    @Test
    fun ensureValidSessionRefreshesExpiredAccessTokenWhenRefreshTokenIsValid() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 999L,
                refreshExpiresAtMillis = 3_000L
            )
        )
        val refreshed = AuthSession(
            accessToken = "fresh-access",
            refreshToken = "refresh-b",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 3_000L
        )
        val repository = AuthRepository(FakeAuthApi(refreshResult = AuthResult.Success(refreshed)), tokenStore, nowMillis = { 1_000L })

        assertEquals(refreshed, repository.ensureValidSession())
        assertEquals("fresh-access", tokenStore.currentSession()?.accessToken)
        assertEquals("refresh-b", tokenStore.currentSession()?.refreshToken)
    }

    @Test
    fun ensureValidSessionClearsStoredSessionWhenRefreshFails() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(
            AuthSession(
                accessToken = "expired-access",
                refreshToken = "refresh-a",
                userId = "13800138000",
                username = "13800138000",
                accessExpiresAtMillis = 999L,
                refreshExpiresAtMillis = 3_000L
            )
        )
        val repository = AuthRepository(
            FakeAuthApi(refreshResult = AuthResult.Failure("Refresh token expired or revoked")),
            tokenStore,
            nowMillis = { 1_000L }
        )

        assertNull(repository.ensureValidSession())
        assertNull(tokenStore.currentSession())
    }

    @Test
    fun ensureValidSessionKeepsStoredSessionWhenRefreshFailsDueToNetwork() = runTest {
        val storedSession = AuthSession(
            accessToken = "expired-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 999L,
            refreshExpiresAtMillis = 3_000L
        )
        val tokenStore = InMemoryTokenStore().apply {
            save(storedSession)
        }
        val repository = AuthRepository(
            FakeAuthApi(refreshResult = AuthResult.Failure("网络异常")),
            tokenStore,
            nowMillis = { 1_000L }
        )

        assertNull(repository.ensureValidSession())
        assertEquals(storedSession, tokenStore.currentSession())
        assertEquals(storedSession, repository.sessionState.value)
    }

    @Test
    fun concurrentEnsureValidSessionRefreshesOnlyOnceWhenRefreshTokenRotates() = runTest {
        val expiredSession = AuthSession(
            accessToken = "expired-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 999L,
            refreshExpiresAtMillis = 3_000L
        )
        val refreshedSession = AuthSession(
            accessToken = "fresh-access",
            refreshToken = "refresh-b",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 3_000L
        )
        val tokenStore = InMemoryTokenStore().apply { save(expiredSession) }
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        val api = object : AuthApi {
            var refreshCallCount = 0

            override suspend fun login(phone: String, password: String): AuthResult = AuthResult.Failure("unused")

            override suspend fun register(phone: String, password: String): AuthResult = AuthResult.Failure("unused")

            override suspend fun refresh(refreshToken: String): AuthResult {
                refreshCallCount += 1
                refreshStarted.complete(Unit)
                allowRefresh.await()
                return if (refreshCallCount == 1) {
                    AuthResult.Success(refreshedSession)
                } else {
                    AuthResult.Failure("Refresh token expired or revoked")
                }
            }

            override suspend fun logout(refreshToken: String): AuthResult = AuthResult.LoggedOut
        }
        val repository = AuthRepository(api, tokenStore, nowMillis = { 1_000L })

        val first = async { repository.ensureValidSession() }
        refreshStarted.await()
        val second = async { repository.ensureValidSession() }
        allowRefresh.complete(Unit)

        assertEquals(listOf(refreshedSession, refreshedSession), awaitAll(first, second))
        assertEquals(1, api.refreshCallCount)
        assertEquals(refreshedSession, tokenStore.currentSession())
        assertEquals(refreshedSession, repository.sessionState.value)
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
        var refreshCallCount: Int = 0

        override suspend fun login(phone: String, password: String): AuthResult = loginResult

        override suspend fun register(phone: String, password: String): AuthResult = registerResult

        override suspend fun refresh(refreshToken: String): AuthResult {
            refreshCallCount += 1
            return refreshResult
        }

        override suspend fun logout(refreshToken: String): AuthResult {
            loggedOutRefreshToken = refreshToken
            return AuthResult.Success(AuthSession("token", "13800138000", "13800138000", expiresAtMillis = 2_000L))
        }
    }
}
