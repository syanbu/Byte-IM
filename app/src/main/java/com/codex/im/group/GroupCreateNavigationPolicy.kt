package com.codex.im.group

import com.codex.im.SelfHostedImRoute

object GroupCreateNavigationPolicy {
    fun destinationAfterCreated(conversationId: String): String? {
        return SelfHostedImRoute.Chat.createRoute(conversationId)
    }
}
