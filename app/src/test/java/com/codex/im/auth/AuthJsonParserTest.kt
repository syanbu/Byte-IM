package com.codex.im.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthJsonParserTest {
    @Test
    fun parsesFlatSuccessResponse() {
        val json = """{"token":"jwt-1","userId":"u1","username":"alice"}"""

        val result = AuthJsonParser.parse(json)

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).session
        assertEquals("jwt-1", session.token)
        assertEquals("u1", session.userId)
        assertEquals("alice", session.username)
    }

    @Test
    fun parsesNestedSuccessResponse() {
        val json = """{"code":0,"message":"ok","data":{"token":"jwt-2","userId":"u2","username":"bob"}}"""

        val result = AuthJsonParser.parse(json)

        assertTrue(result is AuthResult.Success)
        val session = (result as AuthResult.Success).session
        assertEquals("jwt-2", session.token)
        assertEquals("u2", session.userId)
        assertEquals("bob", session.username)
    }

    @Test
    fun parsesFailureResponse() {
        val json = """{"code":401,"message":"bad credentials"}"""

        val result = AuthJsonParser.parse(json)

        assertEquals(AuthResult.Failure("bad credentials"), result)
    }
}
