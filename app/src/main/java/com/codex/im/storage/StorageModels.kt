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
    val updatedAt: Long,
    val type: MessageType = MessageType.TEXT,
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long? = null,
    val localOriginalPath: String? = null,
    val localThumbnailPath: String? = null,
    val isRecalled: Boolean = false,
    val recalledAt: Long? = null,
    val recalledBy: String? = null
)

data class Conversation(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val lastMessageId: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val updatedAt: Long,
    val peerReadUpToServerSeq: Long? = null,
    val peerReadAt: Long? = null
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
    UPLOADING,
    UPLOAD_FAILED,
    SENDING,
    SENT,
    FAILED,
    RECEIVED
}

enum class MessageDirection {
    OUTGOING,
    INCOMING
}

enum class MessageType {
    TEXT,
    IMAGE
}
