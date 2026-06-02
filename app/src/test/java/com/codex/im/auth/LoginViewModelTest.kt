package com.codex.im.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Test
    fun loginSuccessExposesAuthenticatedState() = runTest {
        val repository = AuthRepository(
            FakeAuthApi(AuthResult.Success(AuthSession("token-a", "13800138000", "13800138000", expiresAtMillis = 2_000L))),
            InMemoryTokenStore(),
            nowMillis = { 1_000L }
        )
        val viewModel = LoginViewModel(repository, backgroundScope)

        viewModel.login("13800138000", "password")
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertEquals("13800138000", state.session?.username)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun loginFailureExposesErrorState() = runTest {
        val repository = AuthRepository(
            FakeAuthApi(AuthResult.Failure("bad credentials")),
            InMemoryTokenStore()
        )
        val viewModel = LoginViewModel(repository, backgroundScope)

        viewModel.login("13800138000", "wrong")
        runCurrent()

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertEquals("bad credentials", state.errorMessage)
    }

    @Test
    fun clearsAuthenticatedUiStateWhenRepositorySessionIsCleared() = runTest {
        val tokenStore = InMemoryTokenStore().apply {
            save(
                AuthSession(
                    accessToken = "access-a",
                    refreshToken = "refresh-a",
                    userId = "13800138000",
                    username = "13800138000",
                    accessExpiresAtMillis = 2_000L,
                    refreshExpiresAtMillis = 3_000L
                )
            )
        }
        val repository = AuthRepository(
            FakeAuthApi(AuthResult.Success(AuthSession("unused", "13800138000", "13800138000", expiresAtMillis = 2_000L))),
            tokenStore,
            nowMillis = { 1_000L }
        )
        val viewModel = LoginViewModel(repository, backgroundScope)

        assertTrue(viewModel.state.value.isAuthenticated)

        repository.logout()
        runCurrent()

        assertFalse(viewModel.state.value.isAuthenticated)
        assertNull(viewModel.state.value.session)
    }

    @Test
    fun restoreSessionKeepsUserAuthenticatedWhenRefreshFailsDueToNetwork() = runTest {
        val storedSession = AuthSession(
            accessToken = "expired-access",
            refreshToken = "refresh-a",
            userId = "13800138000",
            username = "13800138000",
            accessExpiresAtMillis = 999L,
            refreshExpiresAtMillis = 3_000L
        )
        val repository = AuthRepository(
            FakeAuthApi(
                result = AuthResult.Failure("unused"),
                refreshResult = AuthResult.Failure("网络异常")
            ),
            InMemoryTokenStore().apply { save(storedSession) },
            nowMillis = { 1_000L }
        )
        val viewModel = LoginViewModel(repository, backgroundScope)

        viewModel.restoreSession()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.isAuthenticated)
        assertEquals(storedSession, state.session)
        assertNull(state.errorMessage)
    }

    private class FakeAuthApi(
        private val result: AuthResult,
        private val refreshResult: AuthResult = result
    ) : AuthApi {
        override suspend fun login(phone: String, password: String): AuthResult = result

        override suspend fun register(phone: String, password: String): AuthResult = result

        override suspend fun refresh(refreshToken: String): AuthResult = refreshResult

        override suspend fun logout(refreshToken: String): AuthResult = AuthResult.LoggedOut
    }
}
