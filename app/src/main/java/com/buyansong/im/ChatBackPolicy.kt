package com.buyansong.im

object ChatBackPolicy {
    fun run(
        navigateBack: () -> Unit
    ) {
        navigateBack()
    }
}
