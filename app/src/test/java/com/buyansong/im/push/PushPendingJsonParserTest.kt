package com.buyansong.im.push

import org.junit.Assert.assertEquals
import org.junit.Test

class PushPendingJsonParserTest {
    @Test
    fun parsePending_readsRowsAndLatestPushId() {
        val result = PushPendingJsonParser.parse(
            """
            {
              "code": 0,
              "message": "ok",
              "data": {
                "pending": [
                  {
                    "pushId": 7,
                    "senderId": "u_sender",
                    "conversationId": "single:u_sender:u_receiver",
                    "messageId": "m_1",
                    "messageType": "TEXT",
                    "preview": "hello",
                    "serverSeq": 42,
                    "serverTime": 1000
                  }
                ],
                "latestPushId": 7
              }
            }
            """.trimIndent()
        )

        check(result is PushPendingResult.Success)
        assertEquals(7L, result.latestPushId)
        assertEquals("m_1", result.pending.single().messageId)
        assertEquals("hello", result.pending.single().preview)
    }

    @Test
    fun parsePending_returnsFailureForNonZeroCode() {
        val result = PushPendingJsonParser.parse("""{"code":401,"message":"Unauthorized"}""")

        check(result is PushPendingResult.Failure)
        assertEquals("Unauthorized", result.message)
    }
}
