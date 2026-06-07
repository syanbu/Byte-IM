package com.buyansong.im.profile

import com.google.gson.JsonObject
import com.google.gson.JsonParser

object AvatarUploadJsonParser {
    fun parseTarget(json: String): AvatarUploadResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return AvatarUploadResult.Failure(root.optionalString("message") ?: "头像上传地址请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            AvatarUploadResult.Success(
                AvatarUploadTarget(
                    objectKey = payload.requiredString("objectKey"),
                    uploadUrl = payload.requiredString("uploadUrl"),
                    publicUrl = payload.requiredString("publicUrl"),
                    expiresAt = payload.optionalLong("expiresAt") ?: 0L
                )
            )
        } catch (error: RuntimeException) {
            AvatarUploadResult.Failure(error.message ?: "头像上传响应无效")
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name) ?: error("Missing $name")
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
