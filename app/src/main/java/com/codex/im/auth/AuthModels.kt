package com.codex.im.auth

data class AuthSession(
    val token: String,
    val userId: String,
    val username: String
)

sealed class AuthResult {
    data class Success(val session: AuthSession) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}
