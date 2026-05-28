package com.codex.im

import com.codex.im.message.MessageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface MessagesTabUnreadBadgeSource {
    val conversationUpdates: SharedFlow<Unit>

    fun totalUnreadCount(): Int
}

object MessagesTabUnreadBadgePolicy {
    fun badgeTextForCount(unreadCount: Int): String? {
        return when {
            unreadCount <= 0 -> null
            unreadCount > 99 -> "99+"
            else -> unreadCount.toString()
        }
    }
}

class MessagesTabUnreadBadgeController(
    private val repository: MessagesTabUnreadBadgeSource,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutableUnreadCount = MutableStateFlow(repository.totalUnreadCount())
    val unreadCount: StateFlow<Int> = mutableUnreadCount.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job != null) {
            mutableUnreadCount.value = repository.totalUnreadCount()
            return
        }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.conversationUpdates
                .onSubscription { emit(Unit) }
                .collect { refreshUnreadCount() }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun refreshUnreadCount() {
        mutableUnreadCount.value = withContext(dispatcher) {
            repository.totalUnreadCount()
        }
    }
}
