package com.buyansong.im.message

import com.buyansong.im.protocol.ImEnvelopeCodec
import com.buyansong.im.protocol.ProtocolException
import com.buyansong.im.protocol.v2.ChatMessagePayload
import com.buyansong.im.protocol.v2.ImEnvelope
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import com.buyansong.im.protocol.v2.ConversationType as ProtoConversationType
import com.buyansong.im.protocol.v2.MessageType as ProtoMessageType

class MessageProtoMapperTest {

    @Test
    fun singleTextChatMessageToSendEnvelope() {
        val message = ChatMessage(
            messageId = "m-1",
            conversationId = "single:alice:bob",
            senderId = "alice",
            receiverId = "bob",
            serverSeq = null,
            content = "hello",
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = 1000L,
            updatedAt = 1000L
        )

        val envelope = MessageProtoMapper.sendEnvelope(message)

        assertEquals(ImEnvelopeCodec.PROTOCOL_VERSION, envelope.protocolVersion)
        assertEquals(ImEnvelope.PayloadCase.SEND_MESSAGE, envelope.payloadCase)
        val payload = envelope.sendMessage.message
        assertEquals("m-1", payload.messageId)
        assertEquals("single:alice:bob", payload.conversationId)
        assertEquals(ProtoConversationType.CONVERSATION_TYPE_SINGLE, payload.conversationType)
        assertEquals("alice", payload.senderId)
        assertEquals("bob", payload.receiverId)
        assertEquals(ProtoMessageType.MESSAGE_TYPE_TEXT, payload.messageType)
        assertEquals("hello", payload.content)
        assertEquals(1000L, payload.clientTime)
        assertEquals(false, payload.hasServerSeq())
        assertEquals(false, payload.hasImage())
    }

    @Test
    fun groupTextWithMentionsToSendEnvelope() {
        val message = ChatMessage(
            messageId = "m-2",
            conversationId = "group:g-1",
            senderId = "alice",
            receiverId = "g-1",
            serverSeq = null,
            content = "hi @bob",
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = 2000L,
            updatedAt = 2000L,
            conversationType = ConversationType.GROUP,
            groupId = "g-1",
            mentionedUserIds = listOf("bob", "carol", "bob")
        )

        val payload = MessageProtoMapper.sendEnvelope(message).sendMessage.message

        assertEquals(ProtoConversationType.CONVERSATION_TYPE_GROUP, payload.conversationType)
        assertEquals("g-1", payload.groupId)
        assertEquals("hi @bob", payload.content)
        assertEquals(listOf("bob", "carol", "bob"), payload.mentionedUserIdsList)
    }

    @Test
    fun singleImageMetadataToSendEnvelope() {
        val message = ChatMessage(
            messageId = "m-3",
            conversationId = "single:alice:bob",
            senderId = "alice",
            receiverId = "bob",
            serverSeq = null,
            content = "[图片]",
            status = MessageStatus.SENDING,
            direction = MessageDirection.OUTGOING,
            createdAt = 3000L,
            updatedAt = 3000L,
            type = MessageType.IMAGE,
            imageUrl = "https://oss.example/full.jpg",
            thumbnailUrl = "https://oss.example/thumb.jpg",
            imageWidth = 800,
            imageHeight = 600,
            mimeType = "image/jpeg",
            fileSizeBytes = 123456L
        )

        val payload = MessageProtoMapper.sendEnvelope(message).sendMessage.message

        assertEquals(ProtoMessageType.MESSAGE_TYPE_IMAGE, payload.messageType)
        assertEquals(true, payload.hasImage())
        val image = payload.image
        assertEquals("https://oss.example/full.jpg", image.imageUrl)
        assertEquals("https://oss.example/thumb.jpg", image.thumbnailUrl)
        assertEquals(800, image.width)
        assertEquals(600, image.height)
        assertEquals("image/jpeg", image.mimeType)
        assertEquals(123456L, image.sizeBytes)
    }

