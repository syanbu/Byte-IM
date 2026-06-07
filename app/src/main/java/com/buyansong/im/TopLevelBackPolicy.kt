package com.buyansong.im

object TopLevelBackPolicy {
    fun shouldMoveTaskToBack(route: String?): Boolean {
        return route == SelfHostedImRoute.Conversations.route ||
            route == SelfHostedImRoute.Contacts.route ||
            route == SelfHostedImRoute.Me.route
    }
}
