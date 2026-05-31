package com.codex.im.ui

object AvatarPlaceholderPolicy {
    const val GROUP_PLACEHOLDER = "群"

    fun text(displayName: String, isGroup: Boolean = false): String {
        if (isGroup) {
            return GROUP_PLACEHOLDER
        }
        return displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    }
}
