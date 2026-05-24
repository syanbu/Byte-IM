package com.codex.im.profile

import com.codex.im.storage.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileJsonParserTest {
    @Test
    fun parsesSingleProfileResponse() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","avatarUrl":"https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg","avatarUpdatedAt":2000,"updatedAt":3000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        assertEquals(
            UserProfile(
                userId = "13800138000",
                phone = "13800138000",
                nickname = "Syan",
                avatarUrl = "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg",
                avatarUpdatedAt = 2_000L,
                updatedAt = 3_000L
            ),
            (result as ProfileResult.Success).profile
        )
    }

    @Test
    fun parsesBatchProfileResponse() {
        val json = """{"code":0,"message":"ok","data":{"profiles":[{"userId":"13800138000","phone":"13800138000","nickname":"13800138000","avatarUrl":null,"avatarUpdatedAt":0,"updatedAt":1000},{"userId":"13900139000","phone":"13900139000","nickname":"Megumi","avatarUrl":"https://example.com/m.jpg","avatarUpdatedAt":2000,"updatedAt":2000}]}}"""

        val result = ProfileJsonParser.parseProfiles(json)

        assertTrue(result is ProfileBatchResult.Success)
        assertEquals(listOf("13800138000", "Megumi"), (result as ProfileBatchResult.Success).profiles.map { it.nickname })
    }
}
