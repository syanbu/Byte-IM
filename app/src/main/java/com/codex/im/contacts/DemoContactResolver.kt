package com.codex.im.contacts

object DemoContactResolver {
    private const val USER_138 = "13800113800"
    private const val USER_139 = "13900113900"

    fun contactsFor(currentUserId: String): List<String> {
        return when (currentUserId) {
            USER_138 -> listOf(USER_139)
            USER_139 -> listOf(USER_138)
            else -> emptyList()
        }
    }
}
