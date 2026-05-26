package com.codex.im.conversation

object ConversationConnectionStatusPolicy {
    fun visibleLabel(connectionStatus: String): String? {
        val normalized = connectionStatus.trim().ifEmpty { "Disconnected" }
        if (normalized == "Authenticated" || normalized == "Connected") {
            return null
        }
        return "Connection: $normalized"
    }
}
