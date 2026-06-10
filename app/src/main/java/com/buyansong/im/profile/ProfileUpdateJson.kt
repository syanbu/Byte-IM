package com.buyansong.im.profile

import com.buyansong.im.storage.Gender
import com.google.gson.JsonObject

object ProfileUpdateJson {
    fun build(
        nickname: String? = null,
        avatarUrl: String? = null,
        avatarObjectKey: String? = null,
        gender: Gender? = null,
        signature: String? = null
    ): String {
        return JsonObject().apply {
            nickname?.let { addProperty("nickname", it) }
            avatarUrl?.let { addProperty("avatarUrl", it) }
            avatarObjectKey?.let { addProperty("avatarObjectKey", it) }
            gender?.let { addProperty("gender", it.name) }
            signature?.let { addProperty("signature", it) }
        }.toString()
    }
}
