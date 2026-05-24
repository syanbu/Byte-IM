package com.codex.im.storage

data class ChatMessage(
    val messageId: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val clientSeq: Long,
    val serverSeq: Long?,
    val content: String,
    val status: MessageStatus,
    val direction: MessageDirection,
    val createdAt: Long,
    val updatedAt: Long
)

data class Conversation(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val lastMessageId: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val updatedAt: Long
)

data class PendingMessage(
    val messageId: String,
    val packetCmd: Int,
    val packetBody: String,
    val retryCount: Int,
    val nextRetryAt: Long,
    val createdAt: Long
)

data class UserProfile(
    val userId: String,
    val phone: String,
    val nickname: String,
    val avatarUrl: String?,
    val avatarUpdatedAt: Long,
    val updatedAt: Long
)

enum class MessageStatus {
    SENDING,
    SENT,
    FAILED,
    RECEIVED
}

enum class MessageDirection {
    OUTGOING,
    INCOMING
}
