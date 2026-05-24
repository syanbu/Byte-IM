package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.MessageRepository
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.ChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val peerId: String = "13900113900",
    val peerName: String = peerId,
    val peerAvatarUrl: String? = null,
    val currentUserAvatarUrl: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasMoreLocal: Boolean = true,
    val isHistoryMemoryLimitReached: Boolean = false,
    val errorMessage: String? = null
)

class ChatViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val profileRepository: ProfileRepository,
    initialPeerId: String = "13900113900",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ChatUiState(peerId = initialPeerId))
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private var started = false
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (started) {
            return
        }
        started = true
        repository.openConversation(session.userId, mutableState.value.peerId)
        connectIfNeeded()
        jobs += scope.launch(dispatcher) {
            try {
                connection.incomingPackets.collect { packet ->
                    repository.handlePacket(packet)
                    refreshKeepingHistory()
                }
            } finally {
                repository.closeConversation()
            }
        }
        jobs += scope.launch(dispatcher) {
            refreshProfiles()
            refreshInitialPage()
        }
    }

    fun stop() {
        if (!started) {
            return
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        repository.closeConversation()
        started = false
    }

    fun selectPeer(peerId: String) {
        val trimmedPeerId = peerId.trim()
        mutableState.value = mutableState.value.copy(
            peerId = trimmedPeerId,
            peerName = trimmedPeerId,
            peerAvatarUrl = null,
            messages = emptyList(),
            isLoadingMore = false,
            hasMoreLocal = true,
            isHistoryMemoryLimitReached = false,
            errorMessage = null
        )
        scope.launch(dispatcher) {
            if (trimmedPeerId.isNotEmpty()) {
                repository.openConversation(session.userId, trimmedPeerId)
            }
            refreshProfiles()
            refreshInitialPage()
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
            refreshKeepingHistory()
        }
    }

    suspend fun loadMoreHistory() {
        val current = mutableState.value
        if (current.isLoadingMore || !current.hasMoreLocal || current.messages.isEmpty()) {
            return
        }
        if (current.messages.size >= MAX_RETAINED_MESSAGES) {
            mutableState.value = current.copy(isHistoryMemoryLimitReached = true)
            return
        }
        mutableState.value = current.copy(isLoadingMore = true, errorMessage = null)
        withContext(dispatcher) {
            val peerId = mutableState.value.peerId
            val beforeTime = mutableState.value.messages.minOf { it.createdAt }
            try {
                val page = repository.historyPage(
                    userId = session.userId,
                    peerId = peerId,
                    beforeTime = beforeTime,
                    limit = HISTORY_PAGE_SIZE
                )
                val mergedMessages = mergeMessages(mutableState.value.messages, page)
                mutableState.value = mutableState.value.copy(
                    messages = mergedMessages,
                    isLoadingMore = false,
                    isHistoryMemoryLimitReached = mergedMessages.size >= MAX_RETAINED_MESSAGES && page.isNotEmpty(),
                    hasMoreLocal = page.size == HISTORY_PAGE_SIZE,
                    errorMessage = null
                )
            } catch (error: RuntimeException) {
                mutableState.value = mutableState.value.copy(
                    isLoadingMore = false,
                    errorMessage = error.message ?: "Failed to load history"
                )
            }
        }
    }

    private fun refreshInitialPage() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            mutableState.value = mutableState.value.copy(messages = emptyList(), hasMoreLocal = false)
            return
        }
        val messages = repository.historyPage(
            userId = session.userId,
            peerId = peerId,
            beforeTime = null,
            limit = HISTORY_PAGE_SIZE
        )
        mutableState.value = mutableState.value.copy(
            messages = messages,
            hasMoreLocal = messages.size == HISTORY_PAGE_SIZE,
            isHistoryMemoryLimitReached = false,
            errorMessage = null
        )
    }

    private suspend fun refreshProfiles() {
        profileRepository.bootstrapSession(session)
        val peerId = mutableState.value.peerId
        if (peerId.isNotBlank()) {
            profileRepository.refreshProfiles(session.accessToken, listOf(session.userId, peerId))
        }
        val peerProfile = profileRepository.localProfile(peerId)
        val currentUserProfile = profileRepository.localProfile(session.userId)
        mutableState.value = mutableState.value.copy(
            peerName = peerProfile?.nickname ?: peerId,
            peerAvatarUrl = peerProfile?.avatarUrl,
            currentUserAvatarUrl = currentUserProfile?.avatarUrl
        )
    }

    private fun refreshKeepingHistory() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            return
        }
        val currentMessages = mutableState.value.messages
        val limit = maxOf(HISTORY_PAGE_SIZE, currentMessages.size)
        val latestMessages = repository.historyPage(
            userId = session.userId,
            peerId = peerId,
            beforeTime = null,
            limit = limit
        )
        mutableState.value = mutableState.value.copy(
            messages = mergeMessages(currentMessages, latestMessages),
            errorMessage = null
        )
    }

    private fun mergeMessages(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        return (current + incoming)
            .associateBy { it.messageId }
            .values
            .sortedWith(compareByDescending<ChatMessage> { it.createdAt }.thenByDescending { it.messageId })
            .take(MAX_RETAINED_MESSAGES)
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

    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_RETAINED_MESSAGES = 2_000
    }
}
