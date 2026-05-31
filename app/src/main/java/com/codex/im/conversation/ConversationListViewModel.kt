package com.codex.im.conversation

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.ChatThumbnailPreloader
import com.codex.im.message.MessageRepository
import com.codex.im.message.NoopChatThumbnailPreloader
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.Conversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
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
    val unreadCount: Int,
    val isGroup: Boolean = false
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
    private val profileRepository: ProfileRepository,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val thumbnailPreloader: ChatThumbnailPreloader = NoopChatThumbnailPreloader,
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
        jobs += scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            connection.states.collect { state ->
                mutableState.value = mutableState.value.copy(connectionStatus = state.toStatusText())
            }
        }
        jobs += scope.launch(dispatcher) {
            repository.conversationUpdates.collect {
                refresh()
            }
        }
        connectIfNeeded()
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
            launch {
                thumbnailPreloader.preload(
                    repository.recentLocalThumbnailPaths(
                        userId = session.userId,
                        peerId = trimmedPeerId,
                        limit = RECENT_THUMBNAIL_PRELOAD_LIMIT
                    )
                )
            }
        }
    }

    fun consumeNavigationTarget() {
        mutableState.value = mutableState.value.copy(navigationTargetPeerId = null)
    }

    private suspend fun refresh() {
        val conversations = repository.conversations(limit = 50)
        profileRepository.bootstrapSession(session)
        updateItems(conversations)
        val validSession = validSessionProvider()
        if (validSession != null) {
            profileRepository.refreshProfiles(
                accessToken = validSession.accessToken,
                userIds = conversations.map { it.peerIdForCurrentSession() }
            )
        }
        updateItems(conversations)
    }

    private fun updateItems(conversations: List<Conversation>) {
        val items = conversations.map { it.toItem() }
            .distinctBy { it.conversationId }
        mutableState.value = mutableState.value.copy(items = items)
    }

    private fun connectIfNeeded() {
        when (connection.states.value) {
            ConnectionState.Disconnected,
            is ConnectionState.Failed -> connection.connect(session.token)
            ConnectionState.Connecting,
            ConnectionState.Connected,
            ConnectionState.Authenticated,
            is ConnectionState.Reconnecting -> Unit
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
            unreadCount = unreadCount,
            isGroup = conversationId.startsWith("group:")
        )
    }

    private fun ConnectionState.toStatusText(): String {
        return when (this) {
            ConnectionState.Disconnected -> "Disconnected"
            ConnectionState.Connecting -> "Connecting"
            ConnectionState.Connected -> "Connected"
            ConnectionState.Authenticated -> "Authenticated"
            is ConnectionState.Reconnecting -> "Reconnecting in ${delayMillis / 1_000}s"
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

    private companion object {
        const val RECENT_THUMBNAIL_PRELOAD_LIMIT = 5
    }
}
