package com.buyansong.im.push

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class PushPendingItem(
    val pushId: Long,
    val senderId: String,
    val conversationId: String,
    val messageId: String,
    val messageType: String,
    val preview: String,
    val serverSeq: Long,
    val serverTime: Long
)

sealed class PushSimpleResult {
    data object Success : PushSimpleResult()
    data class Failure(val message: String) : PushSimpleResult()
}

sealed class PushPendingResult {
    data class Success(
        val pending: List<PushPendingItem>,
        val latestPushId: Long
    ) : PushPendingResult()

    data class Failure(val message: String) : PushPendingResult()
}

interface PushApi {
    suspend fun registerToken(accessToken: String, pushToken: String, platform: String, deviceId: String): PushSimpleResult

    suspend fun unregisterToken(accessToken: String): PushSimpleResult

    suspend fun pending(accessToken: String, sincePushId: Long, limit: Int): PushPendingResult

    suspend fun ack(accessToken: String, pushIds: List<Long>): PushSimpleResult
}

class OkHttpPushApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : PushApi {
    override suspend fun registerToken(
        accessToken: String,
        pushToken: String,
        platform: String,
        deviceId: String
    ): PushSimpleResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/push/register-token")
                .header("Authorization", "Bearer $accessToken")
                .post(
                    """{"pushToken":"${pushToken.escapeJson()}","platform":"${platform.escapeJson()}","deviceId":"${deviceId.escapeJson()}"}"""
                        .toRequestBody(JSON)
                )
                .build()
            executeSimple(request)
        }
    }

    override suspend fun unregisterToken(accessToken: String): PushSimpleResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/push/unregister-token")
                .header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody(JSON))
                .build()
            executeSimple(request)
        }
    }

    override suspend fun pending(accessToken: String, sincePushId: Long, limit: Int): PushPendingResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/push/pending?since=$sincePushId&limit=$limit")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext PushPendingResult.Failure("HTTP ${response.code}")
                    }
                    PushPendingJsonParser.parse(body)
                }
            } catch (error: IOException) {
                PushPendingResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                PushPendingResult.Failure(error.message ?: "push 响应无效")
            }
        }
    }

    override suspend fun ack(accessToken: String, pushIds: List<Long>): PushSimpleResult {
        return withContext(Dispatchers.IO) {
            val ids = pushIds.joinToString(",")
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/push/ack")
                .header("Authorization", "Bearer $accessToken")
                .post("""{"pushIds":[$ids]}""".toRequestBody(JSON))
                .build()
            executeSimple(request)
        }
    }

    private fun executeSimple(request: Request): PushSimpleResult {
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    PushSimpleResult.Success
                } else {
                    PushSimpleResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (error: IOException) {
            PushSimpleResult.Failure(error.message ?: "网络异常")
        }
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

object PushPendingJsonParser {
    fun parse(json: String): PushPendingResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val code = root.optionalInt("code")
            if (code != null && code != 0) {
                return PushPendingResult.Failure(root.optionalString("message") ?: "push 请求失败")
            }
            val payload = root.optionalObject("data") ?: root
            val rows = payload.optionalArray("pending")
                ?.map { element ->
                    val row = element.asJsonObject
                    PushPendingItem(
                        pushId = row.requiredLong("pushId"),
                        senderId = row.requiredString("senderId"),
                        conversationId = row.requiredString("conversationId"),
                        messageId = row.requiredString("messageId"),
                        messageType = row.optionalString("messageType") ?: "TEXT",
                        preview = row.optionalString("preview") ?: "",
                        serverSeq = row.optionalLong("serverSeq") ?: 0L,
                        serverTime = row.optionalLong("serverTime") ?: 0L
                    )
                }
                .orEmpty()
            PushPendingResult.Success(
                pending = rows,
                latestPushId = payload.optionalLong("latestPushId") ?: rows.maxOfOrNull { it.pushId } ?: 0L
            )
        } catch (error: RuntimeException) {
            PushPendingResult.Failure(error.message ?: "push 响应无效")
        }
    }

    private fun JsonObject.requiredString(name: String): String {
        return optionalString(name) ?: error("$name required")
    }

    private fun JsonObject.requiredLong(name: String): Long {
        return optionalLong(name) ?: error("$name required")
    }

    private fun JsonObject.optionalString(name: String): String? {
        return if (has(name) && !get(name).isJsonNull) get(name).asString else null
    }

    private fun JsonObject.optionalLong(name: String): Long? {
        return if (has(name) && !get(name).isJsonNull) get(name).asLong else null
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        return if (has(name) && !get(name).isJsonNull) get(name).asInt else null
    }

    private fun JsonObject.optionalObject(name: String): JsonObject? {
        return if (has(name) && get(name).isJsonObject) getAsJsonObject(name) else null
    }

    private fun JsonObject.optionalArray(name: String) =
        if (has(name) && get(name).isJsonArray) getAsJsonArray(name) else null
}
