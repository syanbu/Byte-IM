package com.buyansong.im.message

import com.buyansong.im.connection.ConnectionState
import com.buyansong.im.connection.ImConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 事件驱动的 outbox 调度器：不再固定轮询，每轮先扫到期重试，然后睡到
 * 最早的 nextRetryAt，期间任何 outbox 变更（入队/ACK 删除/心跳对账）都会提前唤醒。
 */
class MessageOutboxWorker(
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            return
        }
        job = scope.launch(dispatcher) {
            connection.states.collectLatest { state ->
                if (state == ConnectionState.Authenticated) {
                    runAuthenticatedLoop()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runAuthenticatedLoop() {
        val revisions = repository.outboxChangeSignal.revisions
        while (true) {
            val observed = revisions.value
            repository.retryDuePendingMessages(now = nowProvider())
            val deadline = repository.earliestPendingRetryAt()
            if (revisions.value != observed) {
                continue
            }
            val now = nowProvider()
            if (deadline != null && deadline <= now) {
                continue
            }
            if (deadline == null) {
                revisions.first { it != observed }
            } else {
                withTimeoutOrNull(deadline - now) {
                    revisions.first { it != observed }
                }
            }
        }
    }
}
