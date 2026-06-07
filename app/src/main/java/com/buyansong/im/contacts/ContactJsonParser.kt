package com.buyansong.im.contacts

import com.buyansong.im.storage.FriendContact
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ContactJsonParser {
    fun parseFriendIds(json: String): ContactIdsResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return ContactIdsResult.Failure(root.optionalString("message") ?: "好友请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            val contacts = payload.optionalArray("friends")
                ?.mapIndexedNotNull { index, value -> value.toFriendContact(index) }
                ?: payload.legacyFriendContacts()
            ContactIdsResult.Success(
                userIds = contacts.map { it.userId },
                contacts = contacts
            )
        } catch (error: RuntimeException) {
            ContactIdsResult.Failure(error.message ?: "好友响应无效")
        }
    }

    private fun JsonObject.legacyFriendContacts(): List<FriendContact> {
        return optionalArray("friendUserIds")
            ?.mapIndexedNotNull { index, value ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    FriendContact(value.asString, profileUpdatedAt = 0L, sortOrder = index)
                } else {
                    null
                }
            }
            ?: emptyList()
    }

    private fun JsonElement.toFriendContact(sortOrder: Int): FriendContact? {
        if (!isJsonObject) {
            return null
        }
        val friend = asJsonObject
        val userId = friend.optionalString("userId") ?: friend.optionalString("friendUserId") ?: return null
        val profileUpdatedAt = friend.optionalLong("profileUpdatedAt")
            ?: friend.optionalLong("profile_updated_at")
            ?: friend.optionalLong("updatedAt")
            ?: friend.optionalLong("updated_at")
            ?: 0L
        return FriendContact(userId = userId, profileUpdatedAt = profileUpdatedAt, sortOrder = sortOrder)
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
    }

    private fun JsonObject.optionalArray(name: String): JsonArray? {
        val value: JsonElement = get(name) ?: return null
        return if (value.isJsonArray) value.asJsonArray else null
    }

    private fun JsonObject.optionalString(name: String): String? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isString) value.asString else null
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asInt else null
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = get(name) ?: return null
        return if (value.isJsonPrimitive && value.asJsonPrimitive.isNumber) value.asLong else null
    }
}
