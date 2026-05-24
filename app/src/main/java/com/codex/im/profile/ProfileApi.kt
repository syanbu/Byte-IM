package com.codex.im.profile

import com.codex.im.storage.UserProfile

interface ProfileApi {
    suspend fun me(accessToken: String): ProfileResult

    suspend fun user(accessToken: String, userId: String): ProfileResult

    suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult

    suspend fun updateMe(
        accessToken: String,
        nickname: String,
        avatarUrl: String?,
        avatarObjectKey: String?
    ): ProfileResult
}

sealed class ProfileResult {
    data class Success(val profile: UserProfile) : ProfileResult()
    data class Failure(val message: String) : ProfileResult()
}

sealed class ProfileBatchResult {
    data class Success(val profiles: List<UserProfile>) : ProfileBatchResult()
    data class Failure(val message: String) : ProfileBatchResult()
}
