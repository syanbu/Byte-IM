package com.codex.im.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactJsonParserTest {
    @Test
    fun parsesFriendIdsResponse() {
        val json = """{"code":0,"message":"ok","data":{"friendUserIds":["15000000003","15000000004"]}}"""

        val result = ContactJsonParser.parseFriendIds(json)

        assertEquals(
            listOf("15000000003", "15000000004"),
            (result as ContactIdsResult.Success).userIds
        )
    }

    @Test
    fun parsesFailureResponse() {
        val json = """{"code":401,"message":"Unauthorized"}"""

        val result = ContactJsonParser.parseFriendIds(json)

        assertTrue(result is ContactIdsResult.Failure)
        assertEquals("Unauthorized", (result as ContactIdsResult.Failure).message)
    }
}
