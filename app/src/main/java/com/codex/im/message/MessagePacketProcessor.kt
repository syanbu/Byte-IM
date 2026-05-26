package com.codex.im.message

import com.codex.im.connection.ImConnection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessagePacketProcessor(
    private val repository: MessageRepository,
    private val connection: ImConnection,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) {
            return
        }
        job = scope.launch(dispatcher) {
            connection.incomingPackets.collect { packet ->
                repository.handlePacket(packet)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
