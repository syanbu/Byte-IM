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
    override suspend fun login(phone: String, password: String): AuthResult {
        return postAuth("/login", phone, password)
    }

    override suspend fun register(phone: String, password: String): AuthResult {
        return postAuth("/register", phone, password)
    }

    override suspend fun refresh(refreshToken: String): AuthResult {
        return postRefreshToken("/refresh", refreshToken)
    }

    override suspend fun logout(refreshToken: String): AuthResult {
        return when (val result = postRefreshToken("/logout", refreshToken)) {
            is AuthResult.Failure -> result
            is AuthResult.Success -> AuthResult.LoggedOut
            AuthResult.LoggedOut -> AuthResult.LoggedOut
        }
    }

    private suspend fun postAuth(path: String, phone: String, password: String): AuthResult {
        return withContext(Dispatchers.IO) {
            val body = """{"phone":"${phone.escapeJson()}","password":"${password.escapeJson()}"}"""
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext httpFailure(
                            code = response.code,
                            responseBody = responseBody,
                            unauthorizedKind = AuthFailureKind.INVALID_CREDENTIALS
                        )
                    }
                    AuthJsonParser.parse(responseBody)
                }
            } catch (error: IOException) {
                AuthResult.Failure(
                    message = error.message ?: "网络异常",
                    kind = AuthFailureKind.NETWORK
                )
            } catch (error: RuntimeException) {
                AuthResult.Failure(
                    message = error.message ?: "网络异常",
                    kind = AuthFailureKind.NETWORK
                )
            }
        }
    }

    private suspend fun postRefreshToken(path: String, refreshToken: String): AuthResult {
        return withContext(Dispatchers.IO) {
            val body = """{"refreshToken":"${refreshToken.escapeJson()}"}"""
                .toRequestBody(JSON)
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext httpFailure(
                            code = response.code,
                            responseBody = responseBody,
                            unauthorizedKind = AuthFailureKind.SESSION_EXPIRED
                        )
                    }
                    if (path == "/logout") {
                        return@withContext AuthResult.LoggedOut
                    }
                    AuthJsonParser.parse(responseBody)
                }
            } catch (error: IOException) {
                AuthResult.Failure(
                    message = error.message ?: "网络异常",
                    kind = AuthFailureKind.NETWORK
                )
            } catch (error: RuntimeException) {
                AuthResult.Failure(
                    message = error.message ?: "网络异常",
                    kind = AuthFailureKind.NETWORK
                )
            }
        }
    }

    private fun httpFailure(
        code: Int,
        responseBody: String,
        unauthorizedKind: AuthFailureKind
    ): AuthResult.Failure {
        val message = AuthJsonParser.parse(responseBody).failureMessageOrNull() ?: "HTTP $code"
        val kind = when {
            code == 401 || code == 403 -> unauthorizedKind
            code >= 500 -> AuthFailureKind.SERVER
            else -> AuthFailureKind.UNKNOWN
        }
        return AuthResult.Failure(message = message, kind = kind)
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