    @Test
    fun incomingPayloadToChatMessageParity() {
        val payload = ChatMessagePayload.newBuilder()
            .setMessageId("m-4")
            .setConversationId("group:g-9")
            .setConversationType(ProtoConversationType.CONVERSATION_TYPE_GROUP)
            .setGroupId("g-9")
            .setGroupName("周末群")
            .setSenderId("carol")
            .setReceiverId("alice")
            .setServerSeq(4100L)
            .setMessageType(ProtoMessageType.MESSAGE_TYPE_IMAGE)
            .setContent("[图片]")
            .setImage(
                com.buyansong.im.protocol.v2.ImagePayload.newBuilder()
                    .setImageUrl("https://oss.example/full.png")
                    .setThumbnailUrl("https://oss.example/thumb.png")
                    .setWidth(1024)
                    .setHeight(768)
                    .setMimeType("image/png")
                    .setSizeBytes(654321L)
            )
            .addMentionedUserIds("alice")
            .addMentionedUserIds("alice")
            .setClientTime(4000L)
            .setServerTime(4001L)
            .setSenderProfileVersion(12L)
            .build()

        val message = MessageProtoMapper.incomingMessage(payload)

        assertEquals("m-4", message.messageId)
        assertEquals("group:g-9", message.conversationId)
        assertEquals(ConversationType.GROUP, message.conversationType)
        assertEquals("g-9", message.groupId)
        assertEquals("周末群", message.groupName)
        assertEquals("carol", message.senderId)
        assertEquals("alice", message.receiverId)
        assertEquals(4100L, message.serverSeq)
        assertEquals(MessageType.IMAGE, message.type)
        assertEquals(MessageStatus.RECEIVED, message.status)
        assertEquals(MessageDirection.INCOMING, message.direction)
        assertEquals("https://oss.example/full.png", message.imageUrl)
        assertEquals("https://oss.example/thumb.png", message.thumbnailUrl)
        assertEquals(1024, message.imageWidth)
        assertEquals(768, message.imageHeight)
        assertEquals("image/png", message.mimeType)
        assertEquals(654321L, message.fileSizeBytes)
        assertEquals(listOf("alice"), message.mentionedUserIds)
        assertEquals(4000L, message.createdAt)
        assertEquals(12L, message.senderProfileVersion)
    }

    @Test
    fun incomingSinglePayloadDerivesConversationId() {
        val payload = ChatMessagePayload.newBuilder()
            .setMessageId("m-5")
            .setConversationId("single:bob:alice")
            .setConversationType(ProtoConversationType.CONVERSATION_TYPE_SINGLE)
            .setSenderId("bob")
            .setReceiverId("alice")
            .setMessageType(ProtoMessageType.MESSAGE_TYPE_TEXT)
            .setContent("hey")
            .setClientTime(5000L)
            .build()

        val message = MessageProtoMapper.incomingMessage(payload)

        assertEquals("single:alice:bob", message.conversationId)
    }

    @Test
    fun pendingJsonSnapshotToSendEnvelope() {
        val snapshot = """
            {
              "messageId":"m-6",
              "conversationId":"group:g-2",
              "conversationType":"GROUP",
              "groupId":"g-2",
              "senderId":"alice",
              "receiverId":"g-2",
              "type":"TEXT",
              "content":"queued while offline",
              "mentionedUserIds":["bob","bob","carol"],
              "timestamp":6000
            }
        """.trimIndent()

        val envelope = MessageProtoMapper.sendEnvelopeFromPendingJson(snapshot)

        assertEquals(ImEnvelope.PayloadCase.SEND_MESSAGE, envelope.payloadCase)
        val payload = envelope.sendMessage.message
        assertEquals("m-6", payload.messageId)
        assertEquals(ProtoConversationType.CONVERSATION_TYPE_GROUP, payload.conversationType)
        assertEquals("g-2", payload.groupId)
        assertEquals("alice", payload.senderId)
        assertEquals("queued while offline", payload.content)
        assertEquals(listOf("bob", "carol"), payload.mentionedUserIdsList)
        assertEquals(6000L, payload.clientTime)
    }

    @Test
    fun pendingJsonSnapshotWithImageToSendEnvelope() {
        val snapshot = """
            {
              "messageId":"m-7",
              "conversationId":"single:alice:bob",
              "senderId":"alice",
              "receiverId":"bob",
              "type":"IMAGE",
              "content":"[图片]",
              "image":{
                "imageUrl":"https://oss.example/full.webp",
                "thumbnailUrl":"https://oss.example/thumb.webp",
                "width":640,
                "height":480,
                "mimeType":"image/webp",
                "sizeBytes":43210
              },
              "timestamp":7000
            }
        """.trimIndent()

        val payload = MessageProtoMapper.sendEnvelopeFromPendingJson(snapshot).sendMessage.message

        assertEquals(ProtoMessageType.MESSAGE_TYPE_IMAGE, payload.messageType)
        assertEquals("https://oss.example/full.webp", payload.image.imageUrl)
        assertEquals("https://oss.example/thumb.webp", payload.image.thumbnailUrl)
        assertEquals(640, payload.image.width)
        assertEquals(480, payload.image.height)
        assertEquals("image/webp", payload.image.mimeType)
        assertEquals(43210L, payload.image.sizeBytes)
    }

