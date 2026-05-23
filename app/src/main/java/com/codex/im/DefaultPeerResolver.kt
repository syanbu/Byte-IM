package com.codex.im

object DefaultPeerResolver {
    private const val USER_138 = "13800113800"
    private const val USER_139 = "13900113900"

    fun resolve(currentUserId: String): String {
        return if (currentUserId.startsWith("139")) USER_138 else USER_139
    }
}
