package com.codex.im.message

import com.codex.im.connection.ImConnection
import com.codex.im.protocol.ImCommand
import com.codex.im.protocol.ImPacket
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.ConversationDao
import com.codex.im.storage.MessageDao
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.PendingMessage
import com.codex.im.storage.PendingMessageDao
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
    private val seqGenerator: SeqGenerator
) {
    @Volatile
    private var activeConversationId: String? = null
    private val mutableConversationUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val conversationUpdates: SharedFlow<Unit> = mutableConversationUpdates.asSharedFlow()

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
        messageDao.insertOrIgnore(message)
        conversationDao.upsertFromMessage(message, incrementUnread = false)
        notifyConversationChanged()

        val packet = ImPacket(cmd = ImCommand.SEND_MESSAGE.value, body = message.toSendBody().toByteArray())
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
        connection.send(packet)
        return message
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
        val message = ChatMessage(
            messageId = body.requiredString("messageId"),
            conversationId = conversationIdFor(senderId, receiverId),
            senderId = senderId,
            receiverId = receiverId,
            clientSeq = body.optionalLong("clientSeq") ?: 0L,
            serverSeq = body.optionalLong("serverSeq"),
            content = body.requiredString("content"),
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = timestamp,
            updatedAt = timestamp
        )
        val inserted = messageDao.insertOrIgnore(message)
        if (inserted) {
            conversationDao.upsertFromMessage(
                message = message,
                incrementUnread = message.conversationId != activeConversationId
            )
            notifyConversationChanged()
        }
    }

    private fun notifyConversationChanged() {
        mutableConversationUpdates.tryEmit(Unit)
    }

    private fun ChatMessage.toSendBody(): String {
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

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        const val DEFAULT_ACK_TIMEOUT_MS = 5_000L
    }
}
