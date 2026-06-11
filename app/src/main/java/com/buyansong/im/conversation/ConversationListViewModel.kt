package com.buyansong.im.conversation

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.auth.ValidSessionProvider
import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.message.MessageRepository
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.group.GroupRepository
import com.buyansong.im.storage.Conversation
import com.buyansong.im.storage.ConversationType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
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
    val mentionUnreadCount: Int = 0,
    val isGroup: Boolean = false,
    val mentionDisplayNamesById: Map<String, String> = emptyMap()
)

data class ConversationListUiState(
    val items: List<ConversationListItem> = emptyList(),
    val connectionStatus: String = "未连接",
    val navigationTargetPeerId: String? = null,
    val navigationTargetConversationId: String? = null,
    val isLoadingMore: Boolean = false,
    val hasMoreConversations: Boolean = true,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
)

internal data class ConversationListScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int
)

internal fun normalizeConversationListScrollPosition(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int
): ConversationListScrollPosition {
    return ConversationListScrollPosition(
        firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
    )
}

class ConversationListViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val profileRepository: ProfileRepository,
    private val groupRepository: GroupRepository? = null,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ConversationListUiState())
    val state: StateFlow<ConversationListUiState> = mutableState.asStateFlow()
    private var started = false
    private val jobs = mutableListOf<Job>()
    private var loadedConversations: List<Conversation> = emptyList()
    private var prefetchedPage: List<Conversation>? = null
    private var prefetchJob: Job? = null

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
        val trimmedTarget = peerId.trim()
        if (trimmedTarget.isEmpty()) {
            return
        }
        scope.launch(dispatcher) {
            val conversationId = if (trimmedTarget.startsWith("group:")) {
                repository.openConversationById(session.userId, trimmedTarget)
            } else {
                repository.openConversation(session.userId, trimmedTarget)
            }
            refresh()
            mutableState.value = mutableState.value.copy(
                navigationTargetPeerId = if (trimmedTarget.startsWith("group:")) null else trimmedTarget,
                navigationTargetConversationId = conversationId
            )
        }
    }

    fun consumeNavigationTarget() {
        mutableState.value = mutableState.value.copy(
            navigationTargetPeerId = null,
            navigationTargetConversationId = null
        )
    }

    fun deleteConversation(conversationId: String) {
        val trimmedConversationId = conversationId.trim()
        if (trimmedConversationId.isEmpty()) {
            return
        }
        scope.launch(dispatcher) {
            repository.deleteLocalConversation(trimmedConversationId)
            refresh()
        }
    }

    fun updateScrollPosition(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val normalized = normalizeConversationListScrollPosition(
            firstVisibleItemIndex = firstVisibleItemIndex,
            firstVisibleItemScrollOffset = firstVisibleItemScrollOffset
        )
        val current = mutableState.value
        if (
            current.firstVisibleItemIndex == normalized.firstVisibleItemIndex &&
            current.firstVisibleItemScrollOffset == normalized.firstVisibleItemScrollOffset
        ) {
            return
        }
        mutableState.value = current.copy(
            firstVisibleItemIndex = normalized.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = normalized.firstVisibleItemScrollOffset
        )
    }

    fun loadMoreConversations() {
        val currentState = mutableState.value
        if (currentState.isLoadingMore || !currentState.hasMoreConversations || loadedConversations.isEmpty()) {
            return
        }
        mutableState.value = currentState.copy(isLoadingMore = true)
        scope.launch(dispatcher) {
            // 无论命中缓存还是走真实查询,整批合并/组装都放到后台执行,避免分页边界阻塞主线程。
            val cached = prefetchedPage
            if (cached != null) {
                prefetchedPage = null
                prefetchJob?.cancel()
                prefetchJob = null
                applyPage(cached)
                return@launch
            }
            // 缓存未就绪:取消可能仍在跑的预加载(避免脏数据),走真实查询
            prefetchJob?.cancel()
            prefetchJob = null
            val cursor = loadedConversations.lastOrNull()
            if (cursor == null) {
                mutableState.value = mutableState.value.copy(isLoadingMore = false)
                return@launch
            }
            val nextPage = repository.conversationPage(
                beforeLastMessageTime = cursor.lastMessageTime,
                beforeConversationId = cursor.conversationId,
                limit = CONVERSATION_PAGE_SIZE
            )
            applyPage(nextPage)
        }
    }

    private fun applyPage(page: List<Conversation>) {
        loadedConversations = mergeConversations(loadedConversations, page)
        val hasMore = page.size == CONVERSATION_PAGE_SIZE
        updateItems(
            conversations = loadedConversations,
            hasMoreConversations = hasMore,
            isLoadingMore = false
        )
        if (hasMore) {
            scheduleNextPagePrefetch()
        }
    }

    private fun scheduleNextPagePrefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(dispatcher) {
            val cursor = loadedConversations.lastOrNull() ?: return@launch
            val nextPage = repository.conversationPage(
                beforeLastMessageTime = cursor.lastMessageTime,
                beforeConversationId = cursor.conversationId,
                limit = CONVERSATION_PAGE_SIZE
            )
            ensureActive()
            prefetchedPage = nextPage
        }
    }

    private suspend fun refresh() {
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchedPage = null
        val firstPage = repository.conversationPage(
            beforeLastMessageTime = null,
            beforeConversationId = null,
            limit = CONVERSATION_PAGE_SIZE
        )
        loadedConversations = mergeConversations(
            firstPage,
            loadedConversations.filter { repository.conversation(it.conversationId) != null }
        )
        val hasMoreAfterFirstPage = if (loadedConversations.size <= firstPage.size) {
            firstPage.size == CONVERSATION_PAGE_SIZE
        } else {
            mutableState.value.hasMoreConversations
        }
        val conversations = loadedConversations
        profileRepository.bootstrapSession(session)
        updateItems(conversations, hasMoreConversations = hasMoreAfterFirstPage)
        val validSession = validSessionProvider()
        if (validSession != null) {
            val singleChatPeerIds = conversations
                .filterNot { it.type == ConversationType.GROUP || it.conversationId.startsWith("group:") }
                .map { it.peerIdForCurrentSession() }
            val allUserIds = singleChatPeerIds
                .plus(mentionedUserIdsFor(conversations))
                .plus(recalledUserIdsFor(conversations))
                .distinct()
            val remoteVersions = conversations
                .filterNot { it.type == ConversationType.GROUP || it.conversationId.startsWith("group:") }
                .mapNotNull { conversation ->
                    val peerId = conversation.peerIdForCurrentSession()
                    val lastMessage = conversation.lastMessageId?.let(repository::findMessageById)
                    lastMessage
                        ?.takeIf { it.senderId == peerId }
                        ?.senderProfileVersion
                        ?.let { peerId to it }
                }
                .toMap()
            profileRepository.ensureProfiles(
                accessToken = validSession.accessToken,
                userIds = allUserIds,
                remoteVersions = remoteVersions
            )
            groupRepository?.syncGroups(validSession.accessToken)
        }
        val refreshedFirstPage = repository.conversationPage(
            beforeLastMessageTime = null,
            beforeConversationId = null,
            limit = CONVERSATION_PAGE_SIZE
        )
        loadedConversations = mergeConversations(
            refreshedFirstPage,
            loadedConversations.filter { repository.conversation(it.conversationId) != null }
        )
        updateItems(loadedConversations, hasMoreConversations = hasMoreAfterFirstPage)
        if (hasMoreAfterFirstPage) {
            scheduleNextPagePrefetch()
        }
    }

    private fun updateItems(
        conversations: List<Conversation>,
        hasMoreConversations: Boolean = mutableState.value.hasMoreConversations,
        isLoadingMore: Boolean = mutableState.value.isLoadingMore
    ) {
        val mentionDisplayNamesByConversationId = mentionDisplayNamesByConversationId(conversations)
        val items = conversations.map { conversation ->
            conversation.toItem(
                mentionDisplayNamesById = mentionDisplayNamesByConversationId[conversation.conversationId].orEmpty()
            )
        }
            .distinctBy { it.conversationId }
        mutableState.value = mutableState.value.copy(
            items = items,
            isLoadingMore = isLoadingMore,
            hasMoreConversations = hasMoreConversations
        )
    }

    private fun mergeConversations(
        primary: List<Conversation>,
        secondary: List<Conversation>
    ): List<Conversation> {
        return (primary + secondary)
            .distinctBy { it.conversationId }
            .sortedWith(compareByDescending<Conversation> { it.lastMessageTime }.thenBy { it.conversationId })
    }

    private fun connectIfNeeded() {
        when (connection.states.value) {
            ConnectionState.Disconnected,
            is ConnectionState.Failed,
            is ConnectionState.Reconnecting -> connection.connect(session.token)
            ConnectionState.Connecting,
            ConnectionState.Connected,
            ConnectionState.Authenticated -> Unit
        }
    }

    private fun Conversation.toItem(mentionDisplayNamesById: Map<String, String>): ConversationListItem {
        val resolvedPeerId = peerIdForCurrentSession()
        if (type == ConversationType.GROUP || conversationId.startsWith("group:")) {
            return ConversationListItem(
                conversationId = conversationId,
                peerId = resolvedPeerId,
                peerName = title.ifBlank { peerName },
                peerAvatarUrl = avatarUrl,
                lastMessagePreview = groupRecallPreview() ?: lastMessagePreview,
                lastMessageTime = lastMessageTime,
                unreadCount = unreadCount,
                mentionUnreadCount = mentionUnreadCount,
                isGroup = true,
                mentionDisplayNamesById = mentionDisplayNamesById
            )
        }
        val profile = profileRepository.localProfile(resolvedPeerId)
        return ConversationListItem(
            conversationId = conversationId,
            peerId = resolvedPeerId,
            peerName = profile?.nickname ?: if (resolvedPeerId == peerId) peerName else resolvedPeerId,
            peerAvatarUrl = profile?.avatarUrl,
            lastMessagePreview = lastMessagePreview,
            lastMessageTime = lastMessageTime,
            unreadCount = unreadCount,
            mentionUnreadCount = mentionUnreadCount,
            isGroup = false,
            mentionDisplayNamesById = mentionDisplayNamesById
        )
    }

    private fun mentionedUserIdsFor(conversations: List<Conversation>): List<String> {
        return conversations
            .mapNotNull { it.lastMessageId }
            .mapNotNull(repository::findMessageById)
            .flatMap { it.mentionedUserIds }
            .distinct()
    }

    private fun recalledUserIdsFor(conversations: List<Conversation>): List<String> {
        return conversations
            .filter { it.type == ConversationType.GROUP || it.conversationId.startsWith("group:") }
            .mapNotNull { it.lastMessageId }
            .mapNotNull(repository::findMessageById)
            .filter { it.isRecalled }
            .mapNotNull { it.recalledBy ?: it.senderId }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun Conversation.groupRecallPreview(): String? {
        if (type != ConversationType.GROUP && !conversationId.startsWith("group:")) {
            return null
        }
        val message = lastMessageId?.let(repository::findMessageById) ?: return null
        if (!message.isRecalled) {
            return null
        }
        if (message.senderId == session.userId) {
            return "你撤回了一条消息"
        }
        val recalledBy = message.recalledBy?.takeIf { it.isNotBlank() } ?: message.senderId
        val groupId = message.groupId ?: conversationId.removePrefix("group:")
        val displayName = profileRepository.localProfile(recalledBy)
            ?.nickname
            ?.takeIf { it.isNotBlank() }
            ?: groupRepository
                ?.localMembers(groupId)
                ?.firstOrNull { it.userId == recalledBy }
                ?.displayName
                ?.takeIf { it.isNotBlank() }
            ?: recalledBy
        return "${displayName}撤回了一条消息"
    }

    private fun mentionDisplayNamesByConversationId(conversations: List<Conversation>): Map<String, Map<String, String>> {
        return conversations.associate { conversation ->
            val mentionDisplayNamesById = conversation.lastMessageId
                ?.let(repository::findMessageById)
                ?.mentionedUserIds
                .orEmpty()
                .distinct()
                .mapNotNull { userId ->
                    val nickname = profileRepository.localProfile(userId)
                        ?.nickname
                        ?.takeIf { it.isNotBlank() }
                    nickname?.let { userId to it }
                }
                .toMap()
            conversation.conversationId to mentionDisplayNamesById
        }
    }

    private fun ConnectionState.toStatusText(): String {
        return when (this) {
            ConnectionState.Disconnected -> "未连接"
            ConnectionState.Connecting -> "正在连接"
            ConnectionState.Connected -> "已连接"
            ConnectionState.Authenticated -> "已认证"
            is ConnectionState.Reconnecting -> "${delayMillis / 1_000} 秒后重连"
            is ConnectionState.Failed -> "连接失败：$reason"
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
        const val CONVERSATION_PAGE_SIZE = 50
    }
}
