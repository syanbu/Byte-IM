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
}
