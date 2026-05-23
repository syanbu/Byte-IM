package com.codex.im

import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultPeerResolverTest {
    @Test
    fun returns139AccountWhenCurrentUserIs138Account() {
        assertEquals("13900113900", DefaultPeerResolver.resolve("13800113800"))
    }

    @Test
    fun returns138AccountWhenCurrentUserIs139Account() {
        assertEquals("13800113800", DefaultPeerResolver.resolve("13900113900"))
    }
}
