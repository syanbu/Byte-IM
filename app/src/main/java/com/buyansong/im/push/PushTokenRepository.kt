package com.buyansong.im.push

class PushTokenRepository(
    private val api: PushApi,
    private val tokenStore: MockPushTokenStore,
    private val deviceIdProvider: () -> String
) {
    suspend fun register(accessToken: String, userId: String): PushSimpleResult {
        tokenStore.saveLastKnownUserId(userId)
        return api.registerToken(
            accessToken = accessToken,
            pushToken = tokenStore.getOrCreateToken(),
            platform = "android",
            deviceId = deviceIdProvider()
        )
    }

    suspend fun unregister(accessToken: String, userId: String): PushSimpleResult {
        val result = api.unregisterToken(accessToken)
        tokenStore.clearToken()
        tokenStore.clearLastKnownUserId(userId)
        return result
    }
}
