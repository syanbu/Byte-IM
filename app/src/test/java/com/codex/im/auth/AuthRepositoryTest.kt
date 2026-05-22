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
        val api = FakeAuthApi(loginResult = AuthResult.Success(AuthSession("token-a", "u1", "Alice")))
        val tokenStore = InMemoryTokenStore()
        val repository = AuthRepository(api, tokenStore)

        val result = repository.login("alice", "password")

        assertTrue(result is AuthResult.Success)
        assertEquals("token-a", tokenStore.currentSession()?.token)
        assertEquals("u1", tokenStore.currentSession()?.userId)
    }

    @Test
    fun loginFailureDoesNotOverwriteExistingSession() = runTest {
        val api = FakeAuthApi(loginResult = AuthResult.Failure("bad credentials"))
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("old-token", "u0", "Old"))
        val repository = AuthRepository(api, tokenStore)

        val result = repository.login("alice", "wrong")

        assertTrue(result is AuthResult.Failure)
        assertEquals("old-token", tokenStore.currentSession()?.token)
    }

    @Test
    fun logoutClearsStoredSession() = runTest {
        val tokenStore = InMemoryTokenStore()
        tokenStore.save(AuthSession("token-a", "u1", "Alice"))
        val repository = AuthRepository(FakeAuthApi(), tokenStore)

        repository.logout()

        assertNull(tokenStore.currentSession())
    }

    private class FakeAuthApi(
        private val loginResult: AuthResult = AuthResult.Success(AuthSession("token", "u1", "Alice")),
        private val registerResult: AuthResult = AuthResult.Success(AuthSession("token", "u1", "Alice"))
    ) : AuthApi {
        override suspend fun login(username: String, password: String): AuthResult = loginResult

        override suspend fun register(username: String, password: String): AuthResult = registerResult
    }
}
