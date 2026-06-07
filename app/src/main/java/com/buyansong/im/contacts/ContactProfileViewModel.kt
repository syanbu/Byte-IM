package com.buyansong.im.contacts

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.auth.ValidSessionProvider
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactProfileUiState(
    val profile: UserProfile? = null,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val isSelf: Boolean = false
)

class ContactProfileViewModel(
    val userId: String,
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ContactProfileUiState())
    val state: StateFlow<ContactProfileUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null

    fun start() {
        val cached = profileRepository.localProfile(userId)
        mutableState.value = mutableState.value.copy(
            profile = cached,
            isSelf = userId == session.userId
        )
        if (refreshJob?.isActive == true) {
            return
        }

        refreshJob = scope.launch(dispatcher) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) {
                        ContactProfileDisplayPolicy.sessionExpiredMessage
                    } else {
                        null
                    }
                )
                return@launch
            }

            mutableState.value = mutableState.value.copy(isRefreshing = true, errorMessage = null)
            val remote = profileRepository.refreshProfile(validSession.accessToken, userId)
            if (remote != null) {
                mutableState.value = mutableState.value.copy(
                    profile = remote,
                    isRefreshing = false,
                    errorMessage = null
                )
            } else {
                mutableState.value = mutableState.value.copy(
                    isRefreshing = false,
                    errorMessage = if (cached == null) ContactProfileDisplayPolicy.loadFailedMessage else null
                )
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    fun retry() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
        start()
    }
}
