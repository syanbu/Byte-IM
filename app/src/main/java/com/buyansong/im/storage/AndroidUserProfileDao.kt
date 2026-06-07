package com.buyansong.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidUserProfileDao(private val database: SQLiteDatabase) : UserProfileDao {
    override fun upsert(profile: UserProfile) {
        database.insertWithOnConflict("user_profiles", null, profile.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun upsertAll(profiles: List<UserProfile>) {
        database.beginTransaction()
        try {
            profiles.forEach(::upsert)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    override fun findByUserId(userId: String): UserProfile? {
        return database.query(
            "user_profiles",
            null,
            "user_id = ?",
            arrayOf(userId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toUserProfile() else null
        }
    }

    override fun findByUserIds(userIds: List<String>): List<UserProfile> {
        if (userIds.isEmpty()) {
            return emptyList()
        }
        return userIds.mapNotNull(::findByUserId)
    }

    private fun UserProfile.toValues(): ContentValues {
        return ContentValues().apply {
            put("user_id", userId)
            put("phone", phone)
            put("nickname", nickname)
            if (avatarUrl == null) putNull("avatar_url") else put("avatar_url", avatarUrl)
            put("avatar_updated_at", avatarUpdatedAt)
            put("updated_at", updatedAt)
            if (gender == null) putNull("gender") else put("gender", gender.name)
            if (signature == null) putNull("signature") else put("signature", signature)
        }
    }

    private fun Cursor.toUserProfile(): UserProfile {
        val avatarUrlIndex = getColumnIndexOrThrow("avatar_url")
        val genderIndex = getColumnIndexOrThrow("gender")
        val signatureIndex = getColumnIndexOrThrow("signature")
        return UserProfile(
            userId = getString(getColumnIndexOrThrow("user_id")),
            phone = getString(getColumnIndexOrThrow("phone")),
            nickname = getString(getColumnIndexOrThrow("nickname")),
            avatarUrl = if (isNull(avatarUrlIndex)) null else getString(avatarUrlIndex),
            avatarUpdatedAt = getLong(getColumnIndexOrThrow("avatar_updated_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            gender = if (isNull(genderIndex)) null else runCatching { Gender.valueOf(getString(genderIndex)) }.getOrNull(),
            signature = if (isNull(signatureIndex)) null else getString(signatureIndex)
        )
    }
}
