package com.codex.im.contacts

import com.codex.im.storage.FriendContact
import com.codex.im.storage.FriendContactDao
import com.codex.im.storage.InMemoryFriendContactDao

class ContactRepository(
    private val contactApi: ContactApi,
    private val friendContactDao: FriendContactDao = InMemoryFriendContactDao()
) {
    fun cachedFriends(ownerUserId: String): List<FriendContact> {
        return friendContactDao.contactsForOwner(ownerUserId)
    }

    fun cachedFriendUserIds(): List<String> = emptyList()

    fun cacheFriendUserIds(userIds: List<String>) {
        friendContactDao.replaceForOwner(
            ownerUserId = "",
            contacts = userIds.distinct()
                .filter { it.isNotBlank() }
                .mapIndexed { index, userId -> FriendContact(userId, profileUpdatedAt = 0L, sortOrder = index) }
        )
    }

    suspend fun friendUserIds(accessToken: String): List<String> {
        return when (val result = contactApi.friends(accessToken)) {
            is ContactIdsResult.Success -> normalizedContacts(result.contacts).map { it.userId }
            is ContactIdsResult.Failure -> emptyList()
        }
    }

    suspend fun refreshFriends(accessToken: String, ownerUserId: String): List<FriendContact> {
        return when (val result = contactApi.friends(accessToken)) {
            is ContactIdsResult.Success -> {
                val contacts = normalizedContacts(result.contacts)
                friendContactDao.replaceForOwner(ownerUserId, contacts)
                friendContactDao.contactsForOwner(ownerUserId)
            }
            is ContactIdsResult.Failure -> cachedFriends(ownerUserId)
        }
    }

    private fun normalizedContacts(contacts: List<FriendContact>): List<FriendContact> {
        return contacts
            .filter { it.userId.isNotBlank() }
            .distinctBy { it.userId }
            .sortedBy { it.sortOrder }
    }
}
