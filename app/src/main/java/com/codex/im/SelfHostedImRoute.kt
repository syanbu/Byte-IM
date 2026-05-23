package com.codex.im

sealed class SelfHostedImRoute(val route: String) {
    data object Conversations : SelfHostedImRoute("conversations")

    data object Chat : SelfHostedImRoute("chat/{peerUserId}") {
        const val PEER_USER_ID_ARG = "peerUserId"

        val pattern: String = route

        fun createRoute(peerUserId: String): String? {
            val trimmedPeerUserId = peerUserId.trim()
            if (trimmedPeerUserId.isEmpty()) {
                return null
            }
            return "chat/$trimmedPeerUserId"
        }
    }
}
