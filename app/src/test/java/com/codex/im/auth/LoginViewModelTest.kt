package com.codex.im.auth

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewModelTest {
    @Test
    fun loginSuccessExposesAuthenticatedState() = runTest {
        val repository = AuthRepository(
            FakeAuthApi(AuthResult.Success(AuthSession("token-a", "13800138000", "13800138000", expiresAtMillis = 2_000L))),
            InMemoryTokenStore(),
            nowMillis = { 1_000L }
        )
        val viewModel = LoginViewModel(repository)

        viewModel.login("13800138000", "password")

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
        val viewModel = LoginViewModel(repository)

        viewModel.login("13800138000", "wrong")

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertEquals("bad credentials", state.errorMessage)
    }

    private class FakeAuthApi(private val result: AuthResult) : AuthApi {
        override suspend fun login(phone: String, password: String): AuthResult = result

        override suspend fun register(phone: String, password: String): AuthResult = result

        override suspend fun refresh(refreshToken: String): AuthResult = result

        override suspend fun logout(refreshToken: String): AuthResult = AuthResult.LoggedOut
    }
}
