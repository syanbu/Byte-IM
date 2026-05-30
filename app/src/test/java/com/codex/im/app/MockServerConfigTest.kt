package com.codex.im.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class MockServerConfigTest {
    @Test
    fun defaultConfigTargetsAdbReverseLoopback() {
        val config = MockServerConfig.default()

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals("http://127.0.0.1:8080", config.httpBaseUrl)
        assertEquals("ws://127.0.0.1:8080/ws", config.webSocketUrl)
    }

    @Test
    fun loadFromPropertiesBuildsUrlsFromHostAndPort() {
        val input = """
            host=10.0.2.2
            port=9090
        """.trimIndent().byteInputStream()

        val config = MockServerConfig.load(input)

        assertEquals("10.0.2.2", config.host)
        assertEquals(9090, config.port)
        assertEquals("http://10.0.2.2:9090", config.httpBaseUrl)
        assertEquals("ws://10.0.2.2:9090/ws", config.webSocketUrl)
    }

    @Test
    fun loadFallsBackWhenPropertiesAreBlankOrInvalid() {
        val input = ByteArrayInputStream("host= \nport=abc\n".toByteArray())

        val config = MockServerConfig.load(input)

        assertEquals(MockServerConfig.default(), config)
    }

    @Test
    fun packagedAssetTargetsAdbReverseLoopbackByDefault() {
        val asset = java.io.File("src/main/assets/mock-server.properties")
            .takeIf { it.exists() }
            ?: java.io.File("app/src/main/assets/mock-server.properties")
        val config = MockServerConfig.load(asset.inputStream())

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
    }
}
