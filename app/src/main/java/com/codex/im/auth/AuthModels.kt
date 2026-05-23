package com.codex.im.auth

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long
) {
    constructor(token: String, userId: String, username: String, expiresAtMillis: Long) : this(
        accessToken = token,
        refreshToken = "refresh-$token",
        userId = userId,
        username = username,
        accessExpiresAtMillis = expiresAtMillis,
        refreshExpiresAtMillis = expiresAtMillis
    )

    val token: String
        get() = accessToken

    val expiresAtMillis: Long
        get() = accessExpiresAtMillis
}

sealed class AuthResult {
    data class Success(val session: AuthSession) : AuthResult()
    data class Failure(val message: String) : AuthResult()
    data object LoggedOut : AuthResult()
}
