package com.buyansong.im.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationInputValidatorTest {
    @Test
    fun returnsErrorWhenPasswordsDoNotMatch() {
        val result = RegistrationInputValidator.validate("123456", "123457")

        assertEquals("两次输入的密码不一致", result)
    }

    @Test
    fun acceptsMatchingPasswords() {
        val result = RegistrationInputValidator.validate("123456", "123456")

        assertNull(result)
    }
}
