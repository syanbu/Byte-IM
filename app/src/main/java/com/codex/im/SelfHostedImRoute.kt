package com.codex.im

sealed class SelfHostedImRoute(val route: String) {
    data object Conversations : SelfHostedImRoute("conversations")

    data object Contacts : SelfHostedImRoute("contacts")

    data object GroupCreate : SelfHostedImRoute("group-create")

    data object JoinedGroups : SelfHostedImRoute("joined-groups")

    data object Me : SelfHostedImRoute("me")

    data object ContactProfile : SelfHostedImRoute("contact-profile/{userId}") {
        const val USER_ID_ARG = "userId"
        val pattern: String = route

        fun createRoute(userId: String): String? {
            val trimmedUserId = userId.trim()
            if (trimmedUserId.isEmpty()) {
                return null
            }
            return "contact-profile/$trimmedUserId"
        }
    }

    data object GroupInfo : SelfHostedImRoute("group-info/{groupId}") {
        const val GROUP_ID_ARG = "groupId"
        val pattern: String = route

        fun createRoute(groupId: String): String? {
            val trimmedGroupId = groupId.trim()
            if (trimmedGroupId.isEmpty()) {
                return null
            }
            return "group-info/$trimmedGroupId"
        }
    }

    data object Chat : SelfHostedImRoute("chat/{conversationId}") {
        const val CONVERSATION_ID_ARG = "conversationId"
        const val PEER_USER_ID_ARG = CONVERSATION_ID_ARG

        val pattern: String = route

        fun createRoute(conversationId: String): String? {
            val trimmedConversationId = conversationId.trim()
            if (trimmedConversationId.isEmpty()) {
                return null
            }
            return "chat/$trimmedConversationId"
        }

        fun createSingleRoute(currentUserId: String, peerUserId: String): String? {
            val current = currentUserId.trim()
            val peer = peerUserId.trim()
            if (current.isEmpty() || peer.isEmpty()) {
                return null
            }
            val participants = listOf(current, peer).sorted()
            return createRoute("single:${participants[0]}:${participants[1]}")
        }
    }
}
