package com.codex.im.profile

import com.codex.im.auth.AuthSession
import com.codex.im.storage.Gender
import com.codex.im.storage.UserProfile
import com.codex.im.storage.UserProfileDao

class ProfileRepository(
    private val userProfileDao: UserProfileDao,
    private val profileApi: ProfileApi
) {
    fun bootstrapSession(session: AuthSession): UserProfile {
        val profile = session.toUserProfile()
        userProfileDao.upsert(profile)
        return profile
    }

    fun localProfile(userId: String): UserProfile? = userProfileDao.findByUserId(userId)

    fun localProfiles(userIds: List<String>): List<UserProfile> = userProfileDao.findByUserIds(userIds)

    suspend fun currentUserProfile(session: AuthSession): UserProfile {
        val local = bootstrapSession(session)
        return when (val result = profileApi.me(session.accessToken)) {
            is ProfileResult.Success -> {
                userProfileDao.upsert(result.profile)
                result.profile
            }
            is ProfileResult.Failure -> local
        }
    }

    suspend fun getProfile(accessToken: String, userId: String): UserProfile? {
        userProfileDao.findByUserId(userId)?.let { return it }
        return when (val result = profileApi.user(accessToken, userId)) {
            is ProfileResult.Success -> {
                userProfileDao.upsert(result.profile)
                result.profile
            }
            is ProfileResult.Failure -> null
        }
    }

    suspend fun refreshProfile(accessToken: String, userId: String): UserProfile? {
        val trimmedUserId = userId.trim()
        if (trimmedUserId.isEmpty()) {
            return null
        }
        return when (val result = profileApi.user(accessToken, trimmedUserId)) {
            is ProfileResult.Success -> {
                userProfileDao.upsert(result.profile)
                result.profile
            }
            is ProfileResult.Failure -> null
        }
    }

    suspend fun refreshProfiles(accessToken: String, userIds: List<String>): List<UserProfile> {
        val distinctUserIds = userIds.distinct().filter { it.isNotBlank() }
        if (distinctUserIds.isEmpty()) {
            return emptyList()
        }
        val remoteProfiles = when (val result = profileApi.batch(accessToken, distinctUserIds)) {
            is ProfileBatchResult.Success -> result.profiles
            is ProfileBatchResult.Failure -> emptyList()
        }
        userProfileDao.upsertAll(remoteProfiles)
        return userProfileDao.findByUserIds(distinctUserIds)
    }

    suspend fun updateMe(
        session: AuthSession,
        nickname: String,
        avatarUrl: String?,
        avatarObjectKey: String?,
        gender: Gender? = null,
        signature: String? = null
    ): UserProfile? {
        return when (val result = profileApi.updateMe(session.accessToken, nickname, avatarUrl, avatarObjectKey, gender, signature)) {
            is ProfileResult.Success -> {
                userProfileDao.upsert(result.profile)
                result.profile
            }
            is ProfileResult.Failure -> null
        }
    }

    private fun AuthSession.toUserProfile(): UserProfile {
        return UserProfile(
            userId = userId,
            phone = phone,
            nickname = nickname,
            avatarUrl = avatarUrl,
            avatarUpdatedAt = avatarUpdatedAt,
            updatedAt = profileUpdatedAt,
            gender = null,
            signature = null
        )
    }
}
