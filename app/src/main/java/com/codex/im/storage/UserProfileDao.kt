package com.codex.im.storage

interface UserProfileDao {
    fun upsert(profile: UserProfile)

    fun upsertAll(profiles: List<UserProfile>) {
        profiles.forEach(::upsert)
    }

    fun findByUserId(userId: String): UserProfile?

    fun findByUserIds(userIds: List<String>): List<UserProfile>
}

class InMemoryUserProfileDao : UserProfileDao {
    private val profiles = linkedMapOf<String, UserProfile>()

    override fun upsert(profile: UserProfile) {
        profiles[profile.userId] = profile
    }

    override fun findByUserId(userId: String): UserProfile? = profiles[userId]

    override fun findByUserIds(userIds: List<String>): List<UserProfile> {
        return userIds.mapNotNull { profiles[it] }
    }
}
