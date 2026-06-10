package com.buyansong.im.storage

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class AndroidGroupDao(private val database: SQLiteDatabase) : GroupDao {
    override fun upsertGroup(group: GroupInfo) {
        database.insertWithOnConflict("groups", null, group.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun replaceMembers(groupId: String, members: List<GroupMember>) {
        database.delete("group_members", "group_id = ?", arrayOf(groupId))
        members.forEach { member ->
            database.insertWithOnConflict("group_members", null, member.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    override fun findGroup(groupId: String): GroupInfo? {
        return database.query(
            "groups",
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toGroupInfo() else null
        }
    }

    override fun members(groupId: String): List<GroupMember> {
        return database.query(
            "group_members",
            null,
            "group_id = ?",
            arrayOf(groupId),
            null,
            null,
            "joined_at ASC, user_id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toGroupMember())
                }
            }
        }
    }

    override fun groupIdsForUser(userId: String): List<String> {
        return database.query(
            "group_members",
            arrayOf("group_id"),
            "user_id = ?",
            arrayOf(userId),
            null,
            null,
            "group_id ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(cursor.getColumnIndexOrThrow("group_id")))
                }
            }
        }
    }

    override fun groupsForUser(userId: String): List<GroupInfo> {
        return database.query(
            "groups",
            null,
            null,
            null,
            null,
            null,
            "updated_at DESC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toGroupInfo())
                }
            }
        }
    }

    private fun GroupInfo.toValues(): ContentValues {
        return ContentValues().apply {
            put("group_id", groupId)
            put("name", name)
            if (avatarUrl == null) putNull("avatar_url") else put("avatar_url", avatarUrl)
            put("owner_id", ownerId)
            put("created_at", createdAt)
            put("updated_at", updatedAt)
            put("member_count", memberCount)
        }
    }

    private fun GroupMember.toValues(): ContentValues {
        return ContentValues().apply {
            put("group_id", groupId)
            put("user_id", userId)
            put("display_name", displayName)
            if (avatarUrl == null) putNull("avatar_url") else put("avatar_url", avatarUrl)
            put("role", role.name)
            put("joined_at", joinedAt)
            put("updated_at", updatedAt)
            put("profile_version", profileVersion)
        }
    }

    private fun Cursor.toGroupInfo(): GroupInfo {
        val avatarUrlIndex = getColumnIndexOrThrow("avatar_url")
        return GroupInfo(
            groupId = getString(getColumnIndexOrThrow("group_id")),
            name = getString(getColumnIndexOrThrow("name")),
            avatarUrl = if (isNull(avatarUrlIndex)) null else getString(avatarUrlIndex),
            ownerId = getString(getColumnIndexOrThrow("owner_id")),
            createdAt = getLong(getColumnIndexOrThrow("created_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            memberCount = getInt(getColumnIndexOrThrow("member_count"))
        )
    }

    private fun Cursor.toGroupMember(): GroupMember {
        val avatarUrlIndex = getColumnIndexOrThrow("avatar_url")
        return GroupMember(
            groupId = getString(getColumnIndexOrThrow("group_id")),
            userId = getString(getColumnIndexOrThrow("user_id")),
            displayName = getString(getColumnIndexOrThrow("display_name")),
            avatarUrl = if (isNull(avatarUrlIndex)) null else getString(avatarUrlIndex),
            role = GroupMemberRole.valueOf(getString(getColumnIndexOrThrow("role"))),
            joinedAt = getLong(getColumnIndexOrThrow("joined_at")),
            updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
            profileVersion = getLong(getColumnIndexOrThrow("profile_version"))
        )
    }
}
