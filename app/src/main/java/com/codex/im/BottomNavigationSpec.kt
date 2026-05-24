package com.codex.im

data class BottomNavigationItemSpec(
    val route: String,
    val label: String,
    val iconResId: Int
)

object BottomNavigationSpec {
    val messages = BottomNavigationItemSpec(
        route = SelfHostedImRoute.Conversations.route,
        label = "Messages",
        iconResId = R.drawable.ic_nav_message
    )

    val me = BottomNavigationItemSpec(
        route = SelfHostedImRoute.Me.route,
        label = "Me",
        iconResId = R.drawable.ic_nav_me
    )
}
