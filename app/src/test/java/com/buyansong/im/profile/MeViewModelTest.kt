package com.buyansong.im.profile

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun saveProfileIsNoOpWhenDraftEqualsCurrentNickname() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile("13800138000", "13800138000", "Syan", "https://example.com/me.jpg", 1L, 1L)
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditing()
        // Don't change the draft; current profile.nickname is "Syan" and draft is also "Syan"
        val before = api.updateCallCount

        fixture.viewModel.saveProfile()
        runCurrent()

        assertEquals(before, api.updateCallCount)
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

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveProfileUsesFreshAccessTokenFromProvider() = runTest {
        val uploadApi = FakeAvatarUploadApi()
        val api = FakeProfileApi(
            updatedProfile = UserProfile("13800138000", "13800138000", "Syan", "https://oss.example.com/avatars/13800138000/2000.jpg", 2L, 2L)
        )
        val freshSession = AuthSession(
            accessToken = "fresh-token",
            refreshToken = "fresh-refresh",
            userId = "13800138000",
            username = "Syan",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = "https://example.com/me.jpg",
            avatarUpdatedAt = 1_000L,
            profileUpdatedAt = 1_000L,
            accessExpiresAtMillis = 5_000L,
            refreshExpiresAtMillis = 10_000L
        )
        val fixture = Fixture(
            this,
            profileApi = api,
            avatarUploadApi = uploadApi,
            validSessionProvider = { freshSession }
        )
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditing()
        fixture.viewModel.setSelectedAvatarBytes(byteArrayOf(1, 2, 3))

        fixture.viewModel.saveProfile()
        runCurrent()

        assertEquals("fresh-token", uploadApi.lastRequestedAccessToken)
        assertEquals("fresh-token", api.lastUpdateAccessToken)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startEditingGenderCopiesCurrentGenderIntoDraft() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = Gender.MALE,
                signature = null
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.startEditingGender()

        assertEquals(Gender.MALE, fixture.viewModel.state.value.draftGender)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updateDraftGenderChangesDraftOnly() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = Gender.MALE,
                signature = null
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingGender()

        fixture.viewModel.updateDraftGender(Gender.FEMALE)

        assertEquals(Gender.FEMALE, fixture.viewModel.state.value.draftGender)
        // Profile field stays unchanged until save
        assertEquals(Gender.MALE, fixture.viewModel.state.value.profile?.gender)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveGenderUpdatesProfileAndClearsDraft() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = Gender.MALE,
                signature = null
            ),
            updatedProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1L,
                updatedAt = 2L,
                gender = Gender.FEMALE,
                signature = null
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingGender()
        fixture.viewModel.updateDraftGender(Gender.FEMALE)

        fixture.viewModel.saveGender()
        runCurrent()

        assertEquals(Gender.FEMALE, fixture.viewModel.state.value.profile?.gender)
        assertNull(fixture.viewModel.state.value.draftGender)
        assertEquals(Gender.FEMALE, api.lastGender)
        assertEquals("Syan", api.lastNickname)
        assertEquals("https://example.com/me.jpg", api.lastAvatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveGenderIsNoOpWhenUnchanged() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = null,
                signature = null
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingGender()
        // Don't change the draft; current profile.gender is also null
        val before = api.updateCallCount

        fixture.viewModel.saveGender()
        runCurrent()

        assertEquals(before, api.updateCallCount)
        assertNull(fixture.viewModel.state.value.draftGender)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startEditingSignatureCopiesCurrentSignatureIntoDraft() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = null,
                signature = "existing sig"
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()

        fixture.viewModel.startEditingSignature()

        assertEquals("existing sig", fixture.viewModel.state.value.draftSignature)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun updateDraftSignatureTruncatesAtMaxLength() = runTest {
        val fixture = Fixture(this)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingSignature()

        fixture.viewModel.updateDraftSignature("a".repeat(50))

        val max = MeDisplayPolicy.signatureMaxLength
        assertEquals(max, fixture.viewModel.state.value.draftSignature.length)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveSignatureUpdatesProfileAndClearsDraft() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = null,
                signature = "old"
            ),
            updatedProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 1L,
                updatedAt = 2L,
                gender = null,
                signature = "new sig"
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingSignature()
        fixture.viewModel.updateDraftSignature("new sig")

        fixture.viewModel.saveSignature()
        runCurrent()

        assertEquals("new sig", fixture.viewModel.state.value.profile?.signature)
        assertEquals("", fixture.viewModel.state.value.draftSignature)
        assertEquals("new sig", api.lastSignature)
        assertEquals("Syan", api.lastNickname)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveSignatureIsNoOpWhenUnchanged() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = null,
                signature = "same"
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingSignature()
        // Don't change the draft
        val before = api.updateCallCount

        fixture.viewModel.saveSignature()
        runCurrent()

        assertEquals(before, api.updateCallCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun saveSignatureClearsServerSideWhenDraftIsBlank() = runTest {
        val api = FakeProfileApi(
            currentProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 1L,
                gender = null,
                signature = "old"
            ),
            updatedProfile = UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = null,
                avatarUpdatedAt = 1L,
                updatedAt = 2L,
                gender = null,
                signature = null
            )
        )
        val fixture = Fixture(this, profileApi = api)
        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.startEditingSignature()
        fixture.viewModel.updateDraftSignature("")

        fixture.viewModel.saveSignature()
        runCurrent()

        assertNotNull(api.lastSignature)
        assertEquals("", api.lastSignature)
        assertNull(fixture.viewModel.state.value.profile?.signature)
    }

    private class Fixture(
        scope: TestScope,
        profileApi: FakeProfileApi = FakeProfileApi(),
        avatarUploadApi: AvatarUploadApi = FakeAvatarUploadApi(),
        validSessionProvider: suspend () -> AuthSession? = {
            AuthSession(
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
        }
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
            validSessionProvider = validSessionProvider,
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
        var lastUpdateAccessToken: String? = null
        var lastGender: Gender? = null
        var lastSignature: String? = null
        var updateCallCount: Int = 0

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
            avatarObjectKey: String?,
            gender: Gender?,
            signature: String?
        ): ProfileResult {
            updateCallCount++
            lastUpdateAccessToken = accessToken
            lastNickname = nickname
            lastAvatarUrl = avatarUrl
            lastAvatarObjectKey = avatarObjectKey
            lastGender = gender
            lastSignature = signature
            return ProfileResult.Success(updatedProfile)
        }
    }

    private class FakeAvatarUploadApi : AvatarUploadApi {
        var lastUploadedBytes: ByteArray? = null
        var lastRequestedAccessToken: String? = null

        override suspend fun requestUploadTarget(accessToken: String, contentType: String): AvatarUploadResult {
            lastRequestedAccessToken = accessToken
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
