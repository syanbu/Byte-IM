package com.codex.im.contacts

import com.codex.im.auth.AuthSession
import com.codex.im.profile.ProfileApi
import com.codex.im.profile.ProfileBatchResult
import com.codex.im.profile.ProfileRepository
import com.codex.im.profile.ProfileResult
import com.codex.im.storage.InMemoryUserProfileDao
import com.codex.im.storage.UserProfile
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
    fun current138UserSees139DemoContact() = runTest {
        val fixture = Fixture(this, userId = "13800113800")

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13267100423", "13900113900", "17724734511"),
            fixture.viewModel.state.value.items.map { it.userId }
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun current139UserSees138DemoContact() = runTest {
        val fixture = Fixture(this, userId = "13900113900")

        fixture.viewModel.start()
        runCurrent()

        assertEquals(
            listOf("13267100423", "13800113800", "17724734511"),
            fixture.viewModel.state.value.items.map { it.userId }
        )
    }

    @Test
    fun demoContactResolverMakesFourDemoAccountsMutualContacts() {
        assertEquals(
            listOf("13267100423", "13800113800", "13900113900"),
            DemoContactResolver.contactsFor("17724734511")
        )
        assertEquals(
            listOf("13800113800", "13900113900", "17724734511"),
            DemoContactResolver.contactsFor("13267100423")
        )
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun contactUsesProfileNicknameAndAvatarFromCache() = runTest {
        val fixture = Fixture(this, userId = "13800113800")
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
    fun openContactExposesNavigationTargetWithoutCreatingConversation() = runTest {
        val fixture = Fixture(this, userId = "13800113800")

        fixture.viewModel.openContact("13900113900")
        runCurrent()

        assertEquals("13900113900", fixture.viewModel.state.value.navigationTargetPeerId)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeNavigationTargetClearsIt() = runTest {
        val fixture = Fixture(this, userId = "13800113800")

        fixture.viewModel.openContact("13900113900")
        runCurrent()
        fixture.viewModel.consumeNavigationTarget()

        assertEquals(null, fixture.viewModel.state.value.navigationTargetPeerId)
    }

    private class Fixture(scope: TestScope, userId: String) {
        val profileDao = InMemoryUserProfileDao()
        private val profileRepository = ProfileRepository(profileDao, FakeProfileApi())
        val viewModel = ContactListViewModel(
            session = AuthSession("mock-token-$userId", userId, userId, expiresAtMillis = 2_000L),
            profileRepository = profileRepository,
            contactResolver = DemoContactResolver::contactsFor,
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler)
        )
    }

    private class FakeProfileApi : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun user(accessToken: String, userId: String): ProfileResult = ProfileResult.Failure("unused")

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
            return ProfileBatchResult.Success(emptyList())
        }

        override suspend fun updateMe(
            accessToken: String,
            nickname: String,
            avatarUrl: String?,
            avatarObjectKey: String?,
            gender: com.codex.im.storage.Gender?,
            signature: String?
        ): ProfileResult = ProfileResult.Failure("unused")
    }
}
