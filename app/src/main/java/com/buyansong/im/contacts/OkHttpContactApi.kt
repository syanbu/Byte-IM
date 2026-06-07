package com.buyansong.im.contacts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class OkHttpContactApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : ContactApi {
    override suspend fun friends(accessToken: String): ContactIdsResult {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/friends/me")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext ContactIdsResult.Failure("HTTP ${response.code}")
                    }
                    ContactJsonParser.parseFriendIds(body)
                }
            } catch (error: IOException) {
                ContactIdsResult.Failure(error.message ?: "网络异常")
            } catch (error: RuntimeException) {
                ContactIdsResult.Failure(error.message ?: "网络异常")
            }
        }
    }
}
