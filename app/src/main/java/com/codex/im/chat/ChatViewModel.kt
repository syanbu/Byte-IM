package com.codex.im.chat

import com.codex.im.auth.AuthSession
import com.codex.im.auth.ValidSessionProvider
import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import com.codex.im.message.DisabledImageUploadApi
import com.codex.im.message.ImageUploadApi
import com.codex.im.message.ImageUploadTargetsResult
import com.codex.im.message.MessageRepository
import com.codex.im.message.SelectedChatImageResolver
import com.codex.im.message.SelectedChatImage
import com.codex.im.message.ChatImageCompressor
import com.codex.im.profile.ProfileRepository
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageOrderingPolicy
import com.codex.im.profile.AvatarPutResult
import com.codex.im.storage.MessageStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val peerReadUpToServerSeq: Long? = null
)

class ChatViewModel(
    private val session: AuthSession,
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val profileRepository: ProfileRepository,
    initialPeerId: String = "",
    private val imageUploadApi: ImageUploadApi = DisabledImageUploadApi,
    private val imageResolver: SelectedChatImageResolver = ChatImageCompressor,
    private val validSessionProvider: ValidSessionProvider = { session },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableState = MutableStateFlow(ChatUiState(peerId = initialPeerId))
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    val currentUserId: String = session.userId
    private var started = false
    private val jobs = mutableListOf<Job>()
    private val thumbnailRetryCounts = mutableMapOf<String, Int>()
    private val thumbnailRetryJobs = mutableMapOf<String, Job>()

    fun start() {
        if (started) {
            return
        }
        started = true
        if (mutableState.value.peerId.isNotBlank()) {
            repository.openConversation(session.userId, mutableState.value.peerId)
        }
        connectIfNeeded()
        jobs += scope.launch(dispatcher) {
            repository.conversationUpdates.collect {
                refreshKeepingHistory()
                scheduleMissingThumbnailRetries(immediate = false)
            }
        }
        jobs += scope.launch(dispatcher) {
            refreshProfiles()
            refreshInitialPage()
            scheduleMissingThumbnailRetries(immediate = true)
        }
    }

    fun stop() {
        if (!started) {
            return
        }
        jobs.forEach { it.cancel() }
        jobs.clear()
        thumbnailRetryJobs.values.forEach { it.cancel() }
        thumbnailRetryJobs.clear()
        thumbnailRetryCounts.clear()
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
            errorMessage = null,
            peerReadUpToServerSeq = null
        )
        scope.launch(dispatcher) {
            if (trimmedPeerId.isNotEmpty()) {
                repository.openConversation(session.userId, trimmedPeerId)
            }
            refreshProfiles()
            refreshInitialPage()
            scheduleMissingThumbnailRetries(immediate = true)
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

    suspend fun sendImage(selectedImage: SelectedChatImage, now: Long = System.currentTimeMillis()) {
        if (mutableState.value.peerId.isBlank()) {
            return
        }
        withContext(dispatcher) {
            val localMessage = repository.createLocalImageMessage(
                senderId = session.userId,
                receiverId = mutableState.value.peerId,
                localOriginalPath = selectedImage.localOriginalPath,
                localThumbnailPath = selectedImage.localThumbnailPath,
                imageWidth = selectedImage.width,
                imageHeight = selectedImage.height,
                mimeType = selectedImage.mimeType,
                now = now
            )
            refreshKeepingHistory()

            uploadImageAndQueueSend(localMessage.messageId, selectedImage)
        }
    }

    suspend fun sendImages(selectedImages: List<SelectedChatImage>, now: Long = System.currentTimeMillis()) {
        selectedImages
            .take(MAX_IMAGES_PER_SEND)
            .forEachIndexed { index, selectedImage ->
                sendImage(selectedImage, now + index)
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
                mutableState.value = mutableState.value.copy(errorMessage = "消息撤回失败")
            }
        }
    }

    private suspend fun uploadImageAndQueueSend(messageId: String, selectedImage: SelectedChatImage) {
            val validSession = validSessionProvider()
            if (validSession == null) {
                repository.markImageUploadFailed(messageId, System.currentTimeMillis())
                mutableState.value = mutableState.value.copy(
                    errorMessage = "Image upload target request failed: Session expired"
                )
                refreshKeepingHistoryPreservingError()
                return
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
                    return
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
                    return
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
                    return
                }
                AvatarPutResult.Success -> Unit
            }

            val sendPhaseNow = System.currentTimeMillis()
            repository.completeImageUploadAndQueueSend(
                messageId = messageId,
                imageUrl = targets.targets.original.publicUrl,
                thumbnailUrl = targets.targets.thumbnail.publicUrl,
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
            errorMessage = null,
            peerReadUpToServerSeq = repository.conversationPeerReadCursor(session.userId, peerId)
        )
    }

    private suspend fun refreshProfiles() {
        val currentSession = validSessionProvider() ?: session
        profileRepository.bootstrapSession(currentSession)
        val peerId = mutableState.value.peerId
        if (peerId.isNotBlank()) {
            profileRepository.refreshProfiles(currentSession.accessToken, listOf(currentSession.userId, peerId))
        }
        val peerProfile = profileRepository.localProfile(peerId)
        val currentUserProfile = profileRepository.localProfile(currentSession.userId)
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
            errorMessage = null,
            peerReadUpToServerSeq = repository.conversationPeerReadCursor(session.userId, peerId)
        )
    }

    private fun refreshKeepingHistoryPreservingError() {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            return
        }
        val current = mutableState.value
        val limit = maxOf(HISTORY_PAGE_SIZE, current.messages.size)
        val latestMessages = repository.historyPage(
            userId = session.userId,
            peerId = peerId,
            beforeTime = null,
            limit = limit
        )
        mutableState.value = current.copy(
            messages = mergeMessages(current.messages, latestMessages),
            peerReadUpToServerSeq = repository.conversationPeerReadCursor(session.userId, peerId)
        )
    }

    private fun scheduleMissingThumbnailRetries(immediate: Boolean) {
        val peerId = mutableState.value.peerId
        if (peerId.isEmpty()) {
            return
        }
        repository.missingIncomingImageThumbnails(
            userId = session.userId,
            peerId = peerId,
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
            .let { MessageOrderingPolicy.sortNewestFirst(it) }
            .take(MAX_RETAINED_MESSAGES)
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

    private companion object {
        const val HISTORY_PAGE_SIZE = 20
        const val MAX_RETAINED_MESSAGES = 2_000
        const val MAX_IMAGES_PER_SEND = 9
        const val MISSING_THUMBNAIL_RETRY_BATCH_SIZE = 20
        val THUMBNAIL_RETRY_DELAYS_MS = longArrayOf(2_000L, 10_000L, 30_000L)
    }
}
