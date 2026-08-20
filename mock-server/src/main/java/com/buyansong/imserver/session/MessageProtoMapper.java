package com.buyansong.imserver.session;

import com.buyansong.im.protocol.v2.AuthAck;
import com.buyansong.im.protocol.v2.AuthNack;
import com.buyansong.im.protocol.v2.ChatMessagePayload;
import com.buyansong.im.protocol.v2.ConversationType;
import com.buyansong.im.protocol.v2.HeartbeatAck;
import com.buyansong.im.protocol.v2.ImagePayload;
import com.buyansong.im.protocol.v2.ImEnvelope;
import com.buyansong.im.protocol.v2.MessageAck;
import com.buyansong.im.protocol.v2.MessageType;
import com.buyansong.im.protocol.v2.ReadAck;
import com.buyansong.im.protocol.v2.RecallAck;
import com.buyansong.im.protocol.v2.RecallNotify;
import com.buyansong.im.protocol.v2.ReceiveMessage;
import com.buyansong.imserver.protocol.ImEnvelopeCodec;
import com.buyansong.imserver.protocol.ProtocolException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Builds typed protocol-v2 envelopes on the server and converts
 * {@link ChatMessagePayload} / {@link MessageAck} to and from the existing
 * accepted-message SQLite JSON columns.
 *
 * Additive beside the legacy JSON wire protocol: nothing here is on an
 * active transport path until the Task 5 cutover. The Gson conversion
 * methods are scoped to the accepted-message persistence adapter only; the
 * stored JSON keeps the legacy camelCase key layout so rows written before
 * the cutover stay readable.
 */
public final class MessageProtoMapper {

    private MessageProtoMapper() {
    }

    public static ImEnvelope authAckEnvelope(String userId, long serverTime) {
        return envelopeBuilder()
                .setAuthAck(AuthAck.newBuilder()
                        .setUserId(userId)
                        .setServerTime(serverTime))
                .build();
    }

    public static ImEnvelope authNackEnvelope(com.buyansong.im.protocol.v2.AuthFailureReason reason) {
        return envelopeBuilder()
                .setAuthNack(AuthNack.newBuilder().setReason(reason))
                .build();
    }

    public static ImEnvelope heartbeatAckEnvelope(long serverTime, List<String> receivedMessageIds) {
        HeartbeatAck.Builder ack = HeartbeatAck.newBuilder().setServerTime(serverTime);
        if (receivedMessageIds != null) {
            ack.addAllReceivedMessageIds(receivedMessageIds);
        }
        return envelopeBuilder().setHeartbeatAck(ack).build();
    }

    public static ImEnvelope messageAckEnvelope(MessageAck ack) {
        return envelopeBuilder().setMessageAck(ack).build();
    }

    public static ImEnvelope receiveMessageEnvelope(ChatMessagePayload message) {
        return envelopeBuilder()
                .setReceiveMessage(ReceiveMessage.newBuilder().setMessage(message))
                .build();
    }

    public static ImEnvelope readAckEnvelope(ReadAck ack) {
        return envelopeBuilder().setReadAck(ack).build();
    }

    public static ImEnvelope recallAckEnvelope(RecallAck ack) {
        return envelopeBuilder().setRecallAck(ack).build();
    }

    public static ImEnvelope recallNotifyEnvelope(RecallNotify notify) {
        return envelopeBuilder().setRecallNotify(notify).build();
    }

    /**
     * Serializes a message payload into the accepted-message store JSON
     * layout (legacy camelCase keys, e.g. {@code conversationType=GROUP},
     * {@code type=IMAGE}, {@code timestamp} for the client send time).
     */
    public static JsonObject messageToStoredJson(ChatMessagePayload message) {
        JsonObject json = new JsonObject();
        json.addProperty("messageId", message.getMessageId());
        json.addProperty("conversationId", message.getConversationId());
        json.addProperty("conversationType", conversationTypeName(message.getConversationType()));
        if (!message.getGroupId().isEmpty()) {
            json.addProperty("groupId", message.getGroupId());
        }
        if (!message.getGroupName().isEmpty()) {
            json.addProperty("groupName", message.getGroupName());
        }
        json.addProperty("senderId", message.getSenderId());
        json.addProperty("receiverId", message.getReceiverId());
        json.addProperty("type", messageTypeName(message.getMessageType()));
        json.addProperty("content", message.getContent());
        if (message.hasImage()) {
            ImagePayload image = message.getImage();
            JsonObject imageJson = new JsonObject();
            imageJson.addProperty("imageUrl", image.getImageUrl());
            imageJson.addProperty("thumbnailUrl", image.getThumbnailUrl());
            imageJson.addProperty("width", image.getWidth());
            imageJson.addProperty("height", image.getHeight());
            imageJson.addProperty("mimeType", image.getMimeType());
            imageJson.addProperty("sizeBytes", image.getSizeBytes());
            json.add("image", imageJson);
        }
        if (message.getMentionedUserIdsCount() > 0) {
            JsonArray mentions = new JsonArray();
            for (String mentionedUserId : message.getMentionedUserIdsList()) {
                mentions.add(mentionedUserId);
            }
            json.add("mentionedUserIds", mentions);
        }
        json.addProperty("timestamp", message.getClientTime());
        if (message.hasServerSeq()) {
            json.addProperty("serverSeq", message.getServerSeq());
        }
        if (message.hasServerTime()) {
            json.addProperty("serverTime", message.getServerTime());
        }
        if (message.hasSenderProfileVersion()) {
            json.addProperty("senderProfileVersion", message.getSenderProfileVersion());
        }
        return json;
    }

