package com.codex.im.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserProfileDaoContractTest {
    @Test
    fun upsertStoresAndReplacesUserProfile() {
        val dao = InMemoryUserProfileDao()
        val initial = UserProfile(
            userId = "13800138000",
            phone = "13800138000",
            nickname = "13800138000",
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = 1_000L
        )
        val updated = initial.copy(
            nickname = "Syan",
            avatarUrl = "https://im-byte.oss-cn-shenzhen.aliyuncs.com/avatars/13800138000/2000.jpg",
            avatarUpdatedAt = 2_000L,
            updatedAt = 2_000L
        )

        dao.upsert(initial)
        dao.upsert(updated)

        assertEquals(updated, dao.findByUserId("13800138000"))
    }

    @Test
    fun batchFindReturnsOnlyKnownProfilesInRequestOrder() {
        val dao = InMemoryUserProfileDao()
        val first = UserProfile("13800138000", "13800138000", "Syan", null, 0L, 1L)
        val second = UserProfile("13900139000", "13900139000", "Megumi", "https://example.com/a.jpg", 2L, 2L)
        dao.upsert(second)
        dao.upsert(first)

        val profiles = dao.findByUserIds(listOf("13800138000", "13700137000", "13900139000"))

        assertEquals(listOf(first, second), profiles)
        assertNull(dao.findByUserId("13700137000"))
    }

    @Test
    fun upsertPreservesGenderAndSignature() {
        val dao = InMemoryUserProfileDao()
        val initial = UserProfile(
            userId = "13800138000",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = 1_000L,
            gender = null,
            signature = null
        )
        val withProfile = initial.copy(
            gender = Gender.MALE,
            signature = "hi there"
        )

        dao.upsert(initial)
        dao.upsert(withProfile)

        val stored = dao.findByUserId("13800138000")
        assertEquals(Gender.MALE, stored?.gender)
        assertEquals("hi there", stored?.signature)
    }

    @Test
    fun upsertClearsGenderAndSignatureWhenSetToNull() {
        val dao = InMemoryUserProfileDao()
        val withProfile = UserProfile(
            userId = "13800138000",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = 1_000L,
            gender = Gender.FEMALE,
            signature = "loud and clear"
        )
        val cleared = withProfile.copy(
            gender = null,
            signature = null
        )

        dao.upsert(withProfile)
        dao.upsert(cleared)

        val stored = dao.findByUserId("13800138000")
        assertNull(stored?.gender)
        assertNull(stored?.signature)
    }

    @Test
    fun batchFindReturnsGenderAndSignature() {
        val dao = InMemoryUserProfileDao()
        val first = UserProfile(
            userId = "13800138000",
            phone = "13800138000",
            nickname = "Syan",
            avatarUrl = null,
            avatarUpdatedAt = 0L,
            updatedAt = 1_000L,
            gender = Gender.MALE,
            signature = "hello"
        )
        val second = UserProfile(
            userId = "13900139000",
            phone = "13900139000",
            nickname = "Megumi",
            avatarUrl = "https://example.com/a.jpg",
            avatarUpdatedAt = 2L,
            updatedAt = 2L,
            gender = Gender.FEMALE,
            signature = null
        )
        dao.upsert(second)
        dao.upsert(first)

        val profiles = dao.findByUserIds(listOf("13800138000", "13900139000"))

        assertEquals(Gender.MALE, profiles[0].gender)
        assertEquals("hello", profiles[0].signature)
        assertEquals(Gender.FEMALE, profiles[1].gender)
        assertNull(profiles[1].signature)
    }
}
