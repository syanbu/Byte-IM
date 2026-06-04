package com.codex.im.storage

object AccountScopedDatabaseName {
    fun forUser(userId: String): String {
        val safeUserId = userId
            .trim()
            .replace(Regex("[^A-Za-z0-9_]"), "_")
            .ifBlank { "unknown" }
        return "self_hosted_im_$safeUserId.db"
    }
}
