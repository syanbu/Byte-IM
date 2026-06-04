package com.codex.im.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AccountScopedDatabaseNameTest {
    @Test
    fun databaseNameIsStableAndDifferentPerUser() {
        val first = AccountScopedDatabaseName.forUser("13800113800")
        val same = AccountScopedDatabaseName.forUser("13800113800")
        val second = AccountScopedDatabaseName.forUser("13900113900")

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertEquals("self_hosted_im_13800113800.db", first)
        assertEquals("self_hosted_im_13900113900.db", second)
    }

    @Test
    fun databaseNameSanitizesUnexpectedUserIdCharacters() {
        assertEquals(
            "self_hosted_im_user_abc_123.db",
            AccountScopedDatabaseName.forUser(" user/abc:123 ")
        )
    }
}
