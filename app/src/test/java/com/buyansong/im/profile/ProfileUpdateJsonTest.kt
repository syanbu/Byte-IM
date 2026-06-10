package com.buyansong.im.profile

import com.buyansong.im.storage.Gender
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProfileUpdateJsonTest {
    @Test
    fun build_omitsUnchangedFields() {
        val json = ProfileUpdateJson.build(signature = "hello")
        val objectJson = JsonParser.parseString(json).asJsonObject

        assertEquals("hello", objectJson.get("signature").asString)
        assertFalse(objectJson.has("nickname"))
        assertFalse(objectJson.has("avatarUrl"))
        assertFalse(objectJson.has("avatarObjectKey"))
        assertFalse(objectJson.has("gender"))
    }

    @Test
    fun build_includesOnlyProvidedProfileFields() {
        val json = ProfileUpdateJson.build(
            nickname = "Alice",
            avatarUrl = "https://example.com/a\"b.jpg",
            avatarObjectKey = "avatars/u1/a.jpg",
            gender = Gender.MALE
        )
        val objectJson = JsonParser.parseString(json).asJsonObject

        assertEquals("Alice", objectJson.get("nickname").asString)
        assertEquals("https://example.com/a\"b.jpg", objectJson.get("avatarUrl").asString)
        assertEquals("avatars/u1/a.jpg", objectJson.get("avatarObjectKey").asString)
        assertEquals("MALE", objectJson.get("gender").asString)
        assertFalse(objectJson.has("signature"))
    }
}
