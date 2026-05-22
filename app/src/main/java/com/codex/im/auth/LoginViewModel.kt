package com.codex.im.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LoginUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val session: AuthSession? = null,
    val errorMessage: String? = null
)

class LoginViewModel(private val repository: AuthRepository) {
    private val mutableState = MutableStateFlow(
        LoginUiState(
            isAuthenticated = repository.currentSession() != null,
            session = repository.currentSession()
        )
    )
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    suspend fun login(username: String, password: String) {
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
        when (val result = repository.login(username.trim(), password)) {
            is AuthResult.Success -> {
                mutableState.value = LoginUiState(
                    isAuthenticated = true,
                    session = result.session
                )
            }
            is AuthResult.Failure -> {
                mutableState.value = LoginUiState(errorMessage = result.message)
            }
        }
    }

    suspend fun register(username: String, password: String) {
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
        when (val result = repository.register(username.trim(), password)) {
            is AuthResult.Success -> {
                mutableState.value = LoginUiState(
                    isAuthenticated = true,
                    session = result.session
                )
            }
            is AuthResult.Failure -> {
                mutableState.value = LoginUiState(errorMessage = result.message)
            }
        }
    }

    fun logout() {
        repository.logout()
        mutableState.value = LoginUiState()
    }
}
