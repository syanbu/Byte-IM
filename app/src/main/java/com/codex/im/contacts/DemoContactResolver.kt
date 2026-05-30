package com.codex.im.contacts

object DemoContactResolver {
    private val demoUserIds = listOf(
        "13267100423",
        "13800113800",
        "13900113900",
        "17724734511"
    )

    fun contactsFor(currentUserId: String): List<String> {
        val trimmedUserId = currentUserId.trim()
        if (trimmedUserId !in demoUserIds) {
            return emptyList()
        }
        return demoUserIds.filterNot { it == trimmedUserId }
    }
}
