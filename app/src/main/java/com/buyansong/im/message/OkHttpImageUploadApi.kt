package com.buyansong.im.message

import com.buyansong.im.profile.AvatarPutResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OkHttpImageUploadApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : ImageUploadApi {
    override suspend fun requestUploadTargets(
        accessToken: String,
        messageId: String,
        contentType: String
    ): ImageUploadTargetsResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/oss/message-image/upload-targets")
                .header("Authorization", "Bearer $accessToken")
                .post(
                    """
                    {"messageId":"${messageId.escapeJson()}","contentType":"${contentType.escapeJson()}"}
                    """.trimIndent().toRequestBody(JSON)
                )
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ImageUploadTargetsResult.Failure("HTTP ${response.code}")
                    }
                    ImageUploadJsonParser.parseTargets(body)
                }
            } catch (error: IOException) {
                ImageUploadTargetsResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                ImageUploadTargetsResult.Failure(error.message ?: "网络异常")
            }
        }
    }

    override suspend fun upload(uploadUrl: String, contentType: String, bytes: ByteArray): AvatarPutResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(uploadUrl)
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        AvatarPutResult.Success
                    } else {
                        AvatarPutResult.Failure("HTTP ${response.code}")
                    }
                }
            } catch (error: IOException) {
                AvatarPutResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                AvatarPutResult.Failure(error.message ?: "网络异常")
            }
        }
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
