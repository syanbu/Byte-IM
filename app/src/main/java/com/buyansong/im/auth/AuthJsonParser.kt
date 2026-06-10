package com.buyansong.im.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AuthJsonParser {
    fun parse(json: String): AuthResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return AuthResult.Failure(root.optionalString("message") ?: "认证失败")
            }

            val payload = root.optionalObject("data") ?: root
            val accessToken = payload.optionalString("accessToken") ?: payload.optionalString("token")
            val refreshToken = payload.optionalString("refreshToken")
            val userId = payload.optionalString("userId") ?: payload.optionalString("user_id")
            val nickname = payload.optionalString("nickname")
                ?: payload.optionalString("username")
                ?: payload.optionalString("name")
                ?: userId
            val username = payload.optionalString("username") ?: nickname
            val phone = payload.optionalString("phone") ?: userId
            val avatarUrl = payload.optionalString("avatarUrl") ?: payload.optionalString("avatar_url")
            val avatarUpdatedAt = payload.optionalLong("avatarUpdatedAt")
                ?: payload.optionalLong("avatar_updated_at")
                ?: 0L
            val profileUpdatedAt = payload.optionalLong("profileUpdatedAt")
                ?: payload.optionalLong("profile_updated_at")
                ?: payload.optionalLong("updatedAt")
                ?: payload.optionalLong("updated_at")
                ?: 0L
            val profileVersion = payload.optionalLong("profileVersion")
                ?: payload.optionalLong("profile_version")
                ?: 0L
            val accessExpiresAt = payload.optionalLong("accessExpiresAt")
                ?: payload.optionalLong("accessExpiresAtMillis")
                ?: payload.optionalLong("expiresAt")
                ?: payload.optionalLong("expiresAtMillis")
                ?: payload.optionalLong("expires_at")
            val refreshExpiresAt = payload.optionalLong("refreshExpiresAt")
                ?: payload.optionalLong("refreshExpiresAtMillis")
                ?: payload.optionalLong("refresh_expires_at")

            if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || userId.isNullOrBlank() || username.isNullOrBlank() || phone.isNullOrBlank() || nickname.isNullOrBlank() || accessExpiresAt == null || refreshExpiresAt == null) {
                AuthResult.Failure(root.optionalString("message") ?: "认证响应无效")
            } else {
                AuthResult.Success(
                    AuthSession(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        userId = userId,
                        username = username,
                        phone = phone,
                        nickname = nickname,
                        avatarUrl = avatarUrl,
                        avatarUpdatedAt = avatarUpdatedAt,
                        profileUpdatedAt = profileUpdatedAt,
                        profileVersion = profileVersion,
                        accessExpiresAtMillis = accessExpiresAt,
                        refreshExpiresAtMillis = refreshExpiresAt
                    )
                )
            }
        } catch (_: RuntimeException) {
            AuthResult.Failure("认证响应无效")
        }
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        val value = get(name) ?: return null
        return if (value.isJsonObject) value.asJsonObject else null
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
