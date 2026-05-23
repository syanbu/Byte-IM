package com.codex.im.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistrationInputValidatorTest {
    @Test
    fun returnsErrorWhenPasswordsDoNotMatch() {
        val result = RegistrationInputValidator.validate("123456", "123457")

        assertEquals("Passwords do not match", result)
    }

    @Test
    fun acceptsMatchingPasswords() {
        val result = RegistrationInputValidator.validate("123456", "123456")

        assertNull(result)
    }
}
