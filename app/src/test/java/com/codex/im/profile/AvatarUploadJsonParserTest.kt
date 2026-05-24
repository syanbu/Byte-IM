package com.codex.im.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarUploadJsonParserTest {
    @Test
    fun parsesUploadTargetResponse() {
        val json = """{"code":0,"message":"ok","data":{"objectKey":"avatars/13800138000/2000.jpg","uploadUrl":"https://signed.example.com/upload","publicUrl":"https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg","expiresAt":3000}}"""

        val result = AvatarUploadJsonParser.parseTarget(json)

        assertTrue(result is AvatarUploadResult.Success)
        val target = (result as AvatarUploadResult.Success).target
        assertEquals("avatars/13800138000/2000.jpg", target.objectKey)
        assertEquals("https://signed.example.com/upload", target.uploadUrl)
        assertEquals("https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg", target.publicUrl)
        assertEquals(3_000L, target.expiresAt)
    }
}
