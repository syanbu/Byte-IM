package com.codex.im

object TopLevelBackPolicy {
    fun shouldExitApp(route: String?): Boolean {
        return route == SelfHostedImRoute.Conversations.route ||
            route == SelfHostedImRoute.Contacts.route ||
            route == SelfHostedImRoute.Me.route
    }
}
