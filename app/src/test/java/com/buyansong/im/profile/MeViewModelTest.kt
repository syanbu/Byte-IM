package com.buyansong.im.profile

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

class MeViewModelTest {
    private class FakeProfileApi(
        private val meProfile: UserProfile? = null
    ) : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult {
            return meProfile?.let(ProfileResult::Success) ?: ProfileResult.Failure("unused")
        }

        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            return ProfileResult.Failure("unused")
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Failure("unused")
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String?,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult {
            return ProfileResult.Failure("unused")
        }
    }

    @Test
    fun constructorExposesCachedCurrentUserProfileBeforeStart() {
        val profileDao = InMemoryUserProfileDao()
        profileDao.upsert(profile("u1", nickname = "Cached", avatarUrl = "cached.png", profileVersion = 3L))

        val viewModel = MeViewModel(
            session = session("u1", nickname = "Session", avatarUrl = null, profileVersion = 1L),
            profileRepository = ProfileRepository(profileDao, FakeProfileApi()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            dispatcher = Dispatchers.Unconfined
        )

        assertEquals("Cached", viewModel.state.value.profile?.nickname)
        assertEquals("cached.png", viewModel.state.value.profile?.avatarUrl)
        viewModel.stop()
    }

    private fun session(
        userId: String,
        nickname: String,
        avatarUrl: String?,
        profileVersion: Long
    ): AuthSession {
        return AuthSession(
            accessToken = "token",
            refreshToken = "refresh",
            userId = userId,
            username = nickname,
            phone = userId,
            nickname = nickname,
            avatarUrl = avatarUrl,
            profileVersion = profileVersion,
            accessExpiresAtMillis = Long.MAX_VALUE,
            refreshExpiresAtMillis = Long.MAX_VALUE
        )
    }

    private fun profile(
        userId: String,
        nickname: String,
        avatarUrl: String?,
        profileVersion: Long
    ): UserProfile {
        return UserProfile(
            userId = userId,
            phone = userId,
            nickname = nickname,
            avatarUrl = avatarUrl,
            avatarUpdatedAt = 0L,
            updatedAt = 0L,
            profileVersion = profileVersion
        )
    }
}
