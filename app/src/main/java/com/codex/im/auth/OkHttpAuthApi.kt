package com.codex.im.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OkHttpAuthApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : AuthApi {
    override suspend fun login(username: String, password: String): AuthResult {
        return postAuth("/login", username, password)
    }

    override suspend fun register(username: String, password: String): AuthResult {
        return postAuth("/register", username, password)
    }

    private suspend fun postAuth(path: String, username: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            val body = """{"username":"${username.escapeJson()}","password":"${password.escapeJson()}"}"""
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext AuthResult.Failure(
                            AuthJsonParser.parse(responseBody).failureMessageOrNull()
                                ?: "HTTP ${response.code}"
                        )
                    }
                    AuthJsonParser.parse(responseBody)
                }
            } catch (error: IOException) {
                AuthResult.Failure(error.message ?: "Network error")
            } catch (error: RuntimeException) {
                AuthResult.Failure(error.message ?: "Network error")
            }
        }
    }

    private fun AuthResult.failureMessageOrNull(): String? {
        return (this as? AuthResult.Failure)?.message
    }

    private fun String.escapeJson(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
