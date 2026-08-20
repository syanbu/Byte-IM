package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.ImagePayload;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.MessageAck;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.imserver.protocol.ImEnvelopeCodec;
import com.buyansong.imserver.protocol.ProtocolException;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class MessageProtoMapperTest {

    @Test
    public void heartbeatAckEnvelopeCarriesReconcileFields() {
        ImEnvelope envelope = MessageProtoMapper.heartbeatAckEnvelope(5000L, List.of("m-1", "m-2"));

        assertEquals(ImEnvelope.PayloadCase.HEARTBEAT_ACK, envelope.getPayloadCase());
        assertEquals(ImEnvelopeCodec.PROTOCOL_VERSION, envelope.getProtocolVersion());
        assertEquals(5000L, envelope.getHeartbeatAck().getServerTime());
        assertEquals(List.of("m-1", "m-2"), envelope.getHeartbeatAck().getReceivedMessageIdsList());
    }

    @Test
    public void authAckAndAuthNackEnvelopes() {
        ImEnvelope ack = MessageProtoMapper.authAckEnvelope("alice", 5001L);
        assertEquals(ImEnvelope.PayloadCase.AUTH_ACK, ack.getPayloadCase());
        assertEquals("alice", ack.getAuthAck().getUserId());
        assertEquals(5001L, ack.getAuthAck().getServerTime());

        ImEnvelope nack = MessageProtoMapper.authNackEnvelope(
                com.buyansong.im.protocol.v2.AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_EXPIRED);
        assertEquals(ImEnvelope.PayloadCase.AUTH_NACK, nack.getPayloadCase());
        assertEquals(com.buyansong.im.protocol.v2.AuthFailureReason.AUTH_FAILURE_REASON_TOKEN_EXPIRED,
                nack.getAuthNack().getReason());
    }

    @Test
    public void messageAckAndReceiveMessageEnvelopes() {
        MessageAck ack = MessageAck.newBuilder()
                .setMessageId("m-1")
                .setConversationId("single:alice:bob")
                .setServerSeq(1001L)
                .setServerTime(5002L)
                .build();

        ImEnvelope ackEnvelope = MessageProtoMapper.messageAckEnvelope(ack);
        assertEquals(ImEnvelope.PayloadCase.MESSAGE_ACK, ackEnvelope.getPayloadCase());
        assertEquals(ack, ackEnvelope.getMessageAck());

        ImEnvelope receiveEnvelope = MessageProtoMapper.receiveMessageEnvelope(textPayload().build());
        assertEquals(ImEnvelope.PayloadCase.RECEIVE_MESSAGE, receiveEnvelope.getPayloadCase());
        assertEquals("m-1", receiveEnvelope.getReceiveMessage().getMessage().getMessageId());
    }

    @Test
    public void textMessageStoredJsonRoundTrip() {
        ChatMessagePayload payload = textPayload()
                .setServerSeq(1001L)
                .setServerTime(5003L)
                .setSenderProfileVersion(9L)
                .build();

        ChatMessagePayload restored = MessageProtoMapper.storedJsonToMessage(
                MessageProtoMapper.messageToStoredJson(payload));

        assertEquals(payload, restored);
    }

    @Test
    public void imageMessageStoredJsonRoundTrip() {
        ChatMessagePayload payload = textPayload()
                .setMessageType(MessageType.MESSAGE_TYPE_IMAGE)
                .setContent("[图片]")
                .setImage(ImagePayload.newBuilder()
                        .setImageUrl("https://oss.example/full.jpg")
                        .setThumbnailUrl("https://oss.example/thumb.jpg")
                        .setWidth(800)
                        .setHeight(600)
                        .setMimeType("image/jpeg")
                        .setSizeBytes(123456L))
                .build();

        ChatMessagePayload restored = MessageProtoMapper.storedJsonToMessage(
                MessageProtoMapper.messageToStoredJson(payload));

        assertEquals(payload, restored);
        assertEquals(800, restored.getImage().getWidth());
        assertEquals(600, restored.getImage().getHeight());
        assertEquals("image/jpeg", restored.getImage().getMimeType());
        assertEquals(123456L, restored.getImage().getSizeBytes());
    }

    @Test
    public void groupMentionMessageStoredJsonRoundTrip() {
        ChatMessagePayload payload = textPayload()
                .setConversationId("group:g-1")
                .setConversationType(ConversationType.CONVERSATION_TYPE_GROUP)
                .setGroupId("g-1")
                .setGroupName("周末群")
                .setReceiverId("bob")
                .addMentionedUserIds("bob")
                .addMentionedUserIds("carol")
                .build();

        ChatMessagePayload restored = MessageProtoMapper.storedJsonToMessage(
                MessageProtoMapper.messageToStoredJson(payload));

        assertEquals(payload, restored);
        assertEquals(List.of("bob", "carol"), restored.getMentionedUserIdsList());
        assertEquals("周末群", restored.getGroupName());
    }

    @Test
    public void legacyStoredJsonWithoutOptionalFieldsParses() {
        JsonObject legacy = new JsonObject();
        legacy.addProperty("messageId", "m-legacy");
        legacy.addProperty("conversationId", "single:alice:bob");
        legacy.addProperty("senderId", "alice");
        legacy.addProperty("receiverId", "bob");
        legacy.addProperty("content", "old row");
        legacy.addProperty("timestamp", 4000L);
        legacy.addProperty("serverSeq", 1000L);
        legacy.addProperty("serverTime", 4001L);

        ChatMessagePayload restored = MessageProtoMapper.storedJsonToMessage(legacy);

        assertEquals(ConversationType.CONVERSATION_TYPE_SINGLE, restored.getConversationType());
        assertEquals(MessageType.MESSAGE_TYPE_TEXT, restored.getMessageType());
        assertEquals("old row", restored.getContent());
        assertEquals(4000L, restored.getClientTime());
        assertEquals(1000L, restored.getServerSeq());
        assertFalse(restored.hasSenderProfileVersion());
    }

    @Test
    public void storedJsonWithUnknownEnumsRejected() {
        JsonObject badType = new JsonObject();
        badType.addProperty("messageId", "m-x");
        badType.addProperty("conversationId", "single:a:b");
        badType.addProperty("senderId", "a");
        badType.addProperty("receiverId", "b");
        badType.addProperty("type", "VIDEO");

        assertThrows(ProtocolException.class, () -> MessageProtoMapper.storedJsonToMessage(badType));

        JsonObject badConversation = new JsonObject();
        badConversation.addProperty("messageId", "m-y");
        badConversation.addProperty("conversationId", "channel:c");
        badConversation.addProperty("conversationType", "CHANNEL");
        badConversation.addProperty("senderId", "a");
        badConversation.addProperty("receiverId", "b");

        assertThrows(ProtocolException.class, () -> MessageProtoMapper.storedJsonToMessage(badConversation));
    }

    @Test
    public void messageToStoredJsonRejectsUnspecifiedEnums() {
        ChatMessagePayload payload = ChatMessagePayload.newBuilder()
                .setMessageId("m-z")
                .setConversationId("single:a:b")
                .setSenderId("a")
                .setReceiverId("b")
                .build();

        assertThrows(ProtocolException.class, () -> MessageProtoMapper.messageToStoredJson(payload));
    }

    @Test
    public void ackStoredJsonRoundTrip() {
        MessageAck ack = MessageAck.newBuilder()
                .setMessageId("m-ack")
                .setConversationId("single:alice:bob")
                .setServerSeq(1002L)
                .setServerTime(5004L)
                .build();

        assertEquals(ack, MessageProtoMapper.storedJsonToAck(MessageProtoMapper.ackToStoredJson(ack)));
    }

    @Test
    public void readAckAndRecallEnvelopes() {
        ImEnvelope readAck = MessageProtoMapper.readAckEnvelope(
                com.buyansong.im.protocol.v2.ReadAck.newBuilder()
                        .setConversationId("group:g-1")
                        .setConversationType(ConversationType.CONVERSATION_TYPE_GROUP)
                        .setReaderId("bob")
                        .setReadUpToServerSeq(1003L)
                        .setReadAt(5005L)
                        .build());
        assertEquals(ImEnvelope.PayloadCase.READ_ACK, readAck.getPayloadCase());

        ImEnvelope recallAck = MessageProtoMapper.recallAckEnvelope(
                com.buyansong.im.protocol.v2.RecallAck.newBuilder()
                        .setMessageId("m-1")
                        .setConversationId("single:alice:bob")
                        .setSuccess(true)
                        .setRecalledBy("alice")
                        .setRecalledAt(5006L)
                        .build());
        assertEquals(ImEnvelope.PayloadCase.RECALL_ACK, recallAck.getPayloadCase());
        assertTrue(recallAck.getRecallAck().getSuccess());

        ImEnvelope recallNotify = MessageProtoMapper.recallNotifyEnvelope(
                com.buyansong.im.protocol.v2.RecallNotify.newBuilder()
                        .setMessageId("m-1")
                        .setConversationId("single:alice:bob")
                        .setRecalledBy("alice")
                        .setRecalledAt(5006L)
                        .build());
        assertEquals(ImEnvelope.PayloadCase.RECALL_NOTIFY, recallNotify.getPayloadCase());
    }

    private static ChatMessagePayload.Builder textPayload() {
        return ChatMessagePayload.newBuilder()
                .setMessageId("m-1")
                .setConversationId("single:alice:bob")
                .setConversationType(ConversationType.CONVERSATION_TYPE_SINGLE)
                .setSenderId("alice")
                .setReceiverId("bob")
                .setMessageType(MessageType.MESSAGE_TYPE_TEXT)
                .setContent("hello")
                .setClientTime(5000L);
    }
}
