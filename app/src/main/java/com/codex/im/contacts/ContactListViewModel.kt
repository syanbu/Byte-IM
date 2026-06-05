package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.profile.ProfileRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ContactListItem(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?
)

data class ContactListUiState(
    val items: List<ContactListItem> = emptyList(),
    val navigationTargetPeerId: String? = null
)

class ContactListViewModel(
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val contactRepository: ContactRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ContactListUiState())
    val state: StateFlow<ContactListUiState> = mutableState.asStateFlow()
    private var started = false
    private var refreshJob: Job? = null

    fun start() {
        if (started) {
            refresh()
            return
        }
        started = true
        refresh()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
        started = false
    }

    fun openContact(userId: String) {
        val trimmedUserId = userId.trim()
        if (trimmedUserId.isEmpty()) {
            return
        }
        mutableState.value = mutableState.value.copy(navigationTargetPeerId = trimmedUserId)
    }

    fun consumeNavigationTarget() {
        mutableState.value = mutableState.value.copy(navigationTargetPeerId = null)
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(dispatcher) {
            profileRepository.bootstrapSession(session)
            val validSession = validSessionProvider()
            val contactIds = if (validSession != null) {
                contactRepository.friendUserIds(validSession.accessToken)
            } else {
                emptyList()
            }
            if (validSession != null) {
                profileRepository.refreshProfiles(validSession.accessToken, contactIds)
            }
            val items = contactIds.map { contactId ->
                val profile = profileRepository.localProfile(contactId)
                ContactListItem(
                    userId = contactId,
                    displayName = profile?.nickname ?: contactId,
                    avatarUrl = profile?.avatarUrl
                )
            }
            mutableState.value = mutableState.value.copy(items = items)
        }
    }
}
