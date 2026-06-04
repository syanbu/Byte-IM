package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.storage.Gender
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ContactProfileViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startRendersCachedProfileImmediately() = runTest {
        val fixture = Fixture(this)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startRefreshesProfileInBackground() = runTest {
        val api = RecordingProfileApi().apply {
            nextUserResult = ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick"))
        }
        val fixture = Fixture(this, api = api)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        runCurrent()

        assertEquals("FreshNick", fixture.viewModel.state.value.profile?.nickname)
        assertEquals(1, api.userCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSurfacesErrorWhenNoCacheAndRemoteFails() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()

        assertNull(fixture.viewModel.state.value.profile)
        assertEquals(ContactProfileDisplayPolicy.loadFailedMessage, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startKeepsCacheAndStaysSilentWhenRemoteFails() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        runCurrent()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        assertNull(fixture.viewModel.state.value.errorMessage)
        assertEquals(1, api.userCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startHandlesExpiredSessionWhenNoCache() = runTest {
        val fixture = Fixture(this, validSession = { null })

        fixture.viewModel.start()
        runCurrent()

        assertNull(fixture.viewModel.state.value.profile)
        assertEquals(ContactProfileDisplayPolicy.sessionExpiredMessage, fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startHandlesExpiredSessionWhenCacheExists() = runTest {
        val fixture = Fixture(this, validSession = { null })
        fixture.profileDao.upsert(cachedProfile("13900113900", nickname = "CachedNick"))

        fixture.viewModel.start()
        runCurrent()

        assertEquals("CachedNick", fixture.viewModel.state.value.profile?.nickname)
        assertNull(fixture.viewModel.state.value.errorMessage)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryClearsErrorAndReRuns() = runTest {
        val api = RecordingProfileApi().apply { nextUserResult = ProfileResult.Failure("boom") }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()
        assertNotNull(fixture.viewModel.state.value.errorMessage)

        api.nextUserResult = ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick"))
        fixture.viewModel.retry()
        runCurrent()

        assertNull(fixture.viewModel.state.value.errorMessage)
        assertEquals("FreshNick", fixture.viewModel.state.value.profile?.nickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopCancelsInFlightRefresh() = runTest {
        val gate = CompletableDeferred<ProfileResult>()
        val api = RecordingProfileApi().apply { nextUserResultDeferred = gate }
        val fixture = Fixture(this, api = api)

        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.stop()
        gate.complete(ProfileResult.Success(freshProfile("13900113900", nickname = "FreshNick")))
        runCurrent()

        assertNull(fixture.viewModel.state.value.profile)
    }

    private fun cachedProfile(userId: String, nickname: String): UserProfile = UserProfile(
        userId = userId,
        phone = userId,
        nickname = nickname,
        avatarUrl = "https://example.com/$userId.jpg",
        avatarUpdatedAt = 1_000L,
        updatedAt = 1_000L,
        gender = null,
        signature = null
    )

    private fun freshProfile(userId: String, nickname: String): UserProfile = UserProfile(
        userId = userId,
        phone = userId,
        nickname = nickname,
        avatarUrl = "https://example.com/$userId.jpg",
        avatarUpdatedAt = 2_000L,
        updatedAt = 2_000L,
        gender = Gender.MALE,
        signature = "hello"
    )

    private class Fixture(
        scope: TestScope,
        api: ProfileApi = RecordingProfileApi(),
        validSession: suspend () -> AuthSession? = { session() }
    ) {
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, api)
        val viewModel = ContactProfileViewModel(
            userId = "13900113900",
            session = session(),
            profileRepository = profileRepository,
            validSessionProvider = validSession,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class RecordingProfileApi(
        var nextUserResult: ProfileResult = ProfileResult.Failure("default"),
        var nextUserResultDeferred: CompletableDeferred<ProfileResult>? = null
    ) : ProfileApi {
        var userCallCount: Int = 0

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            userCallCount++
            val deferred = nextUserResultDeferred
            return if (deferred != null) deferred.await() else nextUserResult
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}

private fun session(): AuthSession = AuthSession(
    accessToken = "token",
    refreshToken = "refresh",
    userId = "13800113800",
    username = "Syan",
    phone = "13800113800",
    nickname = "Syan",
    avatarUrl = "https://example.com/me.jpg",
    avatarUpdatedAt = 1_000L,
    profileUpdatedAt = 1_000L,
    accessExpiresAtMillis = 2_000L,
    refreshExpiresAtMillis = 3_000L
)
