package com.codex.im.app

import android.content.Context
import java.io.InputStream
import java.util.Properties

data class MockServerConfig(
    val host: String,
    val port: Int
) {
    val httpBaseUrl: String = "http://$host:$port"
    val webSocketUrl: String = "ws://$host:$port/ws"

    companion object {
        private const val FILE_NAME = "mock-server.properties"
        private const val DEFAULT_HOST = "127.0.0.1"
        private const val DEFAULT_PORT = 8080

        fun default(): MockServerConfig = MockServerConfig(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT
        )

        fun load(context: Context): MockServerConfig {
            return runCatching {
                context.assets.open(FILE_NAME).use(::load)
            }.getOrElse {
                default()
            }
        }

        fun load(input: InputStream): MockServerConfig {
            val properties = Properties().apply { load(input) }
            val default = default()
            val host = properties.getProperty("host")?.trim().orEmpty()
            val port = properties.getProperty("port")?.trim()?.toIntOrNull()

            if (host.isEmpty() || port == null) {
                return default
            }
            return MockServerConfig(host = host, port = port)
        }
    }
}
