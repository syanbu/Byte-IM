package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatKeyboardInsetsPolicyTest {
    @Test
    fun mainActivityLetsComposeHandleKeyboardInsets() {
        val manifest = sourceFile("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("""android:windowSoftInputMode="adjustNothing""""))
    }

    @Test
    fun chatScreenAddsImePaddingToComposer() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        assertTrue(chatScreen.contains("imePadding"))
        assertFalse(chatScreen.contains("""android:windowSoftInputMode="adjustResize""""))
    }

    private fun sourceFile(path: String): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, path),
            File(userDir, "app/$path")
        )
        return candidates.first { it.exists() }
    }
}
