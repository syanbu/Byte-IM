package com.codex.im.contacts

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
            val ids = payload.optionalArray("friendUserIds")
                ?.mapNotNull { value ->
                    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        value.asString
                    } else {
                        null
                    }
                }
                ?: emptyList()
            ContactIdsResult.Success(ids)
        } catch (error: RuntimeException) {
            ContactIdsResult.Failure(error.message ?: "好友响应无效")
        }
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
}
