package com.buyansong.im.profile

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.UserProfile
import com.buyansong.im.storage.UserProfileDao

class ProfileRepository(
    private val userProfileDao: UserProfileDao,
    private val profileApi: ProfileApi
) {
    fun bootstrapSession(session: AuthSession): UserProfile {
        val profile = session.toUserProfile()
        upsertIfNewer(profile)
        return userProfileDao.findByUserId(session.userId) ?: profile
    }

    fun localProfile(userId: String): UserProfile? = userProfileDao.findByUserId(userId)

    fun localProfiles(userIds: List<String>): List<UserProfile> = userProfileDao.findByUserIds(userIds)

    suspend fun currentUserProfile(session: AuthSession): UserProfile {
        val local = bootstrapSession(session)
        return when (val result = profileApi.me(session.accessToken)) {
            is ProfileResult.Success -> {
                upsertIfNewer(result.profile)
                userProfileDao.findByUserId(result.profile.userId) ?: result.profile
            }
            is ProfileResult.Failure -> local
        }
    }

    suspend fun getProfile(accessToken: String, userId: String): UserProfile? {
        userProfileDao.findByUserId(userId)?.let { return it }
        return when (val result = profileApi.user(accessToken, userId)) {
            is ProfileResult.Success -> {
                upsertIfNewer(result.profile)
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
                upsertIfNewer(result.profile)
                userProfileDao.findByUserId(trimmedUserId)
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
        val profilesToUpsert = remoteProfiles.filter { remote ->
            val local = userProfileDao.findByUserId(remote.userId)
            local == null || remote.profileVersion > local.profileVersion
        }
        if (profilesToUpsert.isNotEmpty()) {
            userProfileDao.upsertAll(profilesToUpsert)
        }
        return userProfileDao.findByUserIds(distinctUserIds)
    }

    suspend fun ensureProfiles(
        accessToken: String,
        userIds: List<String>,
        remoteVersions: Map<String, Long> = emptyMap()
    ): List<UserProfile> {
        val distinctUserIds = userIds.distinct().filter { it.isNotBlank() }
        if (distinctUserIds.isEmpty()) {
            return emptyList()
        }
        val localProfiles = userProfileDao.findByUserIds(distinctUserIds)
        val localById = localProfiles.associateBy { it.userId }
        val idsToFetch = if (remoteVersions.isNotEmpty()) {
            distinctUserIds.filter { id ->
                val local = localById[id]
                val remoteVersion = remoteVersions[id] ?: 0L
                local == null || remoteVersion > local.profileVersion
            }
        } else {
            distinctUserIds.filter { it !in localById }
        }
        if (idsToFetch.isEmpty()) {
            return localProfiles
        }
        val remoteProfiles = when (val result = profileApi.batch(accessToken, idsToFetch)) {
            is ProfileBatchResult.Success -> result.profiles
            is ProfileBatchResult.Failure -> emptyList()
        }
        val profilesToUpsert = remoteProfiles.filter { remote ->
            val local = localById[remote.userId]
            local == null || remote.profileVersion > local.profileVersion
        }
        if (profilesToUpsert.isNotEmpty()) {
            userProfileDao.upsertAll(profilesToUpsert)
        }
        return userProfileDao.findByUserIds(distinctUserIds)
    }

    fun backfillFromProfiles(
        members: List<GroupMember>,
        profilesById: Map<String, UserProfile>
    ): List<GroupMember> {
        if (members.isEmpty()) return members
        return members.map { member ->
            val profile = profilesById[member.userId]
            member.copy(
                displayName = profile?.nickname?.takeIf { it.isNotBlank() } ?: member.displayName,
                avatarUrl = profile?.avatarUrl ?: member.avatarUrl
            )
        }
    }

    suspend fun updateMe(
        session: AuthSession,
        nickname: String? = null,
        avatarUrl: String? = null,
        avatarObjectKey: String? = null,
        gender: Gender? = null,
        signature: String? = null
    ): UserProfile? {
        return when (val result = profileApi.updateMe(session.accessToken, nickname, avatarUrl, avatarObjectKey, gender, signature)) {
            is ProfileResult.Success -> {
                upsertIfNewer(result.profile)
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
            signature = null,
            profileVersion = profileVersion
        )
    }

    private fun upsertIfNewer(profile: UserProfile) {
        val local = userProfileDao.findByUserId(profile.userId)
        if (local == null || profile.profileVersion > local.profileVersion) {
            userProfileDao.upsert(profile)
        }
    }
}
