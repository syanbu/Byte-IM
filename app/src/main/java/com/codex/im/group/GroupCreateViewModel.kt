package com.codex.im.group

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

data class GroupCreateContactItem(
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val isSelected: Boolean = false
)

data class GroupCreateUiState(
    val contacts: List<GroupCreateContactItem> = emptyList(),
    val canCreate: Boolean = false,
    val createdConversationId: String? = null,
    val isCreating: Boolean = false,
    val errorMessage: String? = null
)

class GroupCreateViewModel(
    private val session: AuthSession,
    private val profileRepository: ProfileRepository,
    private val groupRepository: GroupRepository,
    private val contactResolver: (String) -> List<String>,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(GroupCreateUiState())
    val state: StateFlow<GroupCreateUiState> = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var started = false

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

    fun toggleContact(userId: String) {
        val trimmedUserId = userId.trim()
        if (trimmedUserId.isEmpty()) {
            return
        }
        val contacts = mutableState.value.contacts.map { item ->
            if (item.userId == trimmedUserId) {
                item.copy(isSelected = !item.isSelected)
            } else {
                item
            }
        }
        mutableState.value = mutableState.value.copy(
            contacts = contacts,
            canCreate = contacts.any { it.isSelected }
        )
    }

    fun createGroup(now: Long = System.currentTimeMillis()) {
        val selectedUserIds = mutableState.value.contacts
            .filter { it.isSelected }
            .map { it.userId }
        if (selectedUserIds.isEmpty()) {
            return
        }
        scope.launch(dispatcher) {
            mutableState.value = mutableState.value.copy(isCreating = true, errorMessage = null)
            val validSession = validSessionProvider()
            if (validSession == null) {
                mutableState.value = mutableState.value.copy(
                    isCreating = false,
                    errorMessage = "登录状态已失效，请重新登录"
                )
                return@launch
            }
            val groupName = "群聊(${selectedUserIds.size + 1})"
            when (val result = groupRepository.createGroup(
                accessToken = validSession.accessToken,
                ownerId = session.userId,
                name = groupName,
                memberUserIds = selectedUserIds,
                now = now
            )) {
                is GroupCreateResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        isCreating = false,
                        createdConversationId = "group:${result.group.groupId}"
                    )
                }
                is GroupCreateResult.Failure -> {
                    mutableState.value = mutableState.value.copy(
                        isCreating = false,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun consumeCreatedConversation() {
        mutableState.value = mutableState.value.copy(createdConversationId = null)
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(dispatcher) {
            val selectedUserIds = mutableState.value.contacts
                .filter { it.isSelected }
                .map { it.userId }
                .toSet()
            profileRepository.bootstrapSession(session)
            val contactIds = contactResolver(session.userId)
            val validSession = validSessionProvider()
            if (validSession != null) {
                profileRepository.refreshProfiles(validSession.accessToken, contactIds)
            }
            val contacts = contactIds.map { contactId ->
                val profile = profileRepository.localProfile(contactId)
                GroupCreateContactItem(
                    userId = contactId,
                    displayName = profile?.nickname ?: contactId,
                    avatarUrl = profile?.avatarUrl,
                    isSelected = contactId in selectedUserIds
                )
            }
            mutableState.value = mutableState.value.copy(
                contacts = contacts,
                canCreate = contacts.any { it.isSelected }
            )
        }
    }
}
