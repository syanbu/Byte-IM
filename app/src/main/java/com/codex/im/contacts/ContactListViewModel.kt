package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.FriendContact
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
    val navigationTargetPeerId: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
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
            return
        }
        started = true
        refresh()
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
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

    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val safeIndex = firstVisibleItemIndex.coerceAtLeast(0)
        val safeOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
        val current = mutableState.value
        if (
            current.firstVisibleItemIndex == safeIndex &&
            current.firstVisibleItemScrollOffset == safeOffset
        ) {
            return
        }
        mutableState.value = current.copy(
            firstVisibleItemIndex = safeIndex,
            firstVisibleItemScrollOffset = safeOffset
        )
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch(dispatcher) {
            profileRepository.bootstrapSession(session)
            val cachedContacts = contactRepository.cachedFriends(session.userId)
            if (cachedContacts.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(items = buildItems(cachedContacts.map { it.userId }))
            }
            val validSession = validSessionProvider()
            val contacts = if (validSession != null) {
                contactRepository.refreshFriends(validSession.accessToken, session.userId)
            } else {
                cachedContacts
            }
            val contactIds = contacts.map { it.userId }
            mutableState.value = mutableState.value.copy(items = buildItems(contactIds))
            if (validSession != null) {
                refreshChangedProfiles(validSession.accessToken, contacts, contactIds)
            }
        }
    }

    private suspend fun refreshChangedProfiles(
        accessToken: String,
        contacts: List<FriendContact>,
        contactIds: List<String>
    ) {
        val changedIds = changedProfileIds(contacts)
        if (changedIds.isEmpty()) {
            return
        }
        changedIds
            .take(INITIAL_PROFILE_REFRESH_LIMIT)
            .let { ids ->
                if (ids.isNotEmpty()) {
                    profileRepository.refreshProfiles(accessToken, ids)
                    mutableState.value = mutableState.value.copy(items = buildItems(contactIds))
                }
            }
        changedIds
            .drop(INITIAL_PROFILE_REFRESH_LIMIT)
            .chunked(NEXT_PROFILE_REFRESH_LIMIT)
            .forEach { ids ->
                profileRepository.refreshProfiles(accessToken, ids)
                mutableState.value = mutableState.value.copy(items = buildItems(contactIds))
            }
    }

    private fun changedProfileIds(contacts: List<FriendContact>): List<String> {
        return contacts
            .filter { contact ->
                val local = profileRepository.localProfile(contact.userId)
                local == null || contact.profileUpdatedAt > local.updatedAt
            }
            .map { it.userId }
    }

    private fun buildItems(contactIds: List<String>): List<ContactListItem> {
        return contactIds.map { contactId ->
            val profile = profileRepository.localProfile(contactId)
            ContactListItem(
                userId = contactId,
                displayName = profile?.nickname ?: contactId,
                avatarUrl = profile?.avatarUrl
            )
        }
    }

    private companion object {
        const val INITIAL_PROFILE_REFRESH_LIMIT = 20
        const val NEXT_PROFILE_REFRESH_LIMIT = 10
    }
}
