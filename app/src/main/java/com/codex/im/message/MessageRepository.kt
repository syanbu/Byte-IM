package com.codex.im.message

import com.codex.im.MessagesTabUnreadBadgeSource
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.Conversation
import com.codex.im.storage.ConversationDao
import com.codex.im.storage.MessageDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.storage.PendingMessage
import com.codex.im.storage.PendingMessageDao
import com.codex.im.storage.TransactionRunner
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val pendingMessageDao: PendingMessageDao,
    private val connection: ImConnection,
    private val messageIdGenerator: MessageIdGenerator,
    private val seqGenerator: SeqGenerator,
    private val retryPolicy: MessageRetryPolicy = MessageRetryPolicy(),
    private val transactionRunner: TransactionRunner = TransactionRunner.immediate(),
    private val thumbnailCache: ChatThumbnailCache = NoopChatThumbnailCache
) : MessagesTabUnreadBadgeSource {
    @Volatile
    private var activeConversationId: String? = null
    @Volatile
    private var activeUserId: String? = null
    @Volatile
    private var activePeerId: String? = null
    private val lastSentReadCursorByConversation = mutableMapOf<String, Long>()
    private val mutableConversationUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    override val conversationUpdates: SharedFlow<Unit> = mutableConversationUpdates.asSharedFlow()

    fun sendText(senderId: String, receiverId: String, content: String, now: Long): ChatMessage {
        val conversationId = conversationIdFor(senderId, receiverId)
        val message = ChatMessage(
            messageId = messageIdGenerator.next(senderId, now),
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            clientSeq = seqGenerator.next(conversationId),
            serverSeq = null,
            content = content,
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = now,
            updatedAt = now
        )
        val packet = ImPacket(cmd = ImCommand.SEND_MESSAGE.value, body = message.toSendBody().toByteArray())
        transactionRunner.runInTransaction {
            messageDao.insertOrIgnore(message)
            conversationDao.upsertFromMessage(message, incrementUnread = false)
            pendingMessageDao.upsert(
                PendingMessage(
                    messageId = message.messageId,
                    packetCmd = packet.cmd,
                    packetBody = packet.body.decodeToString(),
                    retryCount = 0,
                    nextRetryAt = now + DEFAULT_ACK_TIMEOUT_MS,
                    createdAt = now
                )
            )
        }
        notifyConversationChanged()
        connection.send(packet)
        return message
    }

    fun createLocalImageMessage(
        senderId: String,
        receiverId: String,
        localOriginalPath: String,
        localThumbnailPath: String,
        imageWidth: Int,
        imageHeight: Int,
        mimeType: String,
        now: Long
    ): ChatMessage {
        val conversationId = conversationIdFor(senderId, receiverId)
        val message = ChatMessage(
            messageId = messageIdGenerator.next(senderId, now),
            conversationId = conversationId,
            senderId = senderId,
            receiverId = receiverId,
            clientSeq = seqGenerator.next(conversationId),
            serverSeq = null,
            content = IMAGE_PLACEHOLDER_CONTENT,
            status = MessageStatus.UPLOADING,
            direction = MessageDirection.OUTGOING,
            createdAt = now,
            updatedAt = now,
            type = MessageType.IMAGE,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mimeType = mimeType,
            localOriginalPath = localOriginalPath,
            localThumbnailPath = localThumbnailPath
        )
        transactionRunner.runInTransaction {
            messageDao.insertOrIgnore(message)
            conversationDao.upsertFromMessage(message, incrementUnread = false)
        }
        notifyConversationChanged()
        return message
    }

    fun completeImageUploadAndQueueSend(
        messageId: String,
        imageUrl: String,
        thumbnailUrl: String,
        imageWidth: Int,
        imageHeight: Int,
        mimeType: String,
        fileSizeBytes: Long,
        now: Long
    ) {
        val message = messageDao.findByMessageId(messageId) ?: error("Missing message $messageId")
        val packetMessage = message.copy(
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes,
            status = MessageStatus.SENDING,
            updatedAt = now
        )
        val packet = ImPacket(cmd = ImCommand.SEND_MESSAGE.value, body = packetMessage.toSendBody().toByteArray())
        transactionRunner.runInTransaction {
            messageDao.updateImageUploadResult(
                messageId = messageId,
                imageUrl = imageUrl,
                thumbnailUrl = thumbnailUrl,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                mimeType = mimeType,
                fileSizeBytes = fileSizeBytes,
                status = MessageStatus.SENDING,
                updatedAt = now
            )
            pendingMessageDao.upsert(
                PendingMessage(
                    messageId = messageId,
                    packetCmd = packet.cmd,
                    packetBody = packet.body.decodeToString(),
                    retryCount = 0,
                    nextRetryAt = now + DEFAULT_ACK_TIMEOUT_MS,
                    createdAt = packetMessage.createdAt
                )
            )
        }
        notifyConversationChanged()
        connection.send(packet)
    }

    fun requeueImageMessageSend(messageId: String, now: Long): Boolean {
        val message = messageDao.findByMessageId(messageId) ?: return false
        if (message.type != MessageType.IMAGE || message.imageUrl.isNullOrBlank() || message.thumbnailUrl.isNullOrBlank()) {
            return false
        }
        val packetMessage = message.copy(
            status = MessageStatus.SENDING,
            updatedAt = now
        )
        val packet = ImPacket(cmd = ImCommand.SEND_MESSAGE.value, body = packetMessage.toSendBody().toByteArray())
        transactionRunner.runInTransaction {
            messageDao.markStatus(messageId, MessageStatus.SENDING, now)
            pendingMessageDao.upsert(
                PendingMessage(
                    messageId = messageId,
                    packetCmd = packet.cmd,
                    packetBody = packet.body.decodeToString(),
                    retryCount = 0,
                    nextRetryAt = now + DEFAULT_ACK_TIMEOUT_MS,
                    createdAt = message.createdAt
                )
            )
        }
        notifyConversationChanged()
        connection.send(packet)
        return true
    }

    fun markImageUploadFailed(messageId: String, now: Long): Boolean {
        val changed = messageDao.markStatus(messageId, MessageStatus.UPLOAD_FAILED, now)
        if (changed) {
            notifyConversationChanged()
        }
        return changed
    }

    fun markImageUploading(messageId: String, now: Long): Boolean {
        val changed = messageDao.markStatus(messageId, MessageStatus.UPLOADING, now)
        if (changed) {
            notifyConversationChanged()
        }
        return changed
    }

    fun findMessageById(messageId: String): ChatMessage? {
        return messageDao.findByMessageId(messageId)
    }

    fun handlePacket(packet: ImPacket) {
        when (packet.cmd) {
            ImCommand.MESSAGE_ACK.value -> handleAck(packet.body.decodeToString())
            ImCommand.RECEIVE_MESSAGE.value -> handleIncoming(packet.body.decodeToString())
            ImCommand.READ_ACK.value -> handleReadAck(packet.body.decodeToString())
            ImCommand.RECALL_ACK.value -> handleRecallAck(packet.body.decodeToString())
            ImCommand.RECALL_NOTIFY.value -> handleRecallNotify(packet.body.decodeToString())
        }
    }

    fun messagesWith(userId: String, peerId: String, beforeTime: Long? = null, limit: Int = 50): List<ChatMessage> {
        return historyPage(userId, peerId, beforeTime, limit)
    }

    fun historyPage(userId: String, peerId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
        return messageDao.queryPage(conversationIdFor(userId, peerId), beforeTime, limit)
            .filter { it.isReadyForChatDisplay() }
    }

    fun missingIncomingImageThumbnails(userId: String, peerId: String, limit: Int): List<ChatMessage> {
        return messageDao.queryIncomingImagesMissingLocalThumbnail(
            conversationId = conversationIdFor(userId, peerId),
            limit = limit
        )
    }

    fun retryIncomingImageThumbnail(messageId: String): Boolean {
        val message = messageDao.findByMessageId(messageId) ?: return false
        return cacheIncomingThumbnailIfNeeded(inserted = true, message = message)
    }

    fun recentLocalThumbnailPaths(userId: String, peerId: String, limit: Int): List<String> {
        return messageDao.queryRecentImagesWithLocalThumbnail(
            conversationId = conversationIdFor(userId, peerId),
            limit = limit
        ).mapNotNull { it.localThumbnailPath }
    }

    fun conversations(limit: Int = 50) = conversationDao.listConversations(limit)

    fun createLocalGroupConversation(
        creatorUserId: String,
        memberUserIds: List<String>,
        now: Long = System.currentTimeMillis()
    ): Conversation {
        val normalizedMembers = memberUserIds
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != creatorUserId }
            .distinct()
            .sorted()
        require(normalizedMembers.isNotEmpty()) { "At least one member is required" }
        val conversationId = groupConversationIdFor(creatorUserId, normalizedMembers, now)
        val conversation = Conversation(
            conversationId = conversationId,
            peerId = conversationId,
            peerName = "群聊(${normalizedMembers.size + 1})",
            lastMessageId = null,
            lastMessagePreview = "已创建群聊",
            lastMessageTime = now,
            unreadCount = 0,
            updatedAt = now
        )
        conversationDao.upsertConversation(conversation)
        notifyConversationChanged()
        return conversation
    }

    fun conversationPeerReadCursor(userId: String, peerId: String): Long? {
        val conversationId = conversationIdFor(userId, peerId)
        return conversationDao.listConversations(limit = 100)
            .firstOrNull { it.conversationId == conversationId }
            ?.peerReadUpToServerSeq
    }

    override fun totalUnreadCount(): Int = conversationDao.totalUnreadCount()

    fun retryDuePendingMessages(now: Long, limit: Int = DEFAULT_RETRY_BATCH_SIZE) {
        val dueMessages = pendingMessageDao.dueMessages(now, limit)
        var changed = false
        dueMessages.forEach { pending ->
            val current = messageDao.findByMessageId(pending.messageId)
            if (current == null) {
                if (pendingMessageDao.delete(pending.messageId)) {
                    changed = true
                }
                return@forEach
            }
            if (current.status == MessageStatus.SENT && current.serverSeq != null) {
                if (pendingMessageDao.delete(pending.messageId)) {
                    changed = true
                }
                return@forEach
            }
            if (retryPolicy.isExhausted(pending.retryCount)) {
                if (messageDao.markFailed(pending.messageId, now)) {
                    changed = true
                }
                if (pendingMessageDao.delete(pending.messageId)) {
                    changed = true
                }
                return@forEach
            }

            val retryAttempt = pending.retryCount + 1
            connection.send(
                ImPacket(
                    cmd = pending.packetCmd,
                    body = pending.packetBody.toByteArray()
                )
            )
            val latest = messageDao.findByMessageId(pending.messageId)
            if (latest?.status == MessageStatus.SENT && latest.serverSeq != null) {
                if (pendingMessageDao.delete(pending.messageId)) {
                    changed = true
                }
                return@forEach
            }
            if (pendingMessageDao.findByMessageId(pending.messageId) == null) {
                return@forEach
            }
            pendingMessageDao.upsert(
                pending.copy(
                    retryCount = retryAttempt,
                    nextRetryAt = now + retryPolicy.nextDelayMillis(retryAttempt)
                )
            )
            changed = true
        }
        if (changed) {
            notifyConversationChanged()
        }
    }

    fun openConversation(currentUserId: String, peerId: String, now: Long = System.currentTimeMillis()): String {
        val conversationId = conversationIdFor(currentUserId, peerId)
        activeConversationId = conversationId
        activeUserId = currentUserId
        activePeerId = peerId
        conversationDao.clearUnread(conversationId)
        sendReadAckIfAdvanced(
            conversationId = conversationId,
            readerId = currentUserId,
            peerId = peerId,
            readAt = now
        )
        notifyConversationChanged()
        return conversationId
    }

    fun closeConversation() {
        activeConversationId = null
        activeUserId = null
        activePeerId = null
    }

    fun recallMessage(messageId: String, requesterId: String, now: Long): Boolean {
        val message = messageDao.findByMessageId(messageId) ?: return false
        if (message.senderId != requesterId || message.serverSeq == null || message.isRecalled) {
            return false
        }
        return connection.send(
            ImPacket(
                cmd = ImCommand.RECALL_MESSAGE.value,
                body = """
                    {
                      "messageId":"${message.messageId.escapeJson()}",
                      "conversationId":"${message.conversationId.escapeJson()}",
                      "requesterId":"${requesterId.escapeJson()}",
                      "requestAt":$now
                    }
                """.trimIndent().replace(Regex("\\s+"), "")
                    .toByteArray()
            )
        )
    }

    fun conversationIdFor(firstUserId: String, secondUserId: String): String {
        val participants = listOf(firstUserId, secondUserId).sorted()
        return "single:${participants[0]}:${participants[1]}"
    }

    private fun groupConversationIdFor(creatorUserId: String, memberUserIds: List<String>, now: Long): String {
        val memberHash = memberUserIds.joinToString(",").hashCode().toUInt().toString(16)
        return "group:$creatorUserId:$now:$memberHash"
    }

    private fun handleAck(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        val messageId = body.requiredString("messageId")
        val serverSeq = body.requiredLong("serverSeq")
        val serverTime = body.optionalLong("serverTime") ?: System.currentTimeMillis()
        messageDao.markAcked(messageId, serverSeq, serverTime)
        pendingMessageDao.delete(messageId)
        notifyConversationChanged()
    }

    private fun handleIncoming(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        val timestamp = body.optionalLong("timestamp") ?: System.currentTimeMillis()
        val senderId = body.requiredString("senderId")
        val receiverId = body.requiredString("receiverId")
        val serverSeq = body.optionalLong("serverSeq")
        val type = body.optionalString("type")
            ?.takeIf { it.isNotBlank() }
            ?.let { MessageType.valueOf(it) }
            ?: MessageType.TEXT
        val imagePayload = body.optionalObject("image")
        val message = ChatMessage(
            messageId = body.requiredString("messageId"),
            conversationId = conversationIdFor(senderId, receiverId),
            senderId = senderId,
            receiverId = receiverId,
            clientSeq = body.optionalLong("clientSeq") ?: 0L,
            serverSeq = serverSeq,
            content = body.requiredString("content"),
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = timestamp,
            updatedAt = timestamp,
            type = type,
            imageUrl = imagePayload?.optionalString("imageUrl"),
            thumbnailUrl = imagePayload?.optionalString("thumbnailUrl"),
            imageWidth = imagePayload?.optionalInt("width"),
            imageHeight = imagePayload?.optionalInt("height"),
            mimeType = imagePayload?.optionalString("mimeType"),
            fileSizeBytes = imagePayload?.optionalLong("sizeBytes")
        )
        val inserted = messageDao.insertOrIgnore(message)
        if (inserted) {
            conversationDao.upsertFromMessage(
                message = message,
                incrementUnread = message.conversationId != activeConversationId
            )
            notifyConversationChanged()
        }
        if (serverSeq != null) {
            connection.send(
                ImPacket(
                    cmd = ImCommand.DELIVERY_ACK.value,
                    body = """
                        {
                          "messageId":"${message.messageId.escapeJson()}",
                          "conversationId":"${message.conversationId.escapeJson()}",
                          "serverSeq":$serverSeq,
                          "receiverId":"${receiverId.escapeJson()}"
                        }
                    """.trimIndent().replace(Regex("\\s+"), "")
                        .toByteArray()
                )
            )
        }
        if (message.conversationId == activeConversationId && serverSeq != null) {
            val readerId = activeUserId ?: receiverId
            val peerId = activePeerId ?: senderId
            sendReadAckIfAdvanced(message.conversationId, readerId, peerId, timestamp)
        }
        cacheIncomingThumbnailIfNeeded(inserted, message)
    }

    private fun handleReadAck(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        val conversationId = body.requiredString("conversationId")
        val readUpToServerSeq = body.requiredLong("readUpToServerSeq")
        val readAt = body.optionalLong("readAt") ?: System.currentTimeMillis()
        val changed = conversationDao.updatePeerReadCursor(conversationId, readUpToServerSeq, readAt)
        if (changed) {
            notifyConversationChanged()
        }
    }

    private fun handleRecallAck(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        if (body.optionalBoolean("success") == false) {
            notifyConversationChanged()
            return
        }
        markMessageRecalled(
            messageId = body.requiredString("messageId"),
            recalledBy = body.requiredString("recalledBy"),
            recalledAt = body.optionalLong("recalledAt") ?: System.currentTimeMillis()
        )
    }

    private fun handleRecallNotify(json: String) {
        val body = JsonParser.parseString(json).asJsonObject
        markMessageRecalled(
            messageId = body.requiredString("messageId"),
            recalledBy = body.requiredString("recalledBy"),
            recalledAt = body.optionalLong("recalledAt") ?: System.currentTimeMillis()
        )
    }

    private fun markMessageRecalled(messageId: String, recalledBy: String, recalledAt: Long) {
        val message = messageDao.findByMessageId(messageId) ?: return
        val preview = if (message.senderId == recalledBy && message.direction == MessageDirection.OUTGOING) {
            "你撤回了一条消息"
        } else {
            "对方撤回了一条消息"
        }
        val changed = messageDao.markRecalled(messageId, recalledBy, recalledAt)
        val previewChanged = conversationDao.updatePreviewForRecalledMessage(messageId, preview, recalledAt)
        if (changed || previewChanged) {
            notifyConversationChanged()
        }
    }

    private fun sendReadAckIfAdvanced(conversationId: String, readerId: String, peerId: String, readAt: Long) {
        val readUpToServerSeq = messageDao.maxIncomingServerSeq(conversationId) ?: return
        val previous = lastSentReadCursorByConversation[conversationId]
        if (previous != null && readUpToServerSeq <= previous) {
            return
        }
        lastSentReadCursorByConversation[conversationId] = readUpToServerSeq
        connection.send(
            ImPacket(
                cmd = ImCommand.READ_ACK.value,
                body = """
                    {
                      "conversationId":"${conversationId.escapeJson()}",
                      "readerId":"${readerId.escapeJson()}",
                      "peerId":"${peerId.escapeJson()}",
                      "readUpToServerSeq":$readUpToServerSeq,
                      "readAt":$readAt
                    }
                """.trimIndent().replace(Regex("\\s+"), "")
                    .toByteArray()
            )
        )
    }

    private fun cacheIncomingThumbnailIfNeeded(inserted: Boolean, message: ChatMessage): Boolean {
        if (!inserted || message.type != MessageType.IMAGE || message.localThumbnailPath != null) {
            return false
        }
        val thumbnailUrl = message.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return false
        val localPath = thumbnailCache.cacheThumbnail(message.messageId, thumbnailUrl) ?: return false
        val changed = messageDao.updateLocalThumbnailPath(message.messageId, localPath, System.currentTimeMillis())
        if (changed) {
            notifyConversationChanged()
        }
        return changed
    }

    private fun ChatMessage.isReadyForChatDisplay(): Boolean {
        return direction != MessageDirection.INCOMING ||
            type != MessageType.IMAGE ||
            localThumbnailPath != null
    }

    private fun notifyConversationChanged() {
        mutableConversationUpdates.tryEmit(Unit)
    }

    private fun ChatMessage.toSendBody(): String {
        if (type == MessageType.IMAGE) {
            return """
                {
                  "messageId":"${messageId.escapeJson()}",
                  "conversationId":"${conversationId.escapeJson()}",
                  "senderId":"${senderId.escapeJson()}",
                  "receiverId":"${receiverId.escapeJson()}",
                  "clientSeq":$clientSeq,
                  "type":"IMAGE",
                  "content":"${content.escapeJson()}",
                  "image":{
                    "imageUrl":"${imageUrl.orEmpty().escapeJson()}",
                    "thumbnailUrl":"${thumbnailUrl.orEmpty().escapeJson()}",
                    "width":${imageWidth ?: 0},
                    "height":${imageHeight ?: 0},
                    "mimeType":"${mimeType.orEmpty().escapeJson()}",
                    "sizeBytes":${fileSizeBytes ?: 0}
                  },
                  "timestamp":$createdAt
                }
            """.trimIndent()
        }
        return """
            {
              "messageId":"${messageId.escapeJson()}",
              "conversationId":"${conversationId.escapeJson()}",
              "senderId":"${senderId.escapeJson()}",
              "receiverId":"${receiverId.escapeJson()}",
              "clientSeq":$clientSeq,
              "content":"${content.escapeJson()}",
              "timestamp":$createdAt
            }
        """.trimIndent()
    }

    private fun JsonObject.requiredString(name: String): String {
        return get(name)?.asString ?: error("Missing $name")
    }

    private fun JsonObject.requiredLong(name: String): Long {
        return get(name)?.asLong ?: error("Missing $name")
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        return get(name)?.asLong
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        return get(name)?.asInt
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else value.asString
    }

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else value.asBoolean
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        const val DEFAULT_ACK_TIMEOUT_MS = 5_000L
        const val DEFAULT_RETRY_BATCH_SIZE = 50
        const val IMAGE_PLACEHOLDER_CONTENT = "[图片]"
    }
}
