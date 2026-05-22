package com.codex.im.auth

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class OkHttpAuthApiTest {
    @Test
    fun loginReturnsFailureWhenNetworkThrowsIOException() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("network unavailable") })
            .build()
        val api = OkHttpAuthApi(baseUrl = "http://10.0.2.2:8080", client = client)

        val result = api.login("alice", "123456")

        assertEquals(AuthResult.Failure("network unavailable"), result)
    }
}
