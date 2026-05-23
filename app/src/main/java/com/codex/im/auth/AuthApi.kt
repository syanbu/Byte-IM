package com.codex.im.auth

interface AuthApi {
    suspend fun login(phone: String, password: String): AuthResult

    suspend fun register(phone: String, password: String): AuthResult

    suspend fun refresh(refreshToken: String): AuthResult

    suspend fun logout(refreshToken: String): AuthResult
}
