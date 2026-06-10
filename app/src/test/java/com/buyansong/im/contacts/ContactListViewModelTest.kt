package com.buyansong.im.contacts

import com.buyansong.im.auth.AuthSession
import com.buyansong.im.profile.ProfileApi
import com.buyansong.im.profile.ProfileBatchResult
import com.buyansong.im.profile.ProfileResult
import com.buyansong.im.storage.FriendContact
import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.InMemoryFriendContactDao
import com.buyansong.im.storage.InMemoryUserProfileDao
import com.buyansong.im.storage.UserProfile
import com.buyansong.im.profile.ProfileRepository
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactListViewModelTest {
    private class MutableContactApi(
        var contacts: List<FriendContact>
    ) : ContactApi {
        override suspend fun friends(accessToken: String): ContactIdsResult {
            return ContactIdsResult.Success(
                userIds = contacts.map { it.userId },
                contacts = contacts
            )
        }
    }

    private class MutableProfileApi(
        var profilesById: Map<String, UserProfile>
    ) : ProfileApi {
        override suspend fun me(accessToken: String): ProfileResult = ProfileResult.Failure("unused")
        override suspend fun user(accessToken: String, userId: String): ProfileResult {
            return profilesById[userId]?.let(ProfileResult::Success) ?: ProfileResult.Failure("missing")
        }

        override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start_refreshesContactsAgainAfterStopStart() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val contactApi = MutableContactApi(
            contacts = listOf(FriendContact(userId = "u_friend", profileUpdatedAt = 1L, sortOrder = 0))
        )
        val profileApi = MutableProfileApi(
            profilesById = mapOf("u_friend" to profile("u_friend", "Old", updatedAt = 1L, profileVersion = 1L))
        )
        val viewModel = ContactListViewModel(
            session = session(),
            profileRepository = ProfileRepository(InMemoryUserProfileDao(), profileApi),
            contactRepository = ContactRepository(contactApi, InMemoryFriendContactDao()),
            validSessionProvider = { session() },
            scope = this,
            dispatcher = dispatcher
        )

        viewModel.start()
        advanceUntilIdle()
        assertEquals("Old", viewModel.state.value.items.single().displayName)

        viewModel.stop()
        contactApi.contacts = listOf(FriendContact(userId = "u_friend", profileUpdatedAt = 2L, sortOrder = 0))
        profileApi.profilesById = mapOf("u_friend" to profile("u_friend", "New", updatedAt = 2L, profileVersion = 2L))

        viewModel.start()
        advanceUntilIdle()

        assertEquals("New", viewModel.state.value.items.single().displayName)
    }

    private fun session() = AuthSession(
        token = "token",
        userId = "u_self",
        username = "Self",
        expiresAtMillis = Long.MAX_VALUE
    )

    private fun profile(userId: String, nickname: String, updatedAt: Long, profileVersion: Long): UserProfile {
        return UserProfile(
            userId = userId,
            phone = userId,
            nickname = nickname,
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = updatedAt,
            profileVersion = profileVersion
        )
    }
}
