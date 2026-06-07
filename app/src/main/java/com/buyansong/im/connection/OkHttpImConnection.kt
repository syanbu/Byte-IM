package com.buyansong.im.connection

import com.buyansong.im.protocol.ImPacket
import com.buyansong.im.protocol.ImPacketCodec
import com.buyansong.im.protocol.ProtocolException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

class OkHttpImConnection(
    private val webSocketUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) : ImConnection {
    private val mutableStates = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val states: StateFlow<ConnectionState> = mutableStates.asStateFlow()

    private val mutableIncomingPackets = MutableSharedFlow<ImPacket>(extraBufferCapacity = 64)
    override val incomingPackets: SharedFlow<ImPacket> = mutableIncomingPackets.asSharedFlow()

    private var webSocket: WebSocket? = null

    override fun connect(token: String) {
        disconnect()
        mutableStates.value = ConnectionState.Connecting
        val request = Request.Builder().url(webSocketUrl).build()
        webSocket = client.newWebSocket(request, Listener(token))
    }

    override fun disconnect() {
        webSocket?.close(NORMAL_CLOSE, "client disconnect")
        webSocket = null
        mutableStates.value = ConnectionState.Disconnected
    }

    override fun send(packet: ImPacket): Boolean {
        val bytes = ImPacketCodec.encode(packet).toByteString()
        return webSocket?.send(bytes) ?: false
    }

    private inner class Listener(private val token: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            mutableStates.value = ConnectionState.Connected
            send(AuthPacketFactory.create(token))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val packet = ImPacketCodec.decode(bytes.toByteArray())
                ConnectionStateReducer.stateAfterIncomingPacket(packet)?.let { mutableStates.value = it }
                mutableIncomingPackets.tryEmit(packet)
            } catch (error: ProtocolException) {
                mutableStates.value = ConnectionState.Failed(error.message ?: "protocol error")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mutableStates.value = ConnectionState.Disconnected
            this@OkHttpImConnection.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            mutableStates.value = ConnectionState.Failed(t.message ?: "websocket failure")
            this@OkHttpImConnection.webSocket = null
        }
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
    }
}
