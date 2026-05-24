package com.codex.im.profile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OkHttpProfileApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : ProfileApi {
    override suspend fun me(accessToken: String): ProfileResult {
        return getProfile("/users/me", accessToken)
    }

    override suspend fun user(accessToken: String, userId: String): ProfileResult {
        return getProfile("/users/${userId.escapePath()}", accessToken)
    }

    override suspend fun batch(accessToken: String, userIds: List<String>): ProfileBatchResult {
        return withContext(Dispatchers.IO) {
            val idsJson = userIds.joinToString(",") { "\"${it.escapeJson()}\"" }
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/users/batch")
                .header("Authorization", "Bearer $accessToken")
                .post("""{"userIds":[$idsJson]}""".toRequestBody(JSON))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ProfileBatchResult.Failure("HTTP ${response.code}")
                    }
                    ProfileJsonParser.parseProfiles(body)
                }
            } catch (error: IOException) {
                ProfileBatchResult.Failure(error.message ?: "Network error")
            } catch (error: RuntimeException) {
                ProfileBatchResult.Failure(error.message ?: "Network error")
            }
        }
    }

    override suspend fun updateMe(
        accessToken: String,
        nickname: String,
        avatarUrl: String?,
        avatarObjectKey: String?
    ): ProfileResult {
        return withContext(Dispatchers.IO) {
            val avatarUrlJson = avatarUrl?.let { "\"${it.escapeJson()}\"" } ?: "null"
            val avatarObjectKeyJson = avatarObjectKey?.let { "\"${it.escapeJson()}\"" } ?: "null"
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/users/me")
                .header("Authorization", "Bearer $accessToken")
                .put(
                    """
                    {"nickname":"${nickname.escapeJson()}","avatarUrl":$avatarUrlJson,"avatarObjectKey":$avatarObjectKeyJson}
                    """.trimIndent().toRequestBody(JSON)
                )
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ProfileResult.Failure("HTTP ${response.code}")
                    }
                    ProfileJsonParser.parseProfile(body)
                }
            } catch (error: IOException) {
                ProfileResult.Failure(error.message ?: "Network error")
            } catch (error: RuntimeException) {
                ProfileResult.Failure(error.message ?: "Network error")
            }
        }
    }

    private suspend fun getProfile(path: String, accessToken: String): ProfileResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ProfileResult.Failure("HTTP ${response.code}")
                    }
                    ProfileJsonParser.parseProfile(body)
                }
            } catch (error: IOException) {
                ProfileResult.Failure(error.message ?: "Network error")
            } catch (error: RuntimeException) {
                ProfileResult.Failure(error.message ?: "Network error")
            }
        }
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun String.escapePath(): String {
        return replace("/", "")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
