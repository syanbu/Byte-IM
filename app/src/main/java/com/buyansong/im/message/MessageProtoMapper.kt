package com.buyansong.im.message

import com.buyansong.im.protocol.ImEnvelopeCodec
import com.buyansong.im.protocol.ProtocolException
import com.buyansong.im.protocol.v2.ChatMessagePayload
import com.buyansong.im.protocol.v2.DeliveryAck
import com.buyansong.im.protocol.v2.ImagePayload
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.protocol.v2.ReadAck
import com.buyansong.im.protocol.v2.RecallMessage
import com.buyansong.im.protocol.v2.RecallNotifyAck
import com.buyansong.im.protocol.v2.SendMessage
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.buyansong.im.protocol.v2.ConversationType as ProtoConversationType
import com.buyansong.im.protocol.v2.MessageType as ProtoMessageType

/**
 * Maps between the storage [ChatMessage] domain model and the generated
 * Protobuf payloads of protocol version 2.
 *
 * This is additive beside the legacy JSON wire protocol: nothing here is on
 * an active transport path until the Task 3 cutover. The only JSON left in
 * this mapper is [sendEnvelopeFromPendingJson], the compatibility reader for
 * durable `pending_messages.packet_body` outbox snapshots.
 */
object MessageProtoMapper {

    fun sendEnvelope(message: ChatMessage): ImEnvelope {
        return envelope {
            setSendMessage(SendMessage.newBuilder().setMessage(toPayload(message)))
        }
    }

    fun incomingMessage(payload: ChatMessagePayload, now: Long = System.currentTimeMillis()): ChatMessage {
        val conversationType = payload.conversationType.toDomain()
        val type = payload.messageType.toDomain()
        val conversationId = when (conversationType) {
            ConversationType.GROUP -> payload.conversationId
            ConversationType.SINGLE -> conversationIdFor(payload.senderId, payload.receiverId)
        }
        val timestamp = when {
            payload.clientTime != 0L -> payload.clientTime
            payload.hasServerTime() -> payload.serverTime
            else -> now
        }
        val image = if (payload.hasImage()) payload.image else null
        return ChatMessage(
            messageId = payload.messageId,
            conversationId = conversationId,
            senderId = payload.senderId,
            receiverId = payload.receiverId,
            clientSeq = payload.clientSeq,
            serverSeq = if (payload.hasServerSeq()) payload.serverSeq else null,
            content = payload.content,
            status = MessageStatus.RECEIVED,
            direction = MessageDirection.INCOMING,
            createdAt = timestamp,
            updatedAt = timestamp,
            type = type,
            imageUrl = image?.imageUrl,
            thumbnailUrl = image?.thumbnailUrl,
            imageWidth = image?.width,
            imageHeight = image?.height,
            mimeType = image?.mimeType,
            fileSizeBytes = image?.sizeBytes,
            conversationType = conversationType,
            groupId = payload.groupId.ifBlank { null },
            groupName = payload.groupName.ifBlank { null },
            mentionedUserIds = payload.mentionedUserIdsList
                .filter { it.isNotBlank() }
                .distinct(),
            senderProfileVersion = if (payload.hasSenderProfileVersion()) payload.senderProfileVersion else null
        )
    }

    fun deliveryAckEnvelope(
        messageId: String,
        conversationId: String,
        serverSeq: Long,
        receiverId: String
    ): ImEnvelope {
        return envelope {
            setDeliveryAck(
                DeliveryAck.newBuilder()
                    .setMessageId(messageId)
                    .setConversationId(conversationId)
                    .setServerSeq(serverSeq)
                    .setReceiverId(receiverId)
            )
        }
    }

    fun readAckEnvelope(
        conversationId: String,
        conversationType: ConversationType,
        readerId: String,
        peerId: String,
        readUpToServerSeq: Long,
        readAt: Long
    ): ImEnvelope {
        return envelope {
            setReadAck(
                ReadAck.newBuilder()
                    .setConversationId(conversationId)
                    .setConversationType(conversationType.toProto())
                    .setReaderId(readerId)
                    .setPeerId(peerId)
                    .setReadUpToServerSeq(readUpToServerSeq)
                    .setReadAt(readAt)
            )
        }
    }

    fun recallMessageEnvelope(messageId: String, conversationId: String, requesterId: String): ImEnvelope {
        return envelope {
            setRecallMessage(
                RecallMessage.newBuilder()
                    .setMessageId(messageId)
                    .setConversationId(conversationId)
                    .setRequesterId(requesterId)
            )
        }
    }

    fun recallNotifyAckEnvelope(
        messageId: String,
        conversationId: String,
        receiverId: String,
        recalledAt: Long
    ): ImEnvelope {
        return envelope {
            setRecallNotifyAck(
                RecallNotifyAck.newBuilder()
                    .setMessageId(messageId)
                    .setConversationId(conversationId)
                    .setReceiverId(receiverId)
                    .setRecalledAt(recalledAt)
            )
        }
    }

