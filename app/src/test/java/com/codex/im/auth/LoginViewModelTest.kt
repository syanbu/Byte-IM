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
            FakeAuthApi(AuthResult.Success(AuthSession("token-a", "u1", "alice"))),
            InMemoryTokenStore()
        )
        val viewModel = LoginViewModel(repository)

        viewModel.login("alice", "password")

        val state = viewModel.state.value
        assertTrue(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertEquals("alice", state.session?.username)
        assertEquals(null, state.errorMessage)
    }

    @Test
    fun loginFailureExposesErrorState() = runTest {
        val repository = AuthRepository(
            FakeAuthApi(AuthResult.Failure("bad credentials")),
            InMemoryTokenStore()
        )
        val viewModel = LoginViewModel(repository)

        viewModel.login("alice", "wrong")

        val state = viewModel.state.value
        assertFalse(state.isAuthenticated)
        assertFalse(state.isLoading)
        assertEquals("bad credentials", state.errorMessage)
    }

    private class FakeAuthApi(private val result: AuthResult) : AuthApi {
        override suspend fun login(username: String, password: String): AuthResult = result

        override suspend fun register(username: String, password: String): AuthResult = result
    }
}
