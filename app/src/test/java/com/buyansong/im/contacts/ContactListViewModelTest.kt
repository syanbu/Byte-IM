package com.buyansong.im.contacts

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.profile.ProfileApi
import com.buyansong.im.profile.ProfileBatchResult
import com.buyansong.im.profile.ProfileRepository
import com.buyansong.im.profile.ProfileResult
import com.buyansong.im.storage.FriendContact
import com.buyansong.im.storage.InMemoryFriendContactDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactListViewModelTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startLoadsServerFriendIds() = runTest {
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendUserIds = listOf("15000000003", "15000000004")
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("15000000003", "15000000004"),
            fixture.viewModel.state.value.items.map { it.userId }
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun serverFriendIdsAreRefreshedThroughProfileBatch() = runTest {
        val profileApi = FakeProfileApi()
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendUserIds = listOf("15000000003", "15000000004"),
            profileApiOverride = profileApi
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals(listOf(listOf("15000000003", "15000000004")), profileApi.requestedBatchUserIds)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun contactUsesProfileNicknameAndAvatarFromCache() = runTest {
        val fixture = Fixture(this, userId = "15000000000", friendUserIds = listOf("13900113900"))
        fixture.profileDao.upsert(
            UserProfile(
                userId = "13900113900",
                phone = "13900113900",
                nickname = "Megumi",
                avatarUrl = "https://example.com/megumi.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 2_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single { it.userId == "13900113900" }
        assertEquals("Megumi", item.displayName)
        assertEquals("https://example.com/megumi.jpg", item.avatarUrl)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startShowsCachedContactsBeforeRemoteRefreshCompletes() = runTest {
        val profileApi = BlockingBatchProfileApi()
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendUserIds = listOf("13900113900"),
            profileApiOverride = profileApi
        )
        fixture.friendContactDao.replaceForOwner(
            ownerUserId = "15000000000",
            contacts = listOf(FriendContact("13900113900", profileUpdatedAt = 2_000L, sortOrder = 0))
        )
        fixture.profileDao.upsert(
            UserProfile(
                userId = "13900113900",
                phone = "13900113900",
                nickname = "Megumi",
                avatarUrl = "https://example.com/megumi.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 2_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val item = fixture.viewModel.state.value.items.single()
        assertEquals("13900113900", item.userId)
        assertEquals("Megumi", item.displayName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startDoesNotBatchRefreshUnchangedProfiles() = runTest {
        val profileApi = FakeProfileApi()
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendContacts = listOf(FriendContact("13900113900", profileUpdatedAt = 2_000L, sortOrder = 0)),
            profileApiOverride = profileApi
        )
        fixture.profileDao.upsert(profile("13900113900", updatedAt = 2_000L))

        fixture.viewModel.start()
        runCurrent()

        assertEquals(emptyList<List<String>>(), profileApi.requestedBatchUserIds)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startBatchRefreshesProfilesWithNewerRemoteVersion() = runTest {
        val profileApi = FakeProfileApi()
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendContacts = listOf(FriendContact("13900113900", profileUpdatedAt = 3_000L, sortOrder = 0)),
            profileApiOverride = profileApi
        )
        fixture.profileDao.upsert(profile("13900113900", updatedAt = 2_000L))

        fixture.viewModel.start()
        runCurrent()

        assertEquals(listOf(listOf("13900113900")), profileApi.requestedBatchUserIds)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun changedProfilesAreRefreshedInInitialAndBackgroundBatches() = runTest {
        val profileApi = FakeProfileApi()
        val contacts = (1..25).map { index ->
            FriendContact("139001139%02d".format(index), profileUpdatedAt = 2_000L, sortOrder = index - 1)
        }
        val fixture = Fixture(
            this,
            userId = "15000000000",
            friendContacts = contacts,
            profileApiOverride = profileApi
        )

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf(
                contacts.take(20).map { it.userId },
                contacts.drop(20).take(10).map { it.userId }
            ),
            profileApi.requestedBatchUserIds
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openContactExposesNavigationTargetWithoutCreatingConversation() = runTest {
        val fixture = Fixture(this, userId = "15000000000")

        fixture.viewModel.openContact("13900113900")
        runCurrent()

        assertEquals("13900113900", fixture.viewModel.state.value.navigationTargetPeerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeNavigationTargetClearsIt() = runTest {
        val fixture = Fixture(this, userId = "15000000000")

        fixture.viewModel.openContact("13900113900")
        runCurrent()
        fixture.viewModel.consumeNavigationTarget()

        assertEquals(null, fixture.viewModel.state.value.navigationTargetPeerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun stopAndStartAgainKeepsExistingItemsWithoutRefreshing() = runTest {
        val fixture = Fixture(this, userId = "15000000000")

        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.stop()
        fixture.viewModel.start()
        runCurrent()

        assertEquals(listOf("13900113900"), fixture.viewModel.state.value.items.map { it.userId })
        assertEquals(1, fixture.contactApi.requestCount)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun scrollPositionSurvivesStopStartAndRefreshUpdates() = runTest {
        val fixture = Fixture(this, userId = "15000000000")

        fixture.viewModel.start()
        runCurrent()
        fixture.viewModel.updateScrollPosition(firstVisibleItemIndex = 12, firstVisibleItemScrollOffset = 34)
        fixture.viewModel.stop()
        fixture.viewModel.start()
        runCurrent()

        assertEquals(12, fixture.viewModel.state.value.firstVisibleItemIndex)
        assertEquals(34, fixture.viewModel.state.value.firstVisibleItemScrollOffset)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startExposesSelfEntryFromBootstrappedSession() = runTest {
        val fixture = Fixture(this, userId = "15000000000")

        fixture.viewModel.start()
        runCurrent()

        val self = fixture.viewModel.state.value.selfEntry
        assertEquals("15000000000", self?.userId)
        assertEquals("15000000000", self?.displayName)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun startSelfEntryPrefersCachedProfileNickname() = runTest {
        val fixture = Fixture(this, userId = "15000000000")
        fixture.profileDao.upsert(
            UserProfile(
                userId = "15000000000",
                phone = "15000000000",
                nickname = "Syan",
                avatarUrl = "https://example.com/me.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 2_000L
            )
        )

        fixture.viewModel.start()
        runCurrent()

        val self = fixture.viewModel.state.value.selfEntry
        assertEquals("Syan", self?.displayName)
        assertEquals("https://example.com/me.jpg", self?.avatarUrl)
    }

    private class Fixture(
        scope: TestScope,
        userId: String,
        friendUserIds: List<String> = listOf("13900113900"),
        friendContacts: List<FriendContact> = friendUserIds.mapIndexed { index, userId ->
            FriendContact(userId, profileUpdatedAt = 0L, sortOrder = index)
        },
        profileApiOverride: ProfileApi = FakeProfileApi()
    ) {
        val profileDao = InMemoryUserProfileDao()
        val friendContactDao = InMemoryFriendContactDao()
        private val profileRepository = ProfileRepository(profileDao, profileApiOverride)
        val contactApi = FakeContactApi(friendContacts)
        val contactRepository = ContactRepository(contactApi, friendContactDao)
        val viewModel = ContactListViewModel(
            session = AuthSession("mock-token-$userId", userId, userId, expiresAtMillis = 2_000L),
            profileRepository = profileRepository,
            contactRepository = contactRepository,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeProfileApi : ProfileApi {
        val requestedBatchUserIds: MutableList<List<String>> = mutableListOf()

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            requestedBatchUserIds += userIds
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.buyansong.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    private class BlockingBatchProfileApi : ProfileApi {
        private val neverCompletes = CompletableDeferred<ProfileBatchResult>()

        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return neverCompletes.await()
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.buyansong.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }

    private class FakeContactApi(private val friendContacts: List<FriendContact>) : ContactApi {
        var requestCount = 0

        override suspend fun friends(accessToken: String): ContactIdsResult {
            requestCount += 1
            return ContactIdsResult.Success(
                userIds = friendContacts.map { it.userId },
                contacts = friendContacts
            )
        }
    }

    private fun profile(userId: String, updatedAt: Long): UserProfile {
        return UserProfile(
            userId = userId,
            phone = userId,
            nickname = userId,
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = updatedAt
        )
    }
}