    /**
     * Inverse of {@link #messageToStoredJson}; tolerates rows written by the
     * legacy router (missing {@code conversationType} defaults to SINGLE,
     * missing {@code type} defaults to TEXT) but rejects unknown enum names.
     */
    public static ChatMessagePayload storedJsonToMessage(JsonObject json) {
        ChatMessagePayload.Builder message = ChatMessagePayload.newBuilder()
                .setMessageId(requiredString(json, "messageId"))
                .setConversationId(requiredString(json, "conversationId"))
                .setConversationType(parseConversationType(optionalString(json, "conversationType", "SINGLE")))
                .setSenderId(requiredString(json, "senderId"))
                .setReceiverId(requiredString(json, "receiverId"))
                .setMessageType(parseMessageType(optionalString(json, "type", "TEXT")))
                .setContent(optionalString(json, "content", ""))
                .setClientTime(optionalLong(json, "timestamp", 0L));
        String groupId = optionalString(json, "groupId", null);
        if (groupId != null) {
            message.setGroupId(groupId);
        }
        String groupName = optionalString(json, "groupName", null);
        if (groupName != null) {
            message.setGroupName(groupName);
        }
        JsonElement imageElement = json.get("image");
        if (imageElement != null && imageElement.isJsonObject()) {
            JsonObject imageJson = imageElement.getAsJsonObject();
            message.setImage(ImagePayload.newBuilder()
                    .setImageUrl(optionalString(imageJson, "imageUrl", ""))
                    .setThumbnailUrl(optionalString(imageJson, "thumbnailUrl", ""))
                    .setWidth((int) optionalLong(imageJson, "width", 0L))
                    .setHeight((int) optionalLong(imageJson, "height", 0L))
                    .setMimeType(optionalString(imageJson, "mimeType", ""))
                    .setSizeBytes(optionalLong(imageJson, "sizeBytes", 0L)));
        }
        JsonElement mentionsElement = json.get("mentionedUserIds");
        if (mentionsElement != null && mentionsElement.isJsonArray()) {
            for (JsonElement mention : mentionsElement.getAsJsonArray()) {
                if (mention.isJsonPrimitive() && mention.getAsJsonPrimitive().isString()) {
                    message.addMentionedUserIds(mention.getAsString());
                }
            }
        }
        if (json.has("serverSeq") && !json.get("serverSeq").isJsonNull()) {
            message.setServerSeq(json.get("serverSeq").getAsLong());
        }
        if (json.has("serverTime") && !json.get("serverTime").isJsonNull()) {
            message.setServerTime(json.get("serverTime").getAsLong());
        }
        if (json.has("senderProfileVersion") && !json.get("senderProfileVersion").isJsonNull()) {
            message.setSenderProfileVersion(json.get("senderProfileVersion").getAsLong());
        }
        return message.build();
    }

    public static JsonObject ackToStoredJson(MessageAck ack) {
        JsonObject json = new JsonObject();
        json.addProperty("messageId", ack.getMessageId());
        json.addProperty("conversationId", ack.getConversationId());
        json.addProperty("serverSeq", ack.getServerSeq());
        json.addProperty("serverTime", ack.getServerTime());
        return json;
    }

    public static MessageAck storedJsonToAck(JsonObject json) {
        return MessageAck.newBuilder()
                .setMessageId(requiredString(json, "messageId"))
                .setConversationId(requiredString(json, "conversationId"))
                .setServerSeq(optionalLong(json, "serverSeq", 0L))
                .setServerTime(optionalLong(json, "serverTime", 0L))
                .build();
    }

    private static ImEnvelope.Builder envelopeBuilder() {
        return ImEnvelope.newBuilder().setProtocolVersion(ImEnvelopeCodec.PROTOCOL_VERSION);
    }

    private static String conversationTypeName(ConversationType type) {
        return switch (type) {
            case CONVERSATION_TYPE_SINGLE -> "SINGLE";
            case CONVERSATION_TYPE_GROUP -> "GROUP";
            case CONVERSATION_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new ProtocolException("Unsupported conversation type: " + type);
        };
    }

    private static String messageTypeName(MessageType type) {
        return switch (type) {
            case MESSAGE_TYPE_TEXT -> "TEXT";
            case MESSAGE_TYPE_IMAGE -> "IMAGE";
            case MESSAGE_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new ProtocolException("Unsupported message type: " + type);
        };
    }

    private static ConversationType parseConversationType(String value) {
        return switch (value) {
            case "SINGLE" -> ConversationType.CONVERSATION_TYPE_SINGLE;
            case "GROUP" -> ConversationType.CONVERSATION_TYPE_GROUP;
            default -> throw new ProtocolException("Unsupported conversation type: " + value);
        };
    }

    private static MessageType parseMessageType(String value) {
        return switch (value) {
            case "TEXT" -> MessageType.MESSAGE_TYPE_TEXT;
            case "IMAGE" -> MessageType.MESSAGE_TYPE_IMAGE;
            default -> throw new ProtocolException("Unsupported message type: " + value);
        };
    }

    private static String requiredString(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || value.isJsonNull()) {
            throw new ProtocolException("Stored message missing " + name);
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject json, String name, String fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static long optionalLong(JsonObject json, String name, long fallback) {
        JsonElement value = json.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }
}
