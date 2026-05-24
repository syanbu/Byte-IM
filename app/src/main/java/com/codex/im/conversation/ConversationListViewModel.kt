package com.codex.im.conversation

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageRepository
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.Conversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConversationListItem(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val peerAvatarUrl: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int
)

data class ConversationListUiState(
    val items: List<ConversationListItem> = emptyList(),
    val connectionStatus: String = "Disconnected",
    val navigationTargetPeerId: String? = null
)

class ConversationListViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val defaultPeerResolver: (String) -> String,
    private val profileRepository: ProfileRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ConversationListUiState())
    val state: StateFlow<ConversationListUiState> = mutableState.asStateFlow()
    private var started = false
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (started) {
            scope.launch(dispatcher) {
                refresh()
            }
            return
        }
        started = true
        connectIfNeeded()
        jobs += scope.launch(dispatcher) {
            connection.states.collect { state ->
                mutableState.value = mutableState.value.copy(connectionStatus = state.toStatusText())
            }
        }
        jobs += scope.launch(dispatcher) {
            connection.incomingPackets.collect { packet ->
                repository.handlePacket(packet)
                refresh()
            }
        }
        jobs += scope.launch(dispatcher) {
            repository.conversationUpdates.collect {
                refresh()
            }
        }
        jobs += scope.launch(dispatcher) {
            refresh()
        }
    }

    fun stop() {
        if (!started) {
            return
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        started = false
    }

    fun openConversation(peerId: String) {
        val trimmedPeerId = peerId.trim()
        if (trimmedPeerId.isEmpty()) {
            return
        }
        scope.launch(dispatcher) {
            repository.openConversation(session.userId, trimmedPeerId)
            refresh()
            mutableState.value = mutableState.value.copy(navigationTargetPeerId = trimmedPeerId)
        }
    }

    fun consumeNavigationTarget() {
        mutableState.value = mutableState.value.copy(navigationTargetPeerId = null)
    }

    private suspend fun refresh() {
        val conversations = repository.conversations(limit = 50)
        val defaultPeerId = defaultPeerResolver(session.userId)
        val defaultConversationId = repository.conversationIdFor(session.userId, defaultPeerId)
        profileRepository.bootstrapSession(session)
        profileRepository.refreshProfiles(
            accessToken = session.accessToken,
            userIds = conversations.map { it.peerIdForCurrentSession() } + defaultPeerId
        )
        val items = conversations.map { it.toItem() }
            .distinctBy { it.conversationId }
            .toMutableList()
        if (items.none { it.conversationId == defaultConversationId }) {
            items.add(defaultPeerItem(defaultPeerId))
        }
        mutableState.value = mutableState.value.copy(items = items)
    }

    private fun connectIfNeeded() {
        when (connection.states.value) {
            ConnectionState.Disconnected,
            is ConnectionState.Failed -> connection.connect(session.token)
            ConnectionState.Connecting,
            ConnectionState.Connected,
            ConnectionState.Authenticated -> Unit
        }
    }

    private fun Conversation.toItem(): ConversationListItem {
        val resolvedPeerId = peerIdForCurrentSession()
        val profile = profileRepository.localProfile(resolvedPeerId)
        return ConversationListItem(
            conversationId = conversationId,
            peerId = resolvedPeerId,
            peerName = profile?.nickname ?: if (resolvedPeerId == peerId) peerName else resolvedPeerId,
            peerAvatarUrl = profile?.avatarUrl,
            lastMessagePreview = lastMessagePreview,
            lastMessageTime = lastMessageTime,
            unreadCount = unreadCount
        )
    }

    private fun defaultPeerItem(peerId: String): ConversationListItem {
        val profile = profileRepository.localProfile(peerId)
        return ConversationListItem(
            conversationId = repository.conversationIdFor(session.userId, peerId),
            peerId = peerId,
            peerName = profile?.nickname ?: peerId,
            peerAvatarUrl = profile?.avatarUrl,
            lastMessagePreview = "",
            lastMessageTime = 0L,
            unreadCount = 0
        )
    }

    private fun ConnectionState.toStatusText(): String {
        return when (this) {
            ConnectionState.Disconnected -> "Disconnected"
            ConnectionState.Connecting -> "Connecting"
            ConnectionState.Connected -> "Connected"
            ConnectionState.Authenticated -> "Authenticated"
            is ConnectionState.Failed -> "Failed: $reason"
        }
    }

    private fun Conversation.peerIdForCurrentSession(): String {
        val parts = conversationId.split(":")
        if (parts.size == 3 && parts[0] == "single") {
            val first = parts[1]
            val second = parts[2]
            return when (session.userId) {
                first -> second
                second -> first
                else -> peerId
            }
        }
        return peerId
    }
}
