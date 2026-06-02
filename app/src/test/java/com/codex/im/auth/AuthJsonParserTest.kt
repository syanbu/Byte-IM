package com.codex.im.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthJsonParserTest {
    @Test
    fun parsesFlatSuccessResponse() {
        val json = """{"accessToken":"jwt-1","refreshToken":"refresh-1","userId":"13800138000","username":"13800138000","accessExpiresAt":2000,"refreshExpiresAt":8000}"""

        val result = AuthJsonParser.parse(json)

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).session
        assertEquals("jwt-1", session.accessToken)
        assertEquals("refresh-1", session.refreshToken)
        assertEquals("13800138000", session.userId)
        assertEquals("13800138000", session.username)
        assertEquals(2_000L, session.accessExpiresAtMillis)
        assertEquals(8_000L, session.refreshExpiresAtMillis)
    }

    @Test
    fun parsesNestedSuccessResponse() {
        val json = """{"code":0,"message":"ok","data":{"accessToken":"jwt-2","refreshToken":"refresh-2","userId":"13900139000","username":"13900139000","accessExpiresAt":3000,"refreshExpiresAt":9000}}"""

        val result = AuthJsonParser.parse(json)

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).session
        assertEquals("jwt-2", session.accessToken)
        assertEquals("refresh-2", session.refreshToken)
        assertEquals("13900139000", session.userId)
        assertEquals("13900139000", session.username)
        assertEquals(3_000L, session.accessExpiresAtMillis)
        assertEquals(9_000L, session.refreshExpiresAtMillis)
    }

    @Test
    fun parsesProfileFieldsFromSuccessResponse() {
        val json = """{"code":0,"message":"ok","data":{"accessToken":"jwt-3","refreshToken":"refresh-3","userId":"13800138000","phone":"13800138000","username":"13800138000","nickname":"Syan","avatarUrl":"https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg","avatarUpdatedAt":2000,"profileUpdatedAt":3000,"accessExpiresAt":4000,"refreshExpiresAt":9000}}"""

        val result = AuthJsonParser.parse(json)

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).session
        assertEquals("13800138000", session.phone)
        assertEquals("13800138000", session.username)
        assertEquals("Syan", session.nickname)
        assertEquals("https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg", session.avatarUrl)
        assertEquals(2_000L, session.avatarUpdatedAt)
        assertEquals(3_000L, session.profileUpdatedAt)
    }

    @Test
    fun rejectsSuccessResponseWithoutTokenExpiry() {
        val json = """{"token":"jwt-1","userId":"13800138000","username":"13800138000"}"""

        val result = AuthJsonParser.parse(json)

        assertEquals(AuthResult.Failure("认证响应无效"), result)
    }

    @Test
    fun parsesFailureResponse() {
        val json = """{"code":401,"message":"bad credentials"}"""

        val result = AuthJsonParser.parse(json)

        assertEquals(AuthResult.Failure("bad credentials"), result)
    }
}
