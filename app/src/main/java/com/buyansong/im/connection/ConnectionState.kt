package com.buyansong.im.connection

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data object Authenticated : ConnectionState()
    data class Reconnecting(val delayMillis: Long, val reason: String) : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
}
