package com.codex.im.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class FriendContactDaoContractTest {
    @Test
    fun replaceForOwnerStoresOrderedFriendVersions() {
        val dao = InMemoryFriendContactDao()

        dao.replaceForOwner(
            ownerUserId = "15000000000",
            contacts = listOf(
                FriendContact("15000000004", profileUpdatedAt = 4_000L, sortOrder = 1),
                FriendContact("15000000003", profileUpdatedAt = 3_000L, sortOrder = 0)
            )
        )

        val contacts = dao.contactsForOwner("15000000000")
        assertEquals(listOf("15000000003", "15000000004"), contacts.map { it.userId })
        assertEquals(listOf(3_000L, 4_000L), contacts.map { it.profileUpdatedAt })
    }

    @Test
    fun replaceForOwnerKeepsAccountsSeparate() {
        val dao = InMemoryFriendContactDao()

        dao.replaceForOwner(
            ownerUserId = "15000000000",
            contacts = listOf(FriendContact("15000000003", profileUpdatedAt = 3_000L, sortOrder = 0))
        )
        dao.replaceForOwner(
            ownerUserId = "16000000000",
            contacts = listOf(FriendContact("16000000003", profileUpdatedAt = 6_000L, sortOrder = 0))
        )

        assertEquals(listOf("15000000003"), dao.contactsForOwner("15000000000").map { it.userId })
        assertEquals(listOf("16000000003"), dao.contactsForOwner("16000000000").map { it.userId })
    }
}
