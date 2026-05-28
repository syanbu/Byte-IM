package com.codex.im.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val session: AuthSession? = null,
    val errorMessage: String? = null
)

class LoginViewModel(
    private val repository: AuthRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val restoredSession = repository.currentSession()
    private val mutableState = MutableStateFlow(
        LoginUiState(
            isLoading = restoredSession == null && repository.hasStoredSession(),
            isAuthenticated = restoredSession != null,
            session = restoredSession
        )
    )
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()

    init {
        scope.launch {
            repository.sessionState.collect { session ->
                syncSessionState(session)
            }
        }
    }

    suspend fun restoreSession() {
        if (mutableState.value.isAuthenticated) {
            return
        }
        val session = repository.restoreSession()
        mutableState.value = if (session == null) {
            LoginUiState()
        } else {
            LoginUiState(isAuthenticated = true, session = session)
        }
    }

    suspend fun login(phone: String, password: String) {
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
        when (val result = repository.login(phone.trim(), password)) {
            is AuthResult.Success -> {
                mutableState.value = LoginUiState(
                    isAuthenticated = true,
                    session = result.session
                )
            }
            is AuthResult.Failure -> {
                mutableState.value = LoginUiState(errorMessage = result.message)
            }
            AuthResult.LoggedOut -> {
                mutableState.value = LoginUiState()
            }
        }
    }

    suspend fun register(phone: String, password: String) {
        mutableState.value = mutableState.value.copy(isLoading = true, errorMessage = null)
        when (val result = repository.register(phone.trim(), password)) {
            is AuthResult.Success -> {
                mutableState.value = LoginUiState(
                    isAuthenticated = true,
                    session = result.session
                )
            }
            is AuthResult.Failure -> {
                mutableState.value = LoginUiState(errorMessage = result.message)
            }
            AuthResult.LoggedOut -> {
                mutableState.value = LoginUiState()
            }
        }
    }

    suspend fun logout() {
        repository.logout()
        mutableState.value = LoginUiState()
    }

    private fun syncSessionState(session: AuthSession?) {
        val current = mutableState.value
        when {
            session == null && (current.isAuthenticated || current.session != null) -> {
                mutableState.value = LoginUiState()
            }
            session != null && (!current.isAuthenticated || current.session != session) -> {
                mutableState.value = LoginUiState(isAuthenticated = true, session = session)
            }
        }
    }
}
