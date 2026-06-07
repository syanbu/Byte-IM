package com.buyansong.im.group

import com.buyansong.im.SelfHostedImRoute

object GroupCreateNavigationPolicy {
    fun destinationAfterCreated(conversationId: String): String? {
        return SelfHostedImRoute.Chat.createRoute(conversationId)
    }
}
