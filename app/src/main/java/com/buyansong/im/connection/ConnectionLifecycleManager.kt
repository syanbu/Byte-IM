package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionLifecycleManager(
    private val connection: ImConnection,
    private val tokenProvider: suspend (String?) -> String? = { requestedToken -> requestedToken },
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val foregroundHeartbeatIntervalMillis: Long = FOREGROUND_HEARTBEAT_INTERVAL_MILLIS,
    private val backgroundHeartbeatIntervalMillis: Long = BACKGROUND_HEARTBEAT_INTERVAL_MILLIS,
    private val missedHeartbeatLimit: Int = MISSED_HEARTBEAT_LIMIT
) : ImConnection {
    private val mutableStates = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val states: StateFlow<ConnectionState> = mutableStates.asStateFlow()
    override val incomingPackets: SharedFlow<ImPacket> = connection.incomingPackets

    // Access-token hint for the current connection lifecycle. It identifies which
    // authenticated session reconnect/heartbeat-timeout/network-recovery should keep
    // trying to restore; tokenProvider may refresh or replace it before each attempt.
    // requestedToken 当前这个 IM 连接生命周期绑定的登录token，名字可以改为 reconnectTokenHint
    private var requestedToken: String? = null
    private var started = false
    private var foreground = true
    private var rawStateJob: Job? = null
    private var packetJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatAckGeneration = 0L

    override fun connect(token: String) {
        requestedToken = token
        if (!started) {
            started = true
            collectConnectionState()
            collectHeartbeatAcks()
        }
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectPolicy.reset()
        attemptConnect(token)
    }

    override fun disconnect() {
        stop()
    }

    fun stop() {
        started = false
        requestedToken = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        rawStateJob?.cancel()
        rawStateJob = null
        packetJob?.cancel()
        packetJob = null
        connection.disconnect()
        mutableStates.value = ConnectionState.Disconnected
    }

    fun setForeground(isForeground: Boolean) {
        foreground = isForeground
        if (!started) {
            return
        }
        if (isForeground) {
            checkConnectionOnForeground()
        } else {
            if (connection.states.value == ConnectionState.Authenticated) {
                restartHeartbeatLoop(backgroundHeartbeatIntervalMillis)
            }
        }
    }

    fun notifyNetworkAvailable() {
        val tokenHint = requestedToken ?: return
        if (!started) {
            return
        }
        when (connection.states.value) {
            ConnectionState.Authenticated -> restartHeartbeatLoop()
            ConnectionState.Disconnected,
            is ConnectionState.Failed,
            is ConnectionState.Reconnecting -> {
                reconnectJob?.cancel()
                reconnectJob = null
                attemptConnect(tokenHint)
            }
            ConnectionState.Connecting,
            ConnectionState.Connected -> Unit
        }
    }

    fun notifyNetworkUnavailable() {
        if (!started) {
            return
        }
        when (connection.states.value) {
            ConnectionState.Authenticated,
            ConnectionState.Connecting,
            ConnectionState.Connected -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                scheduleReconnect(reason = "network unavailable", disconnectBeforeDelay = true)
            }
            ConnectionState.Disconnected,
            is ConnectionState.Failed -> scheduleReconnect("network unavailable")
            is ConnectionState.Reconnecting -> Unit
        }
    }

    override fun send(packet: ImPacket): Boolean {
        return sendPacket(packet)
    }

    private fun restartHeartbeatLoop(initialDelayMillis: Long = 0L) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        startHeartbeatLoop(initialDelayMillis)
    }

    private fun collectConnectionState() {
        if (rawStateJob != null) {
            return
        }
        rawStateJob = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            connection.states.collect { state ->
                handleConnectionState(state)
            }
        }
    }

    private fun collectHeartbeatAcks() {
        if (packetJob != null) {
            return
        }
        packetJob = scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            connection.incomingPackets.collect { packet ->
                if (packet.cmd == ImCommand.HEARTBEAT_ACK.value) {
                    heartbeatAckGeneration += 1
                }
            }
        }
    }

    private fun handleConnectionState(state: ConnectionState) {
        if (!started) {
            return
        }
        when (state) {
            ConnectionState.Authenticated -> {
                mutableStates.value = state
                reconnectPolicy.reset()
                reconnectJob?.cancel()
                reconnectJob = null
                startHeartbeatLoop()
            }
            ConnectionState.Disconnected -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                scheduleReconnect("disconnected")
            }
            is ConnectionState.Failed -> {
                heartbeatJob?.cancel()
                heartbeatJob = null
                scheduleReconnect(state.reason)
            }
            ConnectionState.Connecting,
            ConnectionState.Connected -> {
                mutableStates.value = state
            }
            is ConnectionState.Reconnecting -> {
                mutableStates.value = state
            }
        }
    }

    private fun startHeartbeatLoop(initialDelayMillis: Long = 0L) {
        if (heartbeatJob?.isActive == true) {
            return
        }
        heartbeatJob = scope.launch(dispatcher) {
            var missedHeartbeats = 0
            if (initialDelayMillis > 0) {
                delay(initialDelayMillis)
            }
            while (started && connection.states.value == ConnectionState.Authenticated) {
                val heartbeatIntervalMillis = currentHeartbeatIntervalMillis()
                val ackBeforeSend = heartbeatAckGeneration
                if (!sendPacket(HeartbeatPacketFactory.create())) {
                    break
                }
                delay(heartbeatIntervalMillis)
                if (!started || connection.states.value != ConnectionState.Authenticated) {
                    break
                }
                if (heartbeatAckGeneration == ackBeforeSend) {
                    missedHeartbeats += 1
                    if (missedHeartbeats >= missedHeartbeatLimit) {
                        handleHeartbeatTimeout()
                        break
                    }
                } else {
                    missedHeartbeats = 0
                }
            }
        }
    }

    private fun sendPacket(packet: ImPacket): Boolean {
        val sent = connection.send(packet)
        if (!sent) {
            scheduleReconnectAfterSendFailure()
        }
        return sent
    }

    private fun scheduleReconnectAfterSendFailure() {
        if (!started) {
            return
        }
        heartbeatJob?.cancel()
        heartbeatJob = null
        scheduleReconnect(reason = "send failed", disconnectBeforeDelay = true)
    }

    private fun handleHeartbeatTimeout() {
        val tokenHint = requestedToken ?: return
        heartbeatJob = null
        val delayMillis = reconnectPolicy.nextDelayMillis()
        mutableStates.value = ConnectionState.Reconnecting(delayMillis, "heartbeat timeout")
        reconnectJob = scope.launch(dispatcher) {
            connection.disconnect()
            delay(delayMillis)
            if (started) {
                reconnectJob = null
                attemptConnect(tokenHint)
            }
        }
    }

    private fun scheduleReconnect(reason: String, disconnectBeforeDelay: Boolean = false) {
        val tokenHint = requestedToken
        if (tokenHint == null || reconnectJob?.isActive == true) {
            return
        }
        val delayMillis = reconnectPolicy.nextDelayMillis()
        mutableStates.value = ConnectionState.Reconnecting(delayMillis, reason)
        reconnectJob = scope.launch(dispatcher) {
            if (disconnectBeforeDelay) {
                connection.disconnect()
            }
            delay(delayMillis)
            if (started) {
                reconnectJob = null
                attemptConnect(tokenHint)
            }
        }
    }

    private fun checkConnectionOnForeground() {
        when (connection.states.value) {
            ConnectionState.Authenticated -> restartHeartbeatLoop(foregroundHeartbeatIntervalMillis)
            ConnectionState.Disconnected,
            is ConnectionState.Failed -> scheduleReconnect("foreground reconnect")
            ConnectionState.Connecting,
            ConnectionState.Connected,
            is ConnectionState.Reconnecting -> Unit
        }
    }

    private fun currentHeartbeatIntervalMillis(): Long {
        return if (foreground) foregroundHeartbeatIntervalMillis else backgroundHeartbeatIntervalMillis
    }

    private fun attemptConnect(tokenHint: String) {
        scope.launch(dispatcher) {
            val resolvedToken = tokenProvider(tokenHint)
            if (!started) {
                return@launch
            }
            if (resolvedToken.isNullOrBlank()) {
                scheduleReconnect("token unavailable")
                return@launch
            }
            requestedToken = resolvedToken
            connection.connect(resolvedToken)
        }
    }

    private companion object {
        const val FOREGROUND_HEARTBEAT_INTERVAL_MILLIS = 15_000L
        const val BACKGROUND_HEARTBEAT_INTERVAL_MILLIS = 75_000L
        const val MISSED_HEARTBEAT_LIMIT = 2
    }
}
