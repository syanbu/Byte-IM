package com.codex.im

data class BottomNavigationItemSpec(
    val route: String,
    val label: String,
    val iconResId: Int
)

object BottomNavigationSpec {
    val messages = BottomNavigationItemSpec(
        route = SelfHostedImRoute.Conversations.route,
        label = "消息",
        iconResId = R.drawable.ic_nav_message
    )

    val contacts = BottomNavigationItemSpec(
        route = SelfHostedImRoute.Contacts.route,
        label = "通讯录",
        iconResId = R.drawable.ic_nav_contacts
    )

    val me = BottomNavigationItemSpec(
        route = SelfHostedImRoute.Me.route,
        label = "我",
        iconResId = R.drawable.ic_nav_me
    )

    val topLevelItems = listOf(messages, contacts, me)
}
