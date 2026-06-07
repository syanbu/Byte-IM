package com.buyansong.im.profile

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRepositoryTest {
    @Test
    fun bootstrapSessionStoresCurrentUserProfile() = runTest {
        val dao = InMemoryUserProfileDao()
        val repository = ProfileRepository(dao, FakeProfileApi())
        val session = AuthSession(
            accessToken = "token",
            refreshToken = "refresh",
            userId = "13800138000",
            username = "Syan",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = "https://example.com/avatar.jpg",
            avatarUpdatedAt = 2_000L,
            profileUpdatedAt = 3_000L,
            accessExpiresAtMillis = 4_000L,
            refreshExpiresAtMillis = 5_000L
        )

        repository.bootstrapSession(session)

        assertEquals("Syan", dao.findByUserId("13800138000")?.nickname)
        assertEquals("https://example.com/avatar.jpg", dao.findByUserId("13800138000")?.avatarUrl)
    }

    @Test
    fun getProfileFetchesAndCachesMissingProfile() = runTest {
        val dao = InMemoryUserProfileDao()
        val remoteProfile = UserProfile("13900139000", "13900139000", "Megumi", "https://example.com/m.jpg", 2L, 2L)
        val api = FakeProfileApi(profile = remoteProfile)
        val repository = ProfileRepository(dao, api)

        val profile = repository.getProfile("token", "13900139000")

        assertEquals(remoteProfile, profile)
        assertEquals(remoteProfile, dao.findByUserId("13900139000"))
    }

    @Test
    fun refreshProfileFetchesRemoteEvenWhenLocalCacheExists() = runTest {
        val dao = InMemoryUserProfileDao()
        dao.upsert(UserProfile("13900139000", "13900139000", "Cached", null, 1L, 1L))
        val remoteProfile = UserProfile("13900139000", "13900139000", "Fresh", null, 2L, 2L)
        val api = FakeProfileApi(profile = remoteProfile)
        val repository = ProfileRepository(dao, api)

        val profile = repository.refreshProfile("token", "13900139000")

        assertEquals(remoteProfile, profile)
        assertEquals(remoteProfile, dao.findByUserId("13900139000"))
    }

    @Test
    fun refreshProfileIgnoresBlankUserId() = runTest {
        val dao = InMemoryUserProfileDao()
        val repository = ProfileRepository(dao, FakeProfileApi())

        assertNull(repository.refreshProfile("token", " "))
    }

    private class FakeProfileApi(
        private val profile: UserProfile = UserProfile("13900139000", "13900139000", "13900139000", null, 0L, 1L)
    ) : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Success(profile)

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Success(profile)

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(listOf(profile).filter { it.userId in userIds })
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.buyansong.im.storage.Gender?,
            signature: String?
        ): ProfileResult {
            return ProfileResult.Success(profile.copy(nickname = nickname, avatarUrl = avatarUrl, gender = gender, signature = signature))
        }
    }
}
