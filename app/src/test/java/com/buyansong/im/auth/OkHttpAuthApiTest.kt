package com.buyansong.im.auth

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

class OkHttpAuthApiTest {
    @Test
    fun loginReturnsFailureWhenNetworkThrowsIOException() = runTest {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("network unavailable") })
            .build()
        val api = OkHttpAuthApi(baseUrl = "http://10.0.2.2:8080", client = client)

        val result = api.login("13800138000", "123456")

        assertEquals(
            AuthResult.Failure(
                message = "network unavailable",
                kind = AuthFailureKind.NETWORK
            ),
            result
        )
    }

    @Test
    fun registerSendsPhoneAndPasswordJsonBody() = runTest {
        val capturedBody = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedBody.set(chain.request().bodyToString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"code":0,"message":"ok","data":{"accessToken":"mock-jwt.token","refreshToken":"refresh-a","userId":"13800138000","username":"13800138000","accessExpiresAt":2000,"refreshExpiresAt":9000}}""".toResponseBody())
                    .build()
            })
            .build()
        val api = OkHttpAuthApi(baseUrl = "http://127.0.0.1:8080", client = client)

        api.register("13800138000", "123456")

        assertTrue(capturedBody.get().contains(""""phone":"13800138000""""))
        assertTrue(capturedBody.get().contains(""""password":"123456""""))
    }

    @Test
    fun refreshPostsRefreshTokenJsonBody() = runTest {
        val capturedPath = AtomicReference<String>()
        val capturedBody = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedPath.set(chain.request().url.encodedPath)
                capturedBody.set(chain.request().bodyToString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"code":0,"message":"ok","data":{"accessToken":"fresh-access","refreshToken":"refresh-b","userId":"13800138000","username":"13800138000","accessExpiresAt":2000,"refreshExpiresAt":9000}}""".toResponseBody())
                    .build()
            })
            .build()
        val api = OkHttpAuthApi(baseUrl = "http://127.0.0.1:8080", client = client)

        val result = api.refresh("refresh-a")

        assertEquals("/refresh", capturedPath.get())
        assertTrue(capturedBody.get().contains(""""refreshToken":"refresh-a""""))
        assertTrue(result is AuthResult.Success)
        assertEquals("fresh-access", (result as AuthResult.Success).session.accessToken)
        assertEquals("refresh-b", result.session.refreshToken)
    }

    @Test
    fun logoutPostsRefreshTokenJsonBody() = runTest {
        val capturedPath = AtomicReference<String>()
        val capturedBody = AtomicReference<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                capturedPath.set(chain.request().url.encodedPath)
                capturedBody.set(chain.request().bodyToString())
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"code":0,"message":"ok"}""".toResponseBody())
                    .build()
            })
            .build()
        val api = OkHttpAuthApi(baseUrl = "http://127.0.0.1:8080", client = client)

        val result = api.logout("refresh-a")

        assertEquals("/logout", capturedPath.get())
        assertTrue(capturedBody.get().contains(""""refreshToken":"refresh-a""""))
        assertEquals(AuthResult.LoggedOut, result)
    }

    private fun okhttp3.Request.bodyToString(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }
}
