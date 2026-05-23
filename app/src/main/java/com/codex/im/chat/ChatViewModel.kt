package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageRepository
import com.codex.im.storage.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val peerId: String = "13900113900",
    val messages: List<ChatMessage> = emptyList()
)

class ChatViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    initialPeerId: String = "13900113900",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ChatUiState(peerId = initialPeerId))
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private var started = false

    fun start() {
        if (started) {
            return
        }
        started = true
        repository.openConversation(session.userId, mutableState.value.peerId)
        connectIfNeeded()
        scope.launch(dispatcher) {
            connection.incomingPackets.collect { packet ->
                repository.handlePacket(packet)
                refresh()
            }
        }
        scope.launch(dispatcher) {
            refresh()
        }
    }

    fun selectPeer(peerId: String) {
        val trimmedPeerId = peerId.trim()
        mutableState.value = mutableState.value.copy(peerId = trimmedPeerId)
        scope.launch(dispatcher) {
            if (trimmedPeerId.isNotEmpty()) {
                repository.openConversation(session.userId, trimmedPeerId)
            }
            refresh()
        }
    }

    suspend fun sendText(content: String, now: Long = System.currentTimeMillis()) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return
        }
        withContext(dispatcher) {
            repository.sendText(
                senderId = session.userId,
                receiverId = mutableState.value.peerId,
                content = trimmed,
                now = now
            )
            refresh()
        }
    }

    private fun refresh() {
        val peerId = mutableState.value.peerId
        mutableState.value = mutableState.value.copy(
            messages = repository.messagesWith(session.userId, peerId)
        )
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
}
