package com.buyansong.im.profile

import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileRepositoryTest {
    private class FakeProfileApi(
        private val profilesById: Map<String, UserProfile>
    ) : ProfileApi {
        val batchRequests = mutableListOf<List<String>>()

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            return profilesById[userId]?.let(ProfileResult::Success) ?: ProfileResult.Failure("missing")
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            batchRequests += userIds
            return ProfileBatchResult.Success(userIds.mapNotNull(profilesById::get))
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String?,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    @Test
    fun ensureProfiles_withRemoteVersionsFetchesMissingProfileEvenWhenRemoteVersionIsZero() = runBlocking {
        val dao = InMemoryUserProfileDao()
        val api = FakeProfileApi(
            profilesById = mapOf(
                "u1" to profile("u1", nickname = "Alice", profileVersion = 0L)
            )
        )
        val repository = ProfileRepository(dao, api)

        val profiles = repository.ensureProfiles(
            accessToken = "token",
            userIds = listOf("u1"),
            remoteVersions = mapOf("u1" to 0L)
        )

        assertEquals(listOf(listOf("u1")), api.batchRequests)
        assertEquals("Alice", profiles.single().nickname)
        assertEquals(0L, dao.findByUserId("u1")?.profileVersion)
    }

    @Test
    fun ensureProfiles_withRemoteVersionsSkipsFetchWhenLocalVersionIsCurrent() = runBlocking {
        val dao = InMemoryUserProfileDao()
        dao.upsert(profile("u1", nickname = "Local", profileVersion = 5L))
        val api = FakeProfileApi(
            profilesById = mapOf(
                "u1" to profile("u1", nickname = "Remote", profileVersion = 5L)
            )
        )
        val repository = ProfileRepository(dao, api)

        val profiles = repository.ensureProfiles(
            accessToken = "token",
            userIds = listOf("u1"),
            remoteVersions = mapOf("u1" to 5L)
        )

        assertEquals(emptyList<List<String>>(), api.batchRequests)
        assertEquals("Local", profiles.single().nickname)
    }

    @Test
    fun ensureProfiles_doesNotOverwriteNewerLocalProfileWithOlderRemoteProfile() = runBlocking {
        val dao = InMemoryUserProfileDao()
        dao.upsert(profile("u1", nickname = "New Local", profileVersion = 8L))
        val api = FakeProfileApi(
            profilesById = mapOf(
                "u1" to profile("u1", nickname = "Old Remote", profileVersion = 7L)
            )
        )
        val repository = ProfileRepository(dao, api)

        val profiles = repository.ensureProfiles(
            accessToken = "token",
            userIds = listOf("u1"),
            remoteVersions = mapOf("u1" to 9L)
        )

        assertEquals(listOf(listOf("u1")), api.batchRequests)
        assertEquals("New Local", profiles.single().nickname)
    }

    @Test
    fun parseProfileReadsProfileVersion() {
        val result = ProfileJsonParser.parseProfile(
            """
            {
              "code": 0,
              "data": {
                "userId": "u1",
                "phone": "u1",
                "nickname": "Alice",
                "avatarUpdatedAt": 0,
                "updatedAt": 10,
                "profileVersion": 6
              }
            }
            """.trimIndent()
        )

        val profile = (result as ProfileResult.Success).profile
        assertEquals(6L, profile.profileVersion)
    }

    @Test
    fun refreshProfileFetchesRemoteEvenWhenLocalProfileExists() = runBlocking {
        val dao = InMemoryUserProfileDao()
        dao.upsert(profile("u1", nickname = "Old", profileVersion = 1L))
        val api = FakeProfileApi(
            profilesById = mapOf(
                "u1" to profile("u1", nickname = "New", profileVersion = 2L)
            )
        )
        val repository = ProfileRepository(dao, api)

        val profile = repository.refreshProfile("token", "u1")

        assertEquals("New", profile?.nickname)
        assertEquals("New", dao.findByUserId("u1")?.nickname)
    }

    @Test
    fun refreshProfilesFetchesRemoteEvenWhenLocalProfilesExist() = runBlocking {
        val dao = InMemoryUserProfileDao()
        dao.upsert(profile("u1", nickname = "Old", profileVersion = 1L))
        val api = FakeProfileApi(
            profilesById = mapOf(
                "u1" to profile("u1", nickname = "New", profileVersion = 2L)
            )
        )
        val repository = ProfileRepository(dao, api)

        val profiles = repository.refreshProfiles("token", listOf("u1"))

        assertEquals(listOf(listOf("u1")), api.batchRequests)
        assertEquals("New", profiles.single().nickname)
        assertEquals("New", dao.findByUserId("u1")?.nickname)
    }

    private fun profile(userId: String, nickname: String, profileVersion: Long): UserProfile {
        return UserProfile(
            userId = userId,
            phone = userId,
            nickname = nickname,
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = 0L,
            profileVersion = profileVersion
        )
    }
}
