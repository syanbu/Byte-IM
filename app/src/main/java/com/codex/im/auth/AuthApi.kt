package com.codex.im.auth

interface AuthApi {
    suspend fun login(username: String, password: String): AuthResult

    suspend fun register(username: String, password: String): AuthResult
}
