package com.codex.im.message

import com.codex.im.profile.AvatarUploadTarget
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object ImageUploadJsonParser {
    fun parseTargets(json: String): ImageUploadTargetsResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return ImageUploadTargetsResult.Failure(
                    root.optionalString("message") ?: "图片上传地址请求失败"
                )
            }
            val payload = root.optionalObject("data") ?: root
            ImageUploadTargetsResult.Success(
                ImageUploadTargets(
                    messageId = payload.requiredString("messageId"),
                    thumbnail = payload.requiredTarget("thumbnail"),
                    original = payload.requiredTarget("original"),
                    expiresAt = payload.optionalLong("expiresAt") ?: 0L
                )
            )
        } catch (error: RuntimeException) {
            ImageUploadTargetsResult.Failure(error.message ?: "图片上传响应无效")
        }
    }

    private fun JsonObject.requiredTarget(name: String): AvatarUploadTarget {
        val target = optionalObject(name) ?: error("Missing $name")
        return AvatarUploadTarget(
            objectKey = target.requiredString("objectKey"),
            uploadUrl = target.requiredString("uploadUrl"),
            publicUrl = target.requiredString("publicUrl"),
            expiresAt = target.optionalLong("expiresAt") ?: 0L
        )
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