    /**
     * Rebuilds a SEND_MESSAGE envelope from a durable outbox snapshot stored
     * in `pending_messages.packet_body`. This JSON is an internal storage
     * format (the legacy `ChatMessage.toSendBody()` shape), never a wire
     * format; it exists so rows queued before the cutover stay retryable.
     */
    fun sendEnvelopeFromPendingJson(json: String): ImEnvelope {
        val body = try {
            JsonParser.parseString(json).asJsonObject
        } catch (error: RuntimeException) {
            throw ProtocolException("Malformed pending message snapshot: ${error.message}")
        }
        val conversationType = body.optionalString("conversationType")
            ?.takeIf { it.isNotBlank() }
            ?.let { parseConversationType(it) }
            ?: ConversationType.SINGLE
        val type = body.optionalString("type")
            ?.takeIf { it.isNotBlank() }
            ?.let { parseMessageType(it) }
            ?: MessageType.TEXT
        val timestamp = body.optionalLong("timestamp") ?: 0L
        val image = body.optionalObject("image")
        val message = ChatMessage(
            messageId = body.requiredString("messageId"),
            conversationId = body.requiredString("conversationId"),
            senderId = body.requiredString("senderId"),
            receiverId = body.requiredString("receiverId"),
            clientSeq = body.optionalLong("clientSeq") ?: 0L,
            serverSeq = null,
            content = body.optionalString("content") ?: "",
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = timestamp,
            updatedAt = timestamp,
            type = type,
            imageUrl = image?.optionalString("imageUrl"),
            thumbnailUrl = image?.optionalString("thumbnailUrl"),
            imageWidth = image?.optionalInt("width"),
            imageHeight = image?.optionalInt("height"),
            mimeType = image?.optionalString("mimeType"),
            fileSizeBytes = image?.optionalLong("sizeBytes"),
            conversationType = conversationType,
            groupId = body.optionalString("groupId"),
            mentionedUserIds = body.optionalStringArray("mentionedUserIds")
        )
        return sendEnvelope(message)
    }

    private fun toPayload(message: ChatMessage): ChatMessagePayload {
        val builder = ChatMessagePayload.newBuilder()
            .setMessageId(message.messageId)
            .setConversationId(message.conversationId)
            .setConversationType(message.conversationType.toProto())
            .setSenderId(message.senderId)
            .setReceiverId(message.receiverId)
            .setClientSeq(message.clientSeq)
            .setMessageType(message.type.toProto())
            .setContent(message.content)
            .setClientTime(message.createdAt)
        message.groupId?.let { builder.setGroupId(it) }
        message.groupName?.let { builder.setGroupName(it) }
        message.serverSeq?.let { builder.setServerSeq(it) }
        message.senderProfileVersion?.let { builder.setSenderProfileVersion(it) }
        builder.addAllMentionedUserIds(message.mentionedUserIds)
        if (message.type == MessageType.IMAGE) {
            builder.setImage(
                ImagePayload.newBuilder()
                    .setImageUrl(message.imageUrl.orEmpty())
                    .setThumbnailUrl(message.thumbnailUrl.orEmpty())
                    .setWidth(message.imageWidth ?: 0)
                    .setHeight(message.imageHeight ?: 0)
                    .setMimeType(message.mimeType.orEmpty())
                    .setSizeBytes(message.fileSizeBytes ?: 0L)
            )
        }
        return builder.build()
    }

    private fun conversationIdFor(firstUserId: String, secondUserId: String): String {
        val participants = listOf(firstUserId, secondUserId).sorted()
        return "single:${participants[0]}:${participants[1]}"
    }

    private fun ConversationType.toProto(): ProtoConversationType {
        return when (this) {
            ConversationType.SINGLE -> ProtoConversationType.CONVERSATION_TYPE_SINGLE
            ConversationType.GROUP -> ProtoConversationType.CONVERSATION_TYPE_GROUP
        }
    }

    private fun ProtoConversationType.toDomain(): ConversationType {
        return when (this) {
            ProtoConversationType.CONVERSATION_TYPE_SINGLE -> ConversationType.SINGLE
            ProtoConversationType.CONVERSATION_TYPE_GROUP -> ConversationType.GROUP
            ProtoConversationType.CONVERSATION_TYPE_UNSPECIFIED,
            ProtoConversationType.UNRECOGNIZED ->
                throw ProtocolException("Unsupported conversation type: $this")
        }
    }

    private fun MessageType.toProto(): ProtoMessageType {
        return when (this) {
            MessageType.TEXT -> ProtoMessageType.MESSAGE_TYPE_TEXT
            MessageType.IMAGE -> ProtoMessageType.MESSAGE_TYPE_IMAGE
        }
    }

    private fun ProtoMessageType.toDomain(): MessageType {
        return when (this) {
            ProtoMessageType.MESSAGE_TYPE_TEXT -> MessageType.TEXT
            ProtoMessageType.MESSAGE_TYPE_IMAGE -> MessageType.IMAGE
            ProtoMessageType.MESSAGE_TYPE_UNSPECIFIED,
            ProtoMessageType.UNRECOGNIZED ->
                throw ProtocolException("Unsupported message type: $this")
        }
    }

    private fun parseConversationType(value: String): ConversationType {
        return when (value) {
            "SINGLE" -> ConversationType.SINGLE
            "GROUP" -> ConversationType.GROUP
            else -> throw ProtocolException("Unsupported conversation type: $value")
        }
    }

    private fun parseMessageType(value: String): MessageType {
        return when (value) {
            "TEXT" -> MessageType.TEXT
            "IMAGE" -> MessageType.IMAGE
            else -> throw ProtocolException("Unsupported message type: $value")
        }
    }

    private fun envelope(build: ImEnvelope.Builder.() -> Unit): ImEnvelope {
        return ImEnvelope.newBuilder()
            .setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION)
            .apply(build)
            .build()
    }

    private fun JsonObject.requiredString(name: String): String {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asString
            ?: throw ProtocolException("Pending message snapshot missing $name")
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonNull) null else value.asString
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asLong
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        return get(name)?.takeIf { it.isJsonPrimitive }?.asInt
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.optionalStringArray(name: String): List<String> {
        val value = get(name) ?: return emptyList()
        if (!value.isJsonArray) {
            return emptyList()
        }
        return value.asJsonArray
            .mapNotNull { element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
            }
            .filter { it.isNotBlank() }
            .distinct()
    }
}
