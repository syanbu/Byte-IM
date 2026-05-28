package com.codex.im.auth

data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val username: String,
    val phone: String = userId,
    val nickname: String = username,
    val avatarUrl: String? = null,
    val avatarUpdatedAt: Long = 0L,
    val profileUpdatedAt: Long = 0L,
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long
) {
    constructor(token: String, userId: String, username: String, expiresAtMillis: Long) : this(
        accessToken = token,
        refreshToken = "refresh-$token",
        userId = userId,
        username = username,
        phone = userId,
        nickname = username,
        avatarUrl = null,
        avatarUpdatedAt = 0L,
        profileUpdatedAt = 0L,
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
    data class Failure(
        val message: String,
        val kind: AuthFailureKind = AuthFailureKind.UNKNOWN
    ) : AuthResult()
    data object LoggedOut : AuthResult()
}

enum class AuthFailureKind {
    UNKNOWN,
    NETWORK,
    INVALID_CREDENTIALS,
    SESSION_EXPIRED,
    SERVER
}
