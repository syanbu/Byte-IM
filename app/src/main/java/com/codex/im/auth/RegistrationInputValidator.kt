package com.codex.im.auth

object RegistrationInputValidator {
    fun validate(password: String, confirmPassword: String): String? {
        return if (password == confirmPassword) null else "两次输入的密码不一致"
    }
}
