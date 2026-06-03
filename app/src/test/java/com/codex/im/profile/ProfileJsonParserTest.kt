package com.codex.im.profile

import com.codex.im.storage.Gender
import com.codex.im.storage.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun parsesGenderAndSignatureWhenPresent() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","gender":"MALE","signature":"hello world","updatedAt":1000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        val profile = (result as ProfileResult.Success).profile
        assertEquals(Gender.MALE, profile.gender)
        assertEquals("hello world", profile.signature)
    }

    @Test
    fun parsesFemaleGender() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","gender":"FEMALE","updatedAt":1000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        assertEquals(Gender.FEMALE, (result as ProfileResult.Success).profile.gender)
    }

    @Test
    fun toleratesNullGenderAndSignature() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","gender":null,"signature":null,"updatedAt":1000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        val profile = (result as ProfileResult.Success).profile
        assertNull(profile.gender)
        assertNull(profile.signature)
    }

    @Test
    fun toleratesMissingGenderAndSignature() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","updatedAt":1000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        val profile = (result as ProfileResult.Success).profile
        assertNull(profile.gender)
        assertNull(profile.signature)
    }

    @Test
    fun ignoresInvalidGenderValue() {
        val json = """{"code":0,"message":"ok","data":{"userId":"13800138000","phone":"13800138000","nickname":"Syan","gender":"OTHER","updatedAt":1000}}"""

        val result = ProfileJsonParser.parseProfile(json)

        assertTrue(result is ProfileResult.Success)
        assertNull((result as ProfileResult.Success).profile.gender)
    }

    @Test
    fun parsesBatchProfileResponse() {
        val json = """{"code":0,"message":"ok","data":{"profiles":[{"userId":"13800138000","phone":"13800138000","nickname":"13800138000","avatarUrl":null,"avatarUpdatedAt":0,"updatedAt":1000},{"userId":"13900139000","phone":"13900139000","nickname":"Megumi","avatarUrl":"https://example.com/m.jpg","avatarUpdatedAt":2000,"updatedAt":2000}]}}"""

        val result = ProfileJsonParser.parseProfiles(json)

        assertTrue(result is ProfileBatchResult.Success)
        assertEquals(listOf("13800138000", "Megumi"), (result as ProfileBatchResult.Success).profiles.map { it.nickname })
    }

    @Test
    fun parsesBatchProfileWithGenderAndSignature() {
        val json = """{"code":0,"message":"ok","data":{"profiles":[{"userId":"13800138000","phone":"13800138000","nickname":"Syan","gender":"MALE","signature":"hi","updatedAt":1000},{"userId":"13900139000","phone":"13900139000","nickname":"Megumi","gender":"FEMALE","signature":null,"updatedAt":2000}]}}"""

        val result = ProfileJsonParser.parseProfiles(json)

        assertTrue(result is ProfileBatchResult.Success)
        val profiles = (result as ProfileBatchResult.Success).profiles
        assertEquals(Gender.MALE, profiles[0].gender)
        assertEquals("hi", profiles[0].signature)
        assertEquals(Gender.FEMALE, profiles[1].gender)
        assertNull(profiles[1].signature)
    }
}