    @Test
    fun pendingJsonWithUnknownTypeRejected() {
        val snapshot = """
            {
              "messageId":"m-8",
              "conversationId":"single:alice:bob",
              "senderId":"alice",
              "receiverId":"bob",
              "type":"VIDEO",
              "content":"x",
              "timestamp":8000
            }
        """.trimIndent()

        assertThrows(ProtocolException::class.java) {
            MessageProtoMapper.sendEnvelopeFromPendingJson(snapshot)
        }
    }

    @Test
    fun pendingJsonWithUnknownConversationTypeRejected() {
        val snapshot = """
            {
              "messageId":"m-9",
              "conversationId":"channel:c-1",
              "conversationType":"CHANNEL",
              "senderId":"alice",
              "receiverId":"c-1",
              "content":"x",
              "timestamp":8000
            }
        """.trimIndent()

        assertThrows(ProtocolException::class.java) {
            MessageProtoMapper.sendEnvelopeFromPendingJson(snapshot)
        }
    }

    @Test
    fun incomingPayloadWithUnspecifiedEnumsRejected() {
        val unspecifiedConversation = ChatMessagePayload.newBuilder()
            .setMessageId("m-10")
            .setConversationId("single:alice:bob")
            .setSenderId("alice")
            .setReceiverId("bob")
            .setMessageType(ProtoMessageType.MESSAGE_TYPE_TEXT)
            .setContent("x")
            .build()

        assertThrows(ProtocolException::class.java) {
            MessageProtoMapper.incomingMessage(unspecifiedConversation)
        }

        val unspecifiedType = ChatMessagePayload.newBuilder()
            .setMessageId("m-11")
            .setConversationId("single:alice:bob")
            .setConversationType(ProtoConversationType.CONVERSATION_TYPE_SINGLE)
            .setSenderId("alice")
            .setReceiverId("bob")
            .setContent("x")
            .build()

        assertThrows(ProtocolException::class.java) {
            MessageProtoMapper.incomingMessage(unspecifiedType)
        }
    }

    @Test
    fun typedAckEnvelopeBuilders() {
        val deliveryAck = MessageProtoMapper.deliveryAckEnvelope(
            messageId = "m-12",
            conversationId = "single:alice:bob",
            serverSeq = 42L,
            receiverId = "bob"
        )
        assertEquals(ImEnvelope.PayloadCase.DELIVERY_ACK, deliveryAck.payloadCase)
        assertEquals(42L, deliveryAck.deliveryAck.serverSeq)
        assertEquals("bob", deliveryAck.deliveryAck.receiverId)

        val readAck = MessageProtoMapper.readAckEnvelope(
            conversationId = "single:alice:bob",
            conversationType = ConversationType.SINGLE,
            readerId = "bob",
            peerId = "alice",
            readUpToServerSeq = 42L,
            readAt = 9000L
        )
        assertEquals(ImEnvelope.PayloadCase.READ_ACK, readAck.payloadCase)
        assertEquals(ProtoConversationType.CONVERSATION_TYPE_SINGLE, readAck.readAck.conversationType)
        assertEquals(42L, readAck.readAck.readUpToServerSeq)

        val recall = MessageProtoMapper.recallMessageEnvelope(
            messageId = "m-12",
            conversationId = "single:alice:bob",
            requesterId = "alice"
        )
        assertEquals(ImEnvelope.PayloadCase.RECALL_MESSAGE, recall.payloadCase)
        assertEquals("alice", recall.recallMessage.requesterId)

        val recallNotifyAck = MessageProtoMapper.recallNotifyAckEnvelope(
            messageId = "m-12",
            conversationId = "single:alice:bob",
            receiverId = "bob",
            recalledAt = 9100L
        )
        assertEquals(ImEnvelope.PayloadCase.RECALL_NOTIFY_ACK, recallNotifyAck.payloadCase)
        assertEquals(9100L, recallNotifyAck.recallNotifyAck.recalledAt)
    }
}
