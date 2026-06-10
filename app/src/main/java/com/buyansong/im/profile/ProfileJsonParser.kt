package com.buyansong.im.profile

import com.buyansong.im.storage.Gender
import com.buyansong.im.storage.UserProfile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ProfileJsonParser {
    fun parseProfile(json: String): ProfileResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return ProfileResult.Failure(root.optionalString("message") ?: "资料请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            ProfileResult.Success(payload.toUserProfile())
        } catch (error: RuntimeException) {
            ProfileResult.Failure(error.message ?: "资料响应无效")
        }
    }

    fun parseProfiles(json: String): ProfileBatchResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return ProfileBatchResult.Failure(root.optionalString("message") ?: "资料请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            val profiles = payload.optionalArray("profiles")
                ?.map { it.asJsonObject.toUserProfile() }
                ?: emptyList()
            ProfileBatchResult.Success(profiles)
        } catch (error: RuntimeException) {
            ProfileBatchResult.Failure(error.message ?: "资料响应无效")
        }
    }

    private fun JsonObject.toUserProfile(): UserProfile {
        val userId = requiredString("userId")
        return UserProfile(
            userId = userId,
            phone = optionalString("phone") ?: userId,
            nickname = optionalString("nickname") ?: optionalString("username") ?: userId,
            avatarUrl = optionalString("avatarUrl") ?: optionalString("avatar_url"),
            avatarUpdatedAt = optionalLong("avatarUpdatedAt") ?: optionalLong("avatar_updated_at") ?: 0L,
            updatedAt = optionalLong("updatedAt") ?: optionalLong("updated_at") ?: 0L,
            gender = optionalString("gender")?.let { runCatching { Gender.valueOf(it) }.getOrNull() },
            signature = optionalString("signature"),
            profileVersion = optionalLong("profileVersion") ?: optionalLong("profile_version") ?: 0L
        )
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name) ?: error("Missing $name")
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
