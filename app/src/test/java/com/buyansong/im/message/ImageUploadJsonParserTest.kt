package com.buyansong.im.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadJsonParserTest {
    @Test
    fun parseMessageImageUploadTargets() {
        val json = """
            {"code":0,"message":"ok","data":{
              "messageId":"m-1",
              "thumbnail":{"objectKey":"chat-images/u/m-1/thumb.jpg","uploadUrl":"https://signed/thumb","publicUrl":"https://public/thumb.jpg"},
              "original":{"objectKey":"chat-images/u/m-1/origin.jpg","uploadUrl":"https://signed/origin","publicUrl":"https://public/origin.jpg"},
              "expiresAt":3000
            }}
        """.trimIndent()

        val result = ImageUploadJsonParser.parseTargets(json)
        assertTrue(result is ImageUploadTargetsResult.Success)
        val success = result as ImageUploadTargetsResult.Success
        assertEquals("m-1", success.targets.messageId)
        assertEquals("chat-images/u/m-1/thumb.jpg", success.targets.thumbnail.objectKey)
        assertEquals("https://public/origin.jpg", success.targets.original.publicUrl)
    }
}
