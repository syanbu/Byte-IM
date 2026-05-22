package com.codex.im.auth

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AuthJsonParser {
    fun parse(json: String): AuthResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return AuthResult.Failure(root.optionalString("message") ?: "Authentication failed")
            }

            val payload = root.optionalObject("data") ?: root
            val token = payload.optionalString("token")
            val userId = payload.optionalString("userId") ?: payload.optionalString("user_id")
            val username = payload.optionalString("username") ?: payload.optionalString("name")

            if (token.isNullOrBlank() || userId.isNullOrBlank() || username.isNullOrBlank()) {
                AuthResult.Failure(root.optionalString("message") ?: "Invalid authentication response")
            } else {
                AuthResult.Success(AuthSession(token = token, userId = userId, username = username))
            }
        } catch (_: RuntimeException) {
            AuthResult.Failure("Invalid authentication response")
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
}
