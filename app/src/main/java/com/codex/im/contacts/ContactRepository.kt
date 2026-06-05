package com.codex.im.contacts

class ContactRepository(
    private val contactApi: ContactApi
) {
    suspend fun friendUserIds(accessToken: String): List<String> {
        return when (val result = contactApi.friends(accessToken)) {
            is ContactIdsResult.Success -> result.userIds.distinct().filter { it.isNotBlank() }
            is ContactIdsResult.Failure -> emptyList()
        }
    }
}
