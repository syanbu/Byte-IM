package com.codex.im.contacts

import com.codex.im.storage.FriendContact

interface ContactApi {
    suspend fun friends(accessToken: String): ContactIdsResult
}

sealed class ContactIdsResult {
    data class Success(
        val userIds: List<String>,
        val contacts: List<FriendContact> = userIds.mapIndexed { index, userId ->
            FriendContact(userId = userId, profileUpdatedAt = 0L, sortOrder = index)
        }
    ) : ContactIdsResult()

    data class Failure(val message: String) : ContactIdsResult()
}
