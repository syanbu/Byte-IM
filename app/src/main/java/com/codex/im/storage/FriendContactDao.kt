package com.codex.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

data class FriendContact(
    val userId: String,
    val profileUpdatedAt: Long,
    val sortOrder: Int
)

interface FriendContactDao {
    fun contactsForOwner(ownerUserId: String): List<FriendContact>

    fun replaceForOwner(ownerUserId: String, contacts: List<FriendContact>)
}

class InMemoryFriendContactDao : FriendContactDao {
    private val contactsByOwner = linkedMapOf<String, List<FriendContact>>()

    override fun contactsForOwner(ownerUserId: String): List<FriendContact> {
        return contactsByOwner[ownerUserId].orEmpty()
    }

    override fun replaceForOwner(ownerUserId: String, contacts: List<FriendContact>) {
        contactsByOwner[ownerUserId] = normalizedContacts(contacts)
    }
}

class AndroidFriendContactDao(private val database: SQLiteDatabase) : FriendContactDao {
    override fun contactsForOwner(ownerUserId: String): List<FriendContact> {
        return database.query(
            "friend_contacts",
            null,
            "owner_user_id = ?",
            arrayOf(ownerUserId),
            null,
            null,
            "sort_order ASC"
        ).use { cursor ->
            val contacts = mutableListOf<FriendContact>()
            while (cursor.moveToNext()) {
                contacts += cursor.toFriendContact()
            }
            contacts
        }
    }

    override fun replaceForOwner(ownerUserId: String, contacts: List<FriendContact>) {
        database.beginTransaction()
        try {
            database.delete("friend_contacts", "owner_user_id = ?", arrayOf(ownerUserId))
            normalizedContacts(contacts).forEach { contact ->
                database.insertWithOnConflict(
                    "friend_contacts",
                    null,
                    contact.toValues(ownerUserId),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun FriendContact.toValues(ownerUserId: String): ContentValues {
        return ContentValues().apply {
            put("owner_user_id", ownerUserId)
            put("friend_user_id", userId)
            put("profile_updated_at", profileUpdatedAt)
            put("sort_order", sortOrder)
        }
    }

    private fun Cursor.toFriendContact(): FriendContact {
        return FriendContact(
            userId = getString(getColumnIndexOrThrow("friend_user_id")),
            profileUpdatedAt = getLong(getColumnIndexOrThrow("profile_updated_at")),
            sortOrder = getInt(getColumnIndexOrThrow("sort_order"))
        )
    }
}

private fun normalizedContacts(contacts: List<FriendContact>): List<FriendContact> {
    return contacts
        .filter { it.userId.isNotBlank() }
        .distinctBy { it.userId }
        .sortedBy { it.sortOrder }
}
