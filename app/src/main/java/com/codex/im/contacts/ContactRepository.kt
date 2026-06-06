package com.codex.im.contacts

class ContactRepository(
    private val contactApi: ContactApi
) {
    private var cachedFriendUserIds: List<String> = emptyList()

    fun cachedFriendUserIds(): List<String> = cachedFriendUserIds

    fun cacheFriendUserIds(userIds: List<String>) {
        cachedFriendUserIds = userIds.distinct().filter { it.isNotBlank() }
    }

    suspend fun friendUserIds(accessToken: String): List<String> {
        return when (val result = contactApi.friends(accessToken)) {
            is ContactIdsResult.Success -> {
                result.userIds.distinct()
                    .filter { it.isNotBlank() }
                    .also { cachedFriendUserIds = it }
            }
            is ContactIdsResult.Failure -> cachedFriendUserIds
        }
    }
}
