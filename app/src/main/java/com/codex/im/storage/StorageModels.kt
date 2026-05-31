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
    val recalledBy: String? = null,
    val conversationType: ConversationType = ConversationType.SINGLE,
    val groupId: String? = null,
    val groupName: String? = null,
    val mentionedUserIds: List<String> = emptyList()
)

data class Conversation(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val type: ConversationType = ConversationType.SINGLE,
    val title: String = peerName,
    val avatarUrl: String? = null,
    val lastMessageId: String?,
    val lastMessagePreview: String,
    val lastMessageTime: Long,
    val unreadCount: Int,
    val mentionUnreadCount: Int = 0,
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

data class GroupInfo(
    val groupId: String,
    val name: String,
    val avatarUrl: String?,
    val ownerId: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class GroupMember(
    val groupId: String,
    val userId: String,
    val displayName: String,
    val avatarUrl: String?,
    val role: GroupMemberRole,
    val joinedAt: Long,
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

enum class ConversationType {
    SINGLE,
    GROUP
}

enum class GroupMemberRole {
    OWNER,
    MEMBER
}

enum class MessageType {
    TEXT,
    IMAGE
}
