package com.codex.im.message

import com.codex.im.connection.ConnectionState
import com.codex.im.connection.ImConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MessageOutboxWorker(
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scanIntervalMillis: Long = DEFAULT_SCAN_INTERVAL_MILLIS
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            return
        }
        job = scope.launch(dispatcher) {
            connection.states.collectLatest { state ->
                if (state == ConnectionState.Authenticated) {
                    while (true) {
                        repository.retryDuePendingMessages(now = System.currentTimeMillis())
                        delay(scanIntervalMillis)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val DEFAULT_SCAN_INTERVAL_MILLIS = 1_000L
    }
}
