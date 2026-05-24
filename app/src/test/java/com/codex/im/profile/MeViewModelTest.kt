package com.codex.im.profile

import com.codex.im.auth.AuthSession
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startExposesCurrentUserProfile() = runTest {
        val dao = InMemoryUserProfileDao()
        val session = AuthSession(
            accessToken = "token",
            refreshToken = "refresh",
            userId = "13800138000",
            username = "Syan",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = "https://example.com/me.jpg",
            avatarUpdatedAt = 1_000L,
            profileUpdatedAt = 1_000L,
            accessExpiresAtMillis = 2_000L,
            refreshExpiresAtMillis = 3_000L
        )
        val viewModel = MeViewModel(
            session = session,
            profileRepository = ProfileRepository(dao, FakeProfileApi()),
            scope = backgroundScope,
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        viewModel.start()
        runCurrent()

        assertEquals("Syan", viewModel.state.value.profile?.nickname)
        assertEquals("13800138000", viewModel.state.value.profile?.phone)
        assertEquals("https://example.com/me.jpg", viewModel.state.value.profile?.avatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startEditingCopiesCurrentProfileIntoDraftFields() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.startEditing()

        assertTrue(fixture.viewModel.state.value.isEditing)
        assertEquals("Syan", fixture.viewModel.state.value.draftNickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancelEditingKeepsSavedProfileUnchanged() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditing()
        fixture.viewModel.updateDraftNickname("Changed")

        fixture.viewModel.cancelEditing()

        assertFalse(fixture.viewModel.state.value.isEditing)
        assertEquals("Syan", fixture.viewModel.state.value.profile?.nickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveProfileTrimsNicknameAndKeepsExistingAvatarWhenNoNewImageIsSelected() = runTest {
        val api = FakeProfileApi(
            updatedProfile = UserProfile("13800138000", "13800138000", "Megumi", "https://example.com/me.jpg", 1L, 2L)
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditing()
        fixture.viewModel.updateDraftNickname("  Megumi  ")

        fixture.viewModel.saveProfile()
        runCurrent()

        assertFalse(fixture.viewModel.state.value.isEditing)
        assertEquals("Megumi", fixture.viewModel.state.value.profile?.nickname)
        assertEquals("https://example.com/me.jpg", fixture.viewModel.state.value.profile?.avatarUrl)
        assertEquals("Megumi", api.lastNickname)
        assertEquals("https://example.com/me.jpg", api.lastAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveProfileUploadsSelectedAvatarBytesBeforeUpdatingProfile() = runTest {
        val uploadApi = FakeAvatarUploadApi()
        val api = FakeProfileApi(
            updatedProfile = UserProfile("13800138000", "13800138000", "Syan", "https://oss.example.com/avatars/13800138000/2000.jpg", 2L, 2L)
        )
        val fixture = Fixture(this, profileApi = api, avatarUploadApi = uploadApi)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditing()
        fixture.viewModel.setSelectedAvatarBytes(byteArrayOf(1, 2, 3))

        fixture.viewModel.saveProfile()
        runCurrent()

        assertEquals(byteArrayOf(1, 2, 3).toList(), uploadApi.lastUploadedBytes?.toList())
        assertEquals("https://oss.example.com/avatars/13800138000/2000.jpg", api.lastAvatarUrl)
        assertEquals("avatars/13800138000/2000.jpg", api.lastAvatarObjectKey)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveAvatarBytesKeepsNameEditingClosedAndUsesCurrentNickname() = runTest {
        val uploadApi = FakeAvatarUploadApi()
        val api = FakeProfileApi(
            updatedProfile = UserProfile("13800138000", "13800138000", "Syan", "https://oss.example.com/avatars/13800138000/2000.jpg", 2L, 2L)
        )
        val fixture = Fixture(this, profileApi = api, avatarUploadApi = uploadApi)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.saveAvatarBytes(byteArrayOf(4, 5, 6))
        runCurrent()

        assertFalse(fixture.viewModel.state.value.isEditing)
        assertEquals("Syan", api.lastNickname)
        assertEquals(byteArrayOf(4, 5, 6).toList(), uploadApi.lastUploadedBytes?.toList())
        assertEquals("https://oss.example.com/avatars/13800138000/2000.jpg", api.lastAvatarUrl)
    }

    private class Fixture(
        scope: TestScope,
        profileApi: FakeProfileApi = FakeProfileApi(),
        avatarUploadApi: AvatarUploadApi = FakeAvatarUploadApi()
    ) {
        private val dao = InMemoryUserProfileDao()
        val viewModel = MeViewModel(
            session = AuthSession(
                accessToken = "token",
                refreshToken = "refresh",
                userId = "13800138000",
                username = "Syan",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1_000L,
                profileUpdatedAt = 1_000L,
                accessExpiresAtMillis = 2_000L,
                refreshExpiresAtMillis = 3_000L
            ),
            profileRepository = ProfileRepository(dao, profileApi),
            avatarUploadApi = avatarUploadApi,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeProfileApi(
        private val currentProfile: UserProfile = UserProfile("13800138000", "13800138000", "Syan", "https://example.com/me.jpg", 1L, 1L),
        private val updatedProfile: UserProfile = currentProfile
    ) : ProfileApi {
        var lastNickname: String? = null
        var lastAvatarUrl: String? = null
        var lastAvatarObjectKey: String? = null

        override suspend fun me(accessToken: String): ProfileResult {
            return ProfileResult.Success(currentProfile)
        }

        override suspend fun user(accessToken: String, userId: String): ProfileResult = me(accessToken)

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?
        ): ProfileResult {
            lastNickname = nickname
            lastAvatarUrl = avatarUrl
            lastAvatarObjectKey = avatarObjectKey
            return ProfileResult.Success(updatedProfile)
        }
    }

    private class FakeAvatarUploadApi : AvatarUploadApi {
        var lastUploadedBytes: ByteArray? = null

        override suspend fun requestUploadTarget(accessToken: String, contentType: String): AvatarUploadResult {
            return AvatarUploadResult.Success(
                AvatarUploadTarget(
                    objectKey = "avatars/13800138000/2000.jpg",
                    uploadUrl = "https://signed.example.com/upload",
                    publicUrl = "https://oss.example.com/avatars/13800138000/2000.jpg",
                    expiresAt = 3_000L
                )
            )
        }

        override suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult {
            lastUploadedBytes = bytes
            return AvatarPutResult.Success
        }
    }
}
