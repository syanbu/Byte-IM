package com.codex.im.conversation

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageRepository
import com.codex.im.storage.Conversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConversationListItem(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ConversationListUiState())
    val state: StateFlow<ConversationListUiState> = mutableState.asStateFlow()
    private var started = false

    fun start() {
        if (started) {
            refresh()
            return
        }
        started = true
        connectIfNeeded()
        scope.launch(dispatcher) {
            connection.states.collect { state ->
                mutableState.value = mutableState.value.copy(connectionStatus = state.toStatusText())
            }
        }
        scope.launch(dispatcher) {
            connection.incomingPackets.collect { packet ->
                repository.handlePacket(packet)
                refresh()
            }
        }
        scope.launch(dispatcher) {
            repository.conversationUpdates.collect {
                refresh()
            }
        }
        scope.launch(dispatcher) {
            refresh()
        }
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

    private fun refresh() {
        val conversations = repository.conversations(limit = 50)
        val defaultPeerId = defaultPeerResolver(session.userId)
        val items = conversations.map { it.toItem() }.toMutableList()
        if (items.none { it.peerId == defaultPeerId }) {
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
        return ConversationListItem(
            conversationId = conversationId,
            peerId = peerId,
            peerName = peerName,
            lastMessagePreview = lastMessagePreview,
            lastMessageTime = lastMessageTime,
            unreadCount = unreadCount
        )
    }

    private fun defaultPeerItem(peerId: String): ConversationListItem {
        return ConversationListItem(
            conversationId = repository.conversationIdFor(session.userId, peerId),
            peerId = peerId,
            peerName = peerId,
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
}
