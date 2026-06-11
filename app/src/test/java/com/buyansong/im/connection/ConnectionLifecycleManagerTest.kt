package com.buyansong.im.connection

import com.buyansong.im.protocol.ImPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLifecycleManagerTest {
    private class FakeConnection : ImConnection {
        override val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        override val incomingPackets = MutableSharedFlow<ImPacket>()
        var disconnectCount = 0

        override fun connect(token: String) {
            states.value = ConnectionState.Connecting
        }

        override fun disconnect() {
            disconnectCount += 1
            states.value = ConnectionState.Disconnected
        }

        override fun send(packet: ImPacket): Boolean = true
    }

    @Test
    fun notifyNetworkUnavailable_marksAuthenticatedConnectionReconnectingImmediately() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val rawConnection = FakeConnection()
        val manager = ConnectionLifecycleManager(
            connection = rawConnection,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            dispatcher = dispatcher
        )

        manager.connect("token")
        testScheduler.runCurrent()
        rawConnection.states.value = ConnectionState.Authenticated
        testScheduler.runCurrent()

        manager.notifyNetworkUnavailable()
        testScheduler.runCurrent()

        val state = manager.states.value
        assertTrue(state is ConnectionState.Reconnecting)
        assertEquals("network unavailable", (state as ConnectionState.Reconnecting).reason)
        assertEquals(1_000L, state.delayMillis)
        assertEquals(1, rawConnection.disconnectCount)
    }
}
