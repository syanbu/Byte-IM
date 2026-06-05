package com.codex.im.contacts

interface ContactApi {
    suspend fun friends(accessToken: String): ContactIdsResult
}

sealed class ContactIdsResult {
    data class Success(val userIds: List<String>) : ContactIdsResult()
    data class Failure(val message: String) : ContactIdsResult()
}
