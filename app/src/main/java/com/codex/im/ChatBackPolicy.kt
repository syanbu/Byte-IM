package com.codex.im

object ChatBackPolicy {
    fun run(
        navigateBack: () -> Unit
    ) {
        navigateBack()
    }
}
