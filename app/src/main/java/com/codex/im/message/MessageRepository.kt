package com.codex.im.message

import com.codex.im.MessagesTabUnreadBadgeSource
import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.ChatMessage
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
    private val transactionRunner: TransactionRunner = TransactionRunner.immediate()
) : MessagesTabUnreadBadgeSource {
    @Volatile
    private var activeConversationId: String? = null
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

    fun markImageUploadFailed(messageId: String, now: Long): Boolean {
        val changed = messageDao.markStatus(messageId, MessageStatus.UPLOAD_FAILED, now)
        if (changed) {
            notifyConversationChanged()
        }
        return changed
    }

    fun handlePacket(packet: ImPacket) {
        when (packet.cmd) {
            ImCommand.MESSAGE_ACK.value -> handleAck(packet.body.decodeToString())
            ImCommand.RECEIVE_MESSAGE.value -> handleIncoming(packet.body.decodeToString())
        }
    }

    fun messagesWith(userId: String, peerId: String, beforeTime: Long? = null, limit: Int = 50): List<ChatMessage> {
        return historyPage(userId, peerId, beforeTime, limit)
    }

    fun historyPage(userId: String, peerId: String, beforeTime: Long?, limit: Int): List<ChatMessage> {
        return messageDao.queryPage(conversationIdFor(userId, peerId), beforeTime, limit)
    }

    fun conversations(limit: Int = 50) = conversationDao.listConversations(limit)

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

    fun openConversation(currentUserId: String, peerId: String): String {
        val conversationId = conversationIdFor(currentUserId, peerId)
        activeConversationId = conversationId
        conversationDao.clearUnread(conversationId)
        notifyConversationChanged()
        return conversationId
    }

    fun closeConversation() {
        activeConversationId = null
    }

    fun conversationIdFor(firstUserId: String, secondUserId: String): String {
        val participants = listOf(firstUserId, secondUserId).sorted()
        return "single:${participants[0]}:${participants[1]}"
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
