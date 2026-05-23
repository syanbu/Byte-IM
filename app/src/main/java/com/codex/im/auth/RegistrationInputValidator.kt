package com.codex.im.auth

object RegistrationInputValidator {
    fun validate(password: String, confirmPassword: String): String? {
        return if (password == confirmPassword) null else "Passwords do not match"
    }
}
