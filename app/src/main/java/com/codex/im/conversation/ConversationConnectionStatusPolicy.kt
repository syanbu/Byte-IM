package com.codex.im.conversation

object ConversationConnectionStatusPolicy {
    fun visibleLabel(connectionStatus: String): String? {
        val normalized = connectionStatus.trim().ifEmpty { "未连接" }
        if (normalized == "已认证" || normalized == "已连接") {
            return null
        }
        return "连接状态：$normalized"
    }
}
