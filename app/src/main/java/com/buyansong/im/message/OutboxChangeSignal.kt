package com.buyansong.im.message

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内 outbox 变更信号。用单调递增的修订号而不是一次性事件，
 * 等待方只需比较修订号，信号在订阅前发出也不会丢失唤醒。
 */
class OutboxChangeSignal {
    private val mutableRevisions = MutableStateFlow(0L)
    val revisions: StateFlow<Long> = mutableRevisions.asStateFlow()

    fun notifyChanged() {
        mutableRevisions.value += 1
    }
}
