package com.codex.im.contacts

import com.codex.im.storage.FriendContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactJsonParserTest {
    @Test
    fun parsesLegacyFriendIdsResponseAsVersionlessContacts() {
        val json = """{"code":0,"message":"ok","data":{"friendUserIds":["15000000003","15000000004"]}}"""

        val result = ContactJsonParser.parseFriendIds(json)

        assertEquals(
            listOf(
                FriendContact("15000000003", profileUpdatedAt = 0L, sortOrder = 0),
                FriendContact("15000000004", profileUpdatedAt = 0L, sortOrder = 1)
            ),
            (result as ContactIdsResult.Success).contacts
        )
    }

    @Test
    fun parsesFriendMetadataResponse() {
        val json = """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "friends": [
                  {"userId": "15000000003", "profileUpdatedAt": 3000},
                  {"userId": "15000000004", "updatedAt": 4000}
                ]
              }
            }
        """.trimIndent()

        val result = ContactJsonParser.parseFriendIds(json)

        assertEquals(
            listOf(
                FriendContact("15000000003", profileUpdatedAt = 3_000L, sortOrder = 0),
                FriendContact("15000000004", profileUpdatedAt = 4_000L, sortOrder = 1)
            ),
            (result as ContactIdsResult.Success).contacts
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
