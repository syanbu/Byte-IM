package com.buyansong.im.chat

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.auth.ValidSessionProvider
import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import com.buyansong.im.message.DisabledImageUploadApi
import com.buyansong.im.message.ImageUploadApi
import com.buyansong.im.message.ImageUploadTargetsResult
import com.buyansong.im.message.MessageRepository
import com.buyansong.im.message.SelectedChatImageResolver
import com.buyansong.im.message.SelectedChatImage
import com.buyansong.im.message.ChatImageCompressor
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.group.GroupRepository
import com.buyansong.im.group.GroupResult
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageOrderingPolicy
import com.buyansong.im.profile.AvatarPutResult
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.UserProfile
import com.buyansong.im.storage.GroupMember
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val peerId: String = "",
    val peerName: String = peerId,
    val peerAvatarUrl: String? = null,
    val currentUserAvatarUrl: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isLoadingMore: Boolean = false,
    val hasMoreLocal: Boolean = true,
    val isHistoryMemoryLimitReached: Boolean = false,
    val errorMessage: String? = null,
    val peerReadUpToServerSeq: Long? = null,
    val senderProfiles: Map<String, UserProfile> = emptyMap(),
    val mentionMembers: List<GroupMember> = emptyList(),
    val latestOwnSentMessageId: String? = null,
    val groupReadCountForLatest: Int = 0,
    val groupReadersForLatest: List<GroupMember> = emptyList()
)

class ChatViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val profileRepository: ProfileRepository,
    private val groupRepository: GroupRepository? = null,
    initialPeerId: String = "",
    private val imageUploadApi: ImageUploadApi = DisabledImageUploadApi,
    private val imageResolver: SelectedChatImageResolver = ChatImageCompressor,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private sealed interface ConversationTarget {
        val peerId: String
        val conversationId: String
        val isGroup: Boolean

        fun openConversation(repository: MessageRepository, currentUserId: String) {
            repository.openConversationById(
                currentUserId = currentUserId,
                conversationId = conversationId,
                peerId = if (isGroup) null else peerId
            )
        }

        fun historyPage(repository: MessageRepository, beforeTime: Long?, limit: Int): List<ChatMessage> {
            return repository.historyPageByConversationId(conversationId, beforeTime, limit)
        }

        fun peerReadCursor(repository: MessageRepository): Long? {
            return repository.conversationPeerReadCursorByConversationId(conversationId)
        }

        fun missingIncomingImageThumbnails(repository: MessageRepository, limit: Int): List<ChatMessage> {
            return repository.missingIncomingImageThumbnailsByConversationId(conversationId, limit)
        }

        fun sendText(
            repository: MessageRepository,
            senderId: String,
            content: String,
            mentionedUserIds: List<String>,
            now: Long
        )

        fun createLocalImageMessage(
            repository: MessageRepository,
            senderId: String,
            selectedImage: SelectedChatImage,
            now: Long
        ): ChatMessage

        fun createLocalImageMessages(
            repository: MessageRepository,
            senderId: String,
            selectedImages: List<SelectedChatImage>,
            nowBase: Long
        ): List<ChatMessage>
    }

    private data class SingleConversationTarget(
        override val peerId: String,
        override val conversationId: String
    ) : ConversationTarget {
        override val isGroup: Boolean = false

        override fun sendText(
            repository: MessageRepository,
            senderId: String,
            content: String,
            mentionedUserIds: List<String>,
            now: Long
        ) {
            repository.sendText(
                senderId = senderId,
                receiverId = peerId,
                content = content,
                now = now
            )
        }

        override fun createLocalImageMessage(
            repository: MessageRepository,
            senderId: String,
            selectedImage: SelectedChatImage,
            now: Long
        ): ChatMessage {
            return repository.createLocalImageMessage(
                senderId = senderId,
                receiverId = peerId,
                localOriginalPath = selectedImage.localOriginalPath,
                localThumbnailPath = selectedImage.localThumbnailPath,
                imageWidth = selectedImage.width,
                imageHeight = selectedImage.height,
                mimeType = selectedImage.mimeType,
                now = now
            )
        }

        override fun createLocalImageMessages(
            repository: MessageRepository,
            senderId: String,
            selectedImages: List<SelectedChatImage>,
            nowBase: Long
        ): List<ChatMessage> {
            return repository.createLocalImageMessages(
                senderId = senderId,
                receiverId = peerId,
                groupId = null,
                selectedImages = selectedImages,
                nowBase = nowBase
            )
        }
    }

    private data class GroupConversationTarget(
        override val peerId: String
    ) : ConversationTarget {
        override val conversationId: String = peerId
        override val isGroup: Boolean = true
        private val groupId: String = peerId.removePrefix("group:")

        override fun sendText(
            repository: MessageRepository,
            senderId: String,
            content: String,
            mentionedUserIds: List<String>,
            now: Long
        ) {
            repository.sendGroupText(
                senderId = senderId,
                groupId = groupId,
                content = content,
                mentionedUserIds = mentionedUserIds,
                now = now
            )
        }

        override fun createLocalImageMessage(
            repository: MessageRepository,
            senderId: String,
            selectedImage: SelectedChatImage,
            now: Long
        ): ChatMessage {
            return repository.createLocalGroupImageMessage(
                senderId = senderId,
                groupId = groupId,
                localOriginalPath = selectedImage.localOriginalPath,
                localThumbnailPath = selectedImage.localThumbnailPath,
                imageWidth = selectedImage.width,
                imageHeight = selectedImage.height,
                mimeType = selectedImage.mimeType,
                now = now
            )
        }

        override fun createLocalImageMessages(
            repository: MessageRepository,
            senderId: String,
            selectedImages: List<SelectedChatImage>,
            nowBase: Long
        ): List<ChatMessage> {
            return repository.createLocalImageMessages(
                senderId = senderId,
                receiverId = null,
                groupId = groupId,
                selectedImages = selectedImages,
                nowBase = nowBase
            )
        }
    }

    private val mutableState = MutableStateFlow(ChatUiState(peerId = initialPeerId))
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    val currentUserId: String = session.userId
    private var started = false
    private val jobs = mutableListOf<Job>()
    private var groupReadObservationJob: Job? = null
    private var latestGroupReadCursors: List<com.buyansong.im.storage.GroupReadCursor> = emptyList()
    private var groupReadContextPeerId: String? = null
    private val thumbnailRetryCounts = mutableMapOf<String, Int>()
    private val thumbnailRetryJobs = mutableMapOf<String, Job>()

    init {
        hydrateInitialStateFromCache(initialPeerId)
    }

    fun start() {
        if (started) {
            return
        }
        started = true
        if (mutableState.value.peerId.isNotBlank()) {
            openCurrentConversation()
        }
        connectIfNeeded()
        val initialRefreshJob = scope.launch(dispatcher) {
            prepareGroupReadContextForFirstRender()
            refreshInitialPage()
            refreshProfiles()
            recomputeGroupReadIndicator()
            scheduleMissingThumbnailRetries(immediate = true)
        }
        jobs += initialRefreshJob
        jobs += scope.launch(dispatcher) {
            initialRefreshJob.join()
            repository.conversationUpdates.collect {
                refreshKeepingHistory()
                sendGroupReadAckIfNeeded()
                recomputeGroupReadIndicator()
                refreshProfiles()
                scheduleMissingThumbnailRetries(immediate = false)
            }
        }
        jobs += scope.launch(dispatcher) {
            repository.recallFailures.collect { message ->
                mutableState.value = mutableState.value.copy(errorMessage = message)
            }
        }
    }

    fun stop() {
        if (!started) {
            return
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        groupReadObservationJob = null
        groupReadContextPeerId = null
        thumbnailRetryJobs.values.forEach { it.cancel() }
        thumbnailRetryJobs.clear()
        thumbnailRetryCounts.clear()
        repository.closeConversation()
        started = false
    }

    fun selectPeer(peerId: String) {
        val trimmedPeerId = peerId.trim()
        val (peerName, peerAvatarUrl) = cachedPeerDisplay(trimmedPeerId)
        mutableState.value = mutableState.value.copy(
            peerId = trimmedPeerId,
            peerName = peerName,
            peerAvatarUrl = peerAvatarUrl,
            currentUserAvatarUrl = cachedCurrentUserAvatarUrl(),
            senderProfiles = emptyMap(),
            messages = emptyList(),
            isLoadingMore = false,
            hasMoreLocal = true,
            isHistoryMemoryLimitReached = false,
            errorMessage = null,
            peerReadUpToServerSeq = null,
            latestOwnSentMessageId = null,
            groupReadCountForLatest = 0,
            groupReadersForLatest = emptyList(),
            mentionMembers = emptyList()
        )
        scope.launch(dispatcher) {
            if (trimmedPeerId.isNotEmpty()) {
                openCurrentConversation()
            }
            prepareGroupReadContextForFirstRender()
            refreshInitialPage()
            refreshProfiles()
            recomputeGroupReadIndicator()
            scheduleMissingThumbnailRetries(immediate = true)
        }
    }

    suspend fun sendText(
        content: String,
        mentionedUserIds: List<String> = emptyList(),
        now: Long = System.currentTimeMillis()
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return
        }
        withContext(dispatcher) {
            val target = currentConversationTarget() ?: return@withContext
            target.sendText(repository, session.userId, trimmed, mentionedUserIds, now)
            refreshKeepingHistory()
        }
    }

    suspend fun renameGroup(name: String) {
        val targetId = mutableState.value.peerId
        if (!targetId.isGroupConversationId()) {
            return
        }
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            mutableState.value = mutableState.value.copy(errorMessage = "群名称不能为空")
            return
        }
        val validSession = validSessionProvider()
        val repository = groupRepository
        if (validSession == null || repository == null) {
            mutableState.value = mutableState.value.copy(errorMessage = "无法更新群名称")
            return
        }
        withContext(dispatcher) {
            when (val result = repository.renameGroup(validSession.accessToken, targetId.removePrefix("group:"), trimmedName)) {
                is GroupResult.Success -> {
                    mutableState.value = mutableState.value.copy(
                        peerName = result.group.name,
                        peerAvatarUrl = result.group.avatarUrl,
                        errorMessage = null
                    )
                }
                is GroupResult.Failure -> {
                    mutableState.value = mutableState.value.copy(errorMessage = result.message)
                }
            }
        }
    }

    suspend fun sendImage(selectedImage: SelectedChatImage, now: Long = System.currentTimeMillis()) {
        if (mutableState.value.peerId.isBlank()) {
            return
        }
        withContext(dispatcher) {
            val target = currentConversationTarget() ?: return@withContext
            val localMessage = target.createLocalImageMessage(repository, session.userId, selectedImage, now)
            refreshKeepingHistory()

            uploadImageAndQueueSend(localMessage.messageId, selectedImage)
        }
    }

    suspend fun sendImages(selectedImages: List<SelectedChatImage>, now: Long = System.currentTimeMillis()) {
        if (mutableState.value.peerId.isBlank()) {
            return
        }
        val ordered = selectedImages
            .take(MAX_IMAGES_PER_SEND)
            .sortedBy { it.selectionOrder }
        if (ordered.isEmpty()) {
            return
        }
        val batchNow = now
        val target = currentConversationTarget() ?: return
        // Phase 1+2: serialize id allocation + batch insert on the IO dispatcher,
        // then trigger a single refresh so all N UPLOADING rows are visible at once.
        val inserted: List<ChatMessage> = withContext(dispatcher) {
            target.createLocalImageMessages(repository, session.userId, ordered, batchNow)
        }
        refreshKeepingHistory()
        // Phase 3: upload rows in parallel, but queue SEND_MESSAGE in selection
        // order so serverSeq assignment cannot scramble the user's chosen order.
        coroutineScope {
            val preparedUploads = ordered.mapIndexed { index, selectedImage ->
                async {
                    runCatching {
                        prepareImageUpload(inserted[index].messageId, selectedImage)
                    }.getOrNull()
                }
            }
            preparedUploads.forEachIndexed { index, prepared ->
                prepared.await()?.let { upload ->
                    completePreparedImageSend(
                        messageId = inserted[index].messageId,
                        selectedImage = ordered[index],
                        prepared = upload
                    )
                }
            }
        }
    }

    suspend fun retryImageMessage(messageId: String, now: Long = System.currentTimeMillis()) {
        withContext(dispatcher) {
            val message = repository.findMessageById(messageId) ?: return@withContext
            when (message.status) {
                MessageStatus.UPLOAD_FAILED -> {
                    val selectedImage = imageResolver.resolve(message)
                    if (selectedImage == null) {
                        mutableState.value = mutableState.value.copy(errorMessage = "Image retry failed: local image cache missing")
                        return@withContext
                    }
                    repository.markImageUploading(messageId, now)
                    refreshKeepingHistory()
                    uploadImageAndQueueSend(messageId, selectedImage)
                }
                MessageStatus.FAILED -> {
                    repository.requeueImageMessageSend(messageId, now)
                    mutableState.value = mutableState.value.copy(errorMessage = null)
                    refreshKeepingHistory()
                }
                MessageStatus.UPLOADING,
                MessageStatus.SENDING,
                MessageStatus.SENT,
                MessageStatus.RECEIVED -> Unit
            }
        }
    }

    suspend fun recallMessage(messageId: String, now: Long = System.currentTimeMillis()) {
        withContext(dispatcher) {
            val sent = repository.recallMessage(messageId, requesterId = session.userId, now = now)
            if (!sent) {
                mutableState.value = mutableState.value.copy(errorMessage = RECALL_FAILURE_MESSAGE)
            }
        }
    }

    fun clearErrorMessage() {
        mutableState.value = mutableState.value.copy(errorMessage = null)
    }

    private suspend fun uploadImageAndQueueSend(messageId: String, selectedImage: SelectedChatImage) {
        val prepared = prepareImageUpload(messageId, selectedImage) ?: return
        completePreparedImageSend(messageId, selectedImage, prepared)
    }

    private suspend fun prepareImageUpload(messageId: String, selectedImage: SelectedChatImage): PreparedImageUpload? {
        val validSession = validSessionProvider()
        if (validSession == null) {
            repository.markImageUploadFailed(messageId, System.currentTimeMillis())
            mutableState.value = mutableState.value.copy(
                errorMessage = "Image upload target request failed: Session expired"
            )
            refreshKeepingHistoryPreservingError()
            return null
        }

        val targets = imageUploadApi.requestUploadTargets(
            accessToken = validSession.accessToken,
            messageId = messageId,
            contentType = selectedImage.mimeType
        )
        when (targets) {
            is ImageUploadTargetsResult.Failure -> {
                repository.markImageUploadFailed(messageId, System.currentTimeMillis())
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Image upload target request failed: ${targets.message}"
                )
                refreshKeepingHistoryPreservingError()
                return null
            }
            is ImageUploadTargetsResult.Success -> Unit
        }

        val thumbnailUpload = imageUploadApi.upload(
            uploadUrl = targets.targets.thumbnail.uploadUrl,
            contentType = selectedImage.mimeType,
            bytes = selectedImage.thumbnailBytes
        )
        when (thumbnailUpload) {
            is AvatarPutResult.Failure -> {
                repository.markImageUploadFailed(messageId, System.currentTimeMillis())
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Image upload failed: ${thumbnailUpload.message}"
                )
                refreshKeepingHistoryPreservingError()
                return null
            }
            AvatarPutResult.Success -> Unit
        }

        val originalUpload = imageUploadApi.upload(
            uploadUrl = targets.targets.original.uploadUrl,
            contentType = selectedImage.mimeType,
            bytes = selectedImage.originalBytes
        )
        when (originalUpload) {
            is AvatarPutResult.Failure -> {
                repository.markImageUploadFailed(messageId, System.currentTimeMillis())
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Image upload failed: ${originalUpload.message}"
                )
                refreshKeepingHistoryPreservingError()
                return null
            }
            AvatarPutResult.Success -> Unit
        }

        return PreparedImageUpload(
            imageUrl = targets.targets.original.publicUrl,
            thumbnailUrl = targets.targets.thumbnail.publicUrl
        )
    }

    private fun completePreparedImageSend(
        messageId: String,
        selectedImage: SelectedChatImage,
        prepared: PreparedImageUpload
    ) {
        val sendPhaseNow = System.currentTimeMillis()
        repository.completeImageUploadAndQueueSend(
            messageId = messageId,
            imageUrl = prepared.imageUrl,
            thumbnailUrl = prepared.thumbnailUrl,
            imageWidth = selectedImage.width,
            imageHeight = selectedImage.height,
            mimeType = selectedImage.mimeType,
            fileSizeBytes = selectedImage.originalBytes.size.toLong(),
            now = sendPhaseNow
        )
        mutableState.value = mutableState.value.copy(errorMessage = null)
        refreshKeepingHistory()
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
                val page = historyPage(peerId, beforeTime = beforeTime, limit = HISTORY_PAGE_SIZE)
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
                    errorMessage = error.message ?: "加载历史消息失败"
                )
            }
        }
    }

    private fun refreshInitialPage() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            mutableState.value = mutableState.value.copy(
                messages = emptyList(),
                hasMoreLocal = false,
                latestOwnSentMessageId = null,
                groupReadCountForLatest = 0,
                groupReadersForLatest = emptyList()
            )
            return
        }
        val messages = historyPage(peerId, beforeTime = null, limit = HISTORY_PAGE_SIZE)
        mutableState.value = mutableState.value.copy(
            messages = messages,
            hasMoreLocal = messages.size == HISTORY_PAGE_SIZE,
            isHistoryMemoryLimitReached = false,
            errorMessage = null,
            peerReadUpToServerSeq = peerReadCursor(peerId),
            senderProfiles = cachedSenderProfiles(messages)
        )
        recomputeGroupReadIndicator()
    }

    private suspend fun refreshProfiles() {
        val currentSession = validSessionProvider() ?: session
        profileRepository.bootstrapSession(currentSession)
        val peerId = mutableState.value.peerId
        if (peerId.isGroupConversationId()) {
            val conversation = repository.conversation(peerId)
            val groupId = peerId.removePrefix("group:")
            val groupRepo = groupRepository
            val rawMentionMembers = if (groupRepo != null && currentSession.accessToken.isNotBlank()) {
                groupRepo.syncMembers(currentSession.accessToken, groupId)
            } else {
                groupRepo?.localMembers(groupId).orEmpty()
            }
            val senderIds = mutableState.value.messages
                .map { it.senderId }
                .filter { it.isNotBlank() }
                .distinct()
            val memberIds = rawMentionMembers.map { it.userId }
            val messageVersions = mutableState.value.messages
                .mapNotNull { message -> message.senderProfileVersion?.let { message.senderId to it } }
                .toMap()
            val memberVersions = rawMentionMembers
                .filter { it.profileVersion > 0L }
                .associate { it.userId to it.profileVersion }
            val remoteProfiles = if (currentSession.accessToken.isNotBlank()) {
                profileRepository.ensureProfiles(
                    accessToken = currentSession.accessToken,
                    userIds = senderIds + memberIds + currentSession.userId,
                    remoteVersions = messageVersions + memberVersions
                )
            } else {
                emptyList()
            }
            val profilesById = (remoteProfiles + memberIds.mapNotNull(profileRepository::localProfile))
                .distinctBy { it.userId }
                .associateBy { it.userId }
            val mentionMembers = profileRepository.backfillFromProfiles(rawMentionMembers, profilesById)
            val senderProfiles = (remoteProfiles + senderIds.mapNotNull(profileRepository::localProfile))
                .distinctBy { it.userId }
                .associateBy { it.userId }
            mutableState.value = mutableState.value.copy(
                peerName = conversation?.title ?: conversation?.peerName ?: peerId,
                peerAvatarUrl = conversation?.avatarUrl,
                currentUserAvatarUrl = profileRepository.localProfile(currentSession.userId)?.avatarUrl,
                senderProfiles = senderProfiles,
                mentionMembers = mentionMembers
            )
            recomputeGroupReadIndicator()
            return
        }
        if (peerId.isNotBlank()) {
            applyCachedPeerDisplay(peerId)
            val hadCachedPeerProfile = profileRepository.localProfile(peerId) != null
            val senderIds = mutableState.value.messages
                .map { it.senderId }
                .filter { it.isNotBlank() }
                .distinct()
            val messageVersions = mutableState.value.messages
                .mapNotNull { message -> message.senderProfileVersion?.let { message.senderId to it } }
                .toMap()
            val hasPeerVersionHint = peerId in messageVersions
            if (currentSession.accessToken.isNotBlank()) {
                profileRepository.ensureProfiles(
                    accessToken = currentSession.accessToken,
                    userIds = senderIds + currentSession.userId + peerId,
                    remoteVersions = messageVersions
                )
                if (!hasPeerVersionHint && hadCachedPeerProfile) {
                    profileRepository.refreshProfile(currentSession.accessToken, peerId)
                }
            }
        }
        val peerProfile = profileRepository.localProfile(peerId)
        val currentUserProfile = profileRepository.localProfile(currentSession.userId)
        val senderProfiles = listOf(peerProfile, currentUserProfile)
            .filterNotNull()
            .associateBy { it.userId }
        mutableState.value = mutableState.value.copy(
            peerName = peerProfile?.nickname ?: peerId,
            peerAvatarUrl = peerProfile?.avatarUrl,
            currentUserAvatarUrl = currentUserProfile?.avatarUrl,
            senderProfiles = senderProfiles,
            mentionMembers = emptyList()
        )
    }

    private fun refreshKeepingHistory() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            return
        }
        if (peerId.isGroupConversationId() && groupReadContextPeerId != peerId) {
            return
        }
        val currentMessages = mutableState.value.messages
        val limit = maxOf(HISTORY_PAGE_SIZE, currentMessages.size)
        val latestMessages = historyPage(peerId, beforeTime = null, limit = limit)
        mutableState.value = mutableState.value.copy(
            messages = mergeMessages(currentMessages, latestMessages),
            errorMessage = null,
            peerReadUpToServerSeq = peerReadCursor(peerId)
        )
        recomputeGroupReadIndicator()
    }

    private fun refreshKeepingHistoryPreservingError() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            return
        }
        if (peerId.isGroupConversationId() && groupReadContextPeerId != peerId) {
            return
        }
        val current = mutableState.value
        val limit = maxOf(HISTORY_PAGE_SIZE, current.messages.size)
        val latestMessages = historyPage(peerId, beforeTime = null, limit = limit)
        mutableState.value = current.copy(
            messages = mergeMessages(current.messages, latestMessages),
            peerReadUpToServerSeq = peerReadCursor(peerId)
        )
        recomputeGroupReadIndicator()
    }

    private suspend fun prepareGroupReadContextForFirstRender() {
        val targetId = mutableState.value.peerId
        startGroupReadObservation()
        if (!targetId.isGroupConversationId() || mutableState.value.peerId != targetId) {
            return
        }
        applyLocalGroupReadMembers(targetId)
        groupReadContextPeerId = targetId
        recomputeGroupReadIndicator()
    }

    private fun applyLocalGroupReadMembers(peerId: String) {
        if (!peerId.isGroupConversationId()) {
            mutableState.value = mutableState.value.copy(mentionMembers = emptyList())
            return
        }
        val groupRepo = groupRepository ?: return
        val groupId = peerId.removePrefix("group:")
        val localMembers = groupRepo.localMembers(groupId)
        if (localMembers.isEmpty()) {
            return
        }
        val profilesById = localMembers
            .mapNotNull { profileRepository.localProfile(it.userId) }
            .associateBy { it.userId }
        mutableState.value = mutableState.value.copy(
            mentionMembers = profileRepository.backfillFromProfiles(localMembers, profilesById)
        )
    }

    private suspend fun startGroupReadObservation() {
        groupReadObservationJob?.cancel()
        groupReadObservationJob = null
        val targetId = mutableState.value.peerId
        if (!targetId.isGroupConversationId()) {
            latestGroupReadCursors = emptyList()
            groupReadContextPeerId = null
            recomputeGroupReadIndicator(emptyList())
            return
        }
        val groupId = targetId.removePrefix("group:")
        latestGroupReadCursors = emptyList()
        groupReadContextPeerId = null
        val initialCursors = repository.observeGroupReadCursors(groupId).first()
        if (mutableState.value.peerId != targetId) {
            return
        }
        latestGroupReadCursors = initialCursors
        groupReadObservationJob = scope.launch(dispatcher) {
            repository.observeGroupReadCursors(groupId).collect { cursors ->
                if (mutableState.value.peerId == targetId) {
                    latestGroupReadCursors = cursors
                    recomputeGroupReadIndicator(cursors)
                }
            }
        }
        jobs += groupReadObservationJob!!
    }

    private fun sendGroupReadAckIfNeeded() {
        val peerId = mutableState.value.peerId
        if (!peerId.isGroupConversationId()) return
        repository.sendGroupReadAck(
            groupId = peerId.removePrefix("group:"),
            readerId = session.userId
        )
    }

    private fun recomputeGroupReadIndicator(cursors: List<com.buyansong.im.storage.GroupReadCursor>? = null) {
        val current = mutableState.value
        if (!current.peerId.isGroupConversationId()) {
            mutableState.value = current.copy(
                latestOwnSentMessageId = null,
                groupReadCountForLatest = 0,
                groupReadersForLatest = emptyList()
            )
            return
        }
        val latestOwnId = GroupReadReceiptPolicy.latestEligibleOwnSentMessageId(current.messages, session.userId)
        val latestOwn = current.messages.firstOrNull { it.messageId == latestOwnId }
        val readers = latestOwn?.let {
            GroupReadReceiptPolicy.readersOf(
                messageSenderId = it.senderId,
                messageServerSeq = it.serverSeq,
                cursors = cursors ?: latestGroupReadCursors,
                members = current.mentionMembers
            )
        }.orEmpty()
        mutableState.value = current.copy(
            latestOwnSentMessageId = latestOwnId,
            groupReadCountForLatest = readers.size,
            groupReadersForLatest = readers
        )
    }

    private fun scheduleMissingThumbnailRetries(immediate: Boolean) {
        val target = currentConversationTarget() ?: return
        target.missingIncomingImageThumbnails(
            repository = repository,
            limit = MISSING_THUMBNAIL_RETRY_BATCH_SIZE
        ).forEach { message ->
            if (thumbnailRetryJobs[message.messageId]?.isActive == true) {
                return@forEach
            }
            if ((thumbnailRetryCounts[message.messageId] ?: 0) >= THUMBNAIL_RETRY_DELAYS_MS.size) {
                return@forEach
            }
            thumbnailRetryJobs[message.messageId] = scope.launch(dispatcher) {
                retryMissingThumbnail(message.messageId, immediate)
            }
        }
    }

    private fun currentConversationTarget(): ConversationTarget? {
        val peerId = mutableState.value.peerId
        if (peerId.isBlank()) {
            return null
        }
        return if (peerId.isGroupConversationId()) {
            GroupConversationTarget(peerId)
        } else {
            SingleConversationTarget(
                peerId = peerId,
                conversationId = repository.conversationIdFor(session.userId, peerId)
            )
        }
    }

    private fun hydrateInitialStateFromCache(peerId: String) {
        if (peerId.isBlank()) {
            return
        }
        val conversationId = if (peerId.isGroupConversationId()) {
            peerId
        } else {
            repository.conversationIdFor(session.userId, peerId)
        }
        applyCachedPeerDisplay(peerId)
        applyCachedCurrentUserDisplay()
        val cachedMessages = repository.getCachedInitialPage(conversationId) ?: return
        val orderedMessages = MessageOrderingPolicy.sortOldestFirst(cachedMessages)
        mutableState.value = mutableState.value.copy(
            messages = orderedMessages,
            hasMoreLocal = orderedMessages.size == HISTORY_PAGE_SIZE,
            isHistoryMemoryLimitReached = false,
            errorMessage = null,
            peerReadUpToServerSeq = repository.conversationPeerReadCursorByConversationId(conversationId),
            senderProfiles = cachedSenderProfiles(orderedMessages)
        )
        recomputeGroupReadIndicator()
    }

    private fun applyCachedCurrentUserDisplay() {
        mutableState.value = mutableState.value.copy(
            currentUserAvatarUrl = cachedCurrentUserAvatarUrl()
        )
    }

    private fun cachedCurrentUserAvatarUrl(): String? {
        return profileRepository.localProfile(session.userId)?.avatarUrl
    }

    private fun cachedSenderProfiles(messages: List<ChatMessage>): Map<String, UserProfile> {
        val senderIds = messages
            .map { it.senderId }
            .filter { it.isNotBlank() }
            .distinct()
        if (senderIds.isEmpty()) {
            return emptyMap()
        }
        return profileRepository.localProfiles(senderIds).associateBy { it.userId }
    }

    private fun applyCachedPeerDisplay(peerId: String) {
        val (peerName, peerAvatarUrl) = cachedPeerDisplay(peerId)
        mutableState.value = mutableState.value.copy(
            peerName = peerName,
            peerAvatarUrl = peerAvatarUrl
        )
    }

    private fun cachedPeerDisplay(peerId: String): Pair<String, String?> {
        if (peerId.isGroupConversationId()) {
            val conversation = repository.conversation(peerId)
            return (conversation?.title ?: conversation?.peerName ?: peerId) to conversation?.avatarUrl
        }
        val peerProfile = profileRepository.localProfile(peerId)
        return (peerProfile?.nickname ?: peerId) to peerProfile?.avatarUrl
    }

    private suspend fun retryMissingThumbnail(messageId: String, immediate: Boolean) {
        try {
            while ((thumbnailRetryCounts[messageId] ?: 0) < THUMBNAIL_RETRY_DELAYS_MS.size) {
                val attemptIndex = thumbnailRetryCounts[messageId] ?: 0
                if (!immediate || attemptIndex > 0) {
                    delay(THUMBNAIL_RETRY_DELAYS_MS[attemptIndex])
                }
                thumbnailRetryCounts[messageId] = attemptIndex + 1
                if (repository.retryIncomingImageThumbnail(messageId)) {
                    thumbnailRetryCounts.remove(messageId)
                    refreshKeepingHistory()
                    return
                }
            }
        } finally {
            thumbnailRetryJobs.remove(messageId)
        }
    }

    private fun mergeMessages(current: List<ChatMessage>, incoming: List<ChatMessage>): List<ChatMessage> {
        return (current + incoming)
            .associateBy { it.messageId }
            .values
            .let { MessageOrderingPolicy.sortOldestFirst(it) }
            .take(MAX_RETAINED_MESSAGES)
    }

    private fun openCurrentConversation() {
        currentConversationTarget()?.openConversation(repository, session.userId)
    }

    private fun historyPage(peerId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
        val page = if (peerId == mutableState.value.peerId) {
            currentConversationTarget()?.historyPage(repository, beforeTime, limit).orEmpty()
        } else if (peerId.isGroupConversationId()) {
            GroupConversationTarget(peerId).historyPage(repository, beforeTime, limit)
        } else {
            SingleConversationTarget(
                peerId = peerId,
                conversationId = repository.conversationIdFor(session.userId, peerId)
            ).historyPage(repository, beforeTime, limit)
        }
        return MessageOrderingPolicy.sortOldestFirst(page)
    }

    private fun peerReadCursor(peerId: String): Long? {
        return if (peerId == mutableState.value.peerId) {
            currentConversationTarget()?.peerReadCursor(repository)
        } else if (peerId.isGroupConversationId()) {
            GroupConversationTarget(peerId).peerReadCursor(repository)
        } else {
            SingleConversationTarget(
                peerId = peerId,
                conversationId = repository.conversationIdFor(session.userId, peerId)
            ).peerReadCursor(repository)
        }
    }

    private fun String.isGroupConversationId(): Boolean = startsWith("group:")

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

    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_RETAINED_MESSAGES = 2_000
        const val MAX_IMAGES_PER_SEND = 9
        const val MISSING_THUMBNAIL_RETRY_BATCH_SIZE = 20
        const val RECALL_FAILURE_MESSAGE = "撤回失败，请重试"
        val THUMBNAIL_RETRY_DELAYS_MS = longArrayOf(2_000L, 10_000L, 30_000L)
    }

    private data class PreparedImageUpload(
        val imageUrl: String,
        val thumbnailUrl: String
    )
}
