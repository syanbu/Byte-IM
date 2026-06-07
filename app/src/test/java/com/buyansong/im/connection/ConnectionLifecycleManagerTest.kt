package com.buyansong.im.connection

import com.buyansong.im.protocol.ImCommand
import com.buyansong.im.protocol.ImPacket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionLifecycleManagerTest {
    @Test
    fun authenticatedConnectionSendsHeartbeat() = runTest {
        val fixture = Fixture(this)

        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()

        assertEquals(ImCommand.HEARTBEAT.value, fixture.raw.sentPackets.single().cmd)
    }

    @Test
    fun heartbeatAckPreventsReconnectForThatHeartbeatCycle() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()

        fixture.raw.incoming.tryEmit(ImPacket(cmd = ImCommand.HEARTBEAT_ACK.value, body = ByteArray(0)))
        advanceTimeBy(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
        runCurrent()

        assertEquals(0, fixture.raw.disconnectCount)
        assertEquals(listOf(TOKEN), fixture.raw.connectTokens)
    }

    @Test
    fun missingHeartbeatAckDisconnectsAndReconnectsWithPolicyDelay() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()

        advanceTimeBy(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS * 2)
        runCurrent()

        assertEquals(1, fixture.raw.disconnectCount)
        assertEquals(ConnectionState.Reconnecting(1_000L, "heartbeat timeout"), fixture.manager.states.value)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(TOKEN, TOKEN), fixture.raw.connectTokens)
    }

    @Test
    fun failedPacketSendDisconnectsAndSchedulesReconnect() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()

        fixture.raw.sendSucceeds = false
        val sent = fixture.manager.send(
            ImPacket(
                cmd = ImCommand.SEND_MESSAGE.value,
                body = "{}".toByteArray()
            )
        )
        runCurrent()

        assertEquals(false, sent)
        assertEquals(1, fixture.raw.disconnectCount)
        assertEquals(ConnectionState.Reconnecting(1_000L, "send failed"), fixture.manager.states.value)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(TOKEN, TOKEN), fixture.raw.connectTokens)
    }

    @Test
    fun authenticatedConnectionResetsReconnectPolicy() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("first failure")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("second failure")
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(2_000L, "second failure"), fixture.manager.states.value)

        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("after auth")
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1_000L, "after auth"), fixture.manager.states.value)
    }

    @Test
    fun networkAvailableReconnectsImmediatelyDuringBackoffDelay() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("first failure")
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("second failure")
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(2_000L, "second failure"), fixture.manager.states.value)
        val connectCountBeforeNetworkAvailable = fixture.raw.connectTokens.size

        fixture.manager.notifyNetworkAvailable()
        runCurrent()

        assertEquals(connectCountBeforeNetworkAvailable + 1, fixture.raw.connectTokens.size)

        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(connectCountBeforeNetworkAvailable + 1, fixture.raw.connectTokens.size)
    }

    @Test
    fun stopCancelsHeartbeatAndReconnectWork() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        val heartbeatCountAtStop = fixture.raw.sentPackets.size

        fixture.manager.stop()
        fixture.raw.state.value = ConnectionState.Failed("after stop")
        advanceTimeBy(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS * 4 + 30_000L)
        runCurrent()

        assertEquals(heartbeatCountAtStop, fixture.raw.sentPackets.size)
        assertEquals(listOf(TOKEN), fixture.raw.connectTokens)
        assertTrue(fixture.manager.states.value is ConnectionState.Disconnected)
    }

    @Test
    fun backgroundKeepsConnectionAliveWithSlowerHeartbeatAndReconnect() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        val heartbeatCountInForeground = fixture.raw.sentPackets.size

        fixture.manager.setForeground(false)
        advanceTimeBy(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
        runCurrent()

        assertEquals(heartbeatCountInForeground, fixture.raw.sentPackets.size)
        advanceTimeBy(BACKGROUND_HEARTBEAT_INTERVAL_MILLIS - FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
        runCurrent()

        assertEquals(heartbeatCountInForeground + 1, fixture.raw.sentPackets.size)

        fixture.raw.state.value = ConnectionState.Failed("background failure")
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1_000L, "background failure"), fixture.manager.states.value)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(TOKEN, TOKEN), fixture.raw.connectTokens)
    }

    @Test
    fun reconnectUsesFreshTokenFromProviderInsteadOfCachedToken() = runTest {
        var tokenProviderCalls = 0
        val fixture = Fixture(
            scope = this,
            tokenProvider = { _ ->
                tokenProviderCalls += 1
                if (tokenProviderCalls == 1) TOKEN else "fresh-token"
            }
        )

        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("expired auth")
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(TOKEN, "fresh-token"), fixture.raw.connectTokens)
    }

    @Test
    fun initialConnectRetriesWhenTokenProviderIsTemporarilyUnavailable() = runTest {
        var tokenProviderCalls = 0
        val fixture = Fixture(
            scope = this,
            tokenProvider = { _ ->
                tokenProviderCalls += 1
                if (tokenProviderCalls == 1) null else "fresh-token"
            }
        )

        fixture.manager.connect(TOKEN)
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1_000L, "token unavailable"), fixture.manager.states.value)
        assertEquals(emptyList<String>(), fixture.raw.connectTokens)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("fresh-token"), fixture.raw.connectTokens)
    }

    @Test
    fun reconnectKeepsRetryingWhenTokenProviderIsTemporarilyUnavailable() = runTest {
        var tokenProviderCalls = 0
        val fixture = Fixture(
            scope = this,
            tokenProvider = { _ ->
                tokenProviderCalls += 1
                when (tokenProviderCalls) {
                    1 -> TOKEN
                    2 -> null
                    else -> "fresh-token"
                }
            }
        )

        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        fixture.raw.state.value = ConnectionState.Failed("expired auth")
        runCurrent()

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf(TOKEN), fixture.raw.connectTokens)
        assertEquals(ConnectionState.Reconnecting(2_000L, "token unavailable"), fixture.manager.states.value)

        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(listOf(TOKEN, "fresh-token"), fixture.raw.connectTokens)
    }

    @Test
    fun foregroundRestoresFasterHeartbeatInterval() = runTest {
        val fixture = Fixture(this)
        fixture.manager.connect(TOKEN)
        runCurrent()
        fixture.raw.state.value = ConnectionState.Authenticated
        runCurrent()
        fixture.manager.setForeground(false)
        runCurrent()
        advanceTimeBy(BACKGROUND_HEARTBEAT_INTERVAL_MILLIS)
        runCurrent()

        fixture.manager.setForeground(true)
        runCurrent()
        val heartbeatCountAfterForeground = fixture.raw.sentPackets.size
        advanceTimeBy(FOREGROUND_HEARTBEAT_INTERVAL_MILLIS)
        runCurrent()

        assertEquals(heartbeatCountAfterForeground + 1, fixture.raw.sentPackets.size)
        assertEquals(listOf(TOKEN), fixture.raw.connectTokens)
    }

    private class Fixture(
        scope: TestScope,
        tokenProvider: suspend (String?) -> String? = { TOKEN }
    ) {
        val raw = FakeConnection()
        val manager = ConnectionLifecycleManager(
            connection = raw,
            tokenProvider = tokenProvider,
            reconnectPolicy = ReconnectPolicy(),
            scope = scope.backgroundScope,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            foregroundHeartbeatIntervalMillis = FOREGROUND_HEARTBEAT_INTERVAL_MILLIS,
            backgroundHeartbeatIntervalMillis = BACKGROUND_HEARTBEAT_INTERVAL_MILLIS
        )
    }

    private class FakeConnection : ImConnection {
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val incoming = MutableSharedFlow<ImPacket>(extraBufferCapacity = 64)
        val sentPackets = mutableListOf<ImPacket>()
        val connectTokens = mutableListOf<String>()
        var disconnectCount = 0
        var sendSucceeds = true

        override val states: StateFlow<ConnectionState> = state
        override val incomingPackets: SharedFlow<ImPacket> = incoming

        override fun connect(token: String) {
            connectTokens += token
            state.value = ConnectionState.Connecting
        }

        override fun disconnect() {
            disconnectCount += 1
            state.value = ConnectionState.Disconnected
        }

        override fun send(packet: ImPacket): Boolean {
            sentPackets += packet
            return sendSucceeds
        }
    }

    private companion object {
        const val TOKEN = "mock-token"
        const val FOREGROUND_HEARTBEAT_INTERVAL_MILLIS = 100L
        const val BACKGROUND_HEARTBEAT_INTERVAL_MILLIS = 500L
    }
}
