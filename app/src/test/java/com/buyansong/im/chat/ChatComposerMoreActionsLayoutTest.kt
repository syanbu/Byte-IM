package com.buyansong.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatComposerMoreActionsLayoutTest {

    @Test
    fun chatScreenDoesNotRenderGlobalMoreActionsSheetOverlay() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()

        assertFalse(
            "ChatScreen must not render ChatMoreActionsSheet/Panel as a root-level overlay block " +
                "guarded by `if (showMoreActions)` after the composer; that layout covers the composer " +
                "instead of expanding below it.",
            Regex(
                """if\s*\(\s*showMoreActions\s*\)\s*\{\s*ChatMoreActions(?:Sheet|Panel)\(""",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(chatScreen)
        )
    }

    @Test
    fun chatScreenPassesMoreActionsStateIntoComposerBar() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()

        assertTrue(
            "ChatScreen should pass showMoreActions state into ChatComposerBar so the extra actions " +
                "panel expands from the composer itself instead of from a separate root overlay.",
            chatScreen.contains("showMoreActions = showMoreActions")
        )
        assertTrue(
            "ChatScreen should pass an onDismissMoreActions callback into ChatComposerBar so taps " +
                "and action completions can collapse the inline panel without affecting the rest of the screen.",
            chatScreen.contains("onDismissMoreActions = { showMoreActions = false }")
        )
    }

    @Test
    fun chatComposerBarRendersInlineMoreActionsPanelInsteadOfModalBottomSheet() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()
        val moreActionsFile = sourceFile("src/main/java/com/buyansong/im/chat/ChatMoreActionsSheet.kt").readText()

        assertTrue(
            "ChatComposerBar should render the extra actions panel inline when showMoreActions is true " +
                "and the draft is empty, so it appears below the input row instead of covering it.",
            chatScreen.contains("if (showMoreActions && !hasText)")
        )
        assertTrue(
            "ChatComposerBar should render ChatMoreActionsPanel inline under the input row.",
            chatScreen.contains("ChatMoreActionsPanel(")
        )
        assertFalse(
            "The extra actions panel implementation must not use ModalBottomSheet for the chat composer " +
                "because that creates a page-level overlay that covers the composer.",
            moreActionsFile.contains("ModalBottomSheet")
        )
    }

    @Test
    fun tappingMoreActionsHidesKeyboardBeforeShowingInlinePanel() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()

        assertTrue(
            "ChatScreen should access LocalFocusManager so the text field loses focus when the plus " +
                "button opens the inline actions panel.",
            chatScreen.contains("LocalFocusManager.current")
        )
        assertTrue(
            "ChatScreen should access LocalSoftwareKeyboardController so the software keyboard is " +
                "hidden when the plus button opens the inline actions panel.",
            chatScreen.contains("LocalSoftwareKeyboardController.current")
        )

        val moreActionsClick = extractCallbackBody(
            source = chatScreen,
            marker = "onMoreActionsClick = {"
        ) ?: error("onMoreActionsClick callback not found")

        assertTrue(
            "The plus button should clear focus before toggling showMoreActions, otherwise the text " +
                "field keeps the IME open and the inline panel appears above the keyboard.",
            moreActionsClick.contains("focusManager.clearFocus()") &&
                moreActionsClick.indexOf("focusManager.clearFocus()") <
                moreActionsClick.indexOf("showMoreActions = !showMoreActions")
        )
        assertTrue(
            "The plus button should explicitly hide the software keyboard before toggling the inline panel.",
            moreActionsClick.contains("keyboardController?.hide()") &&
                moreActionsClick.indexOf("keyboardController?.hide()") <
                moreActionsClick.indexOf("showMoreActions = !showMoreActions")
        )
    }

    @Test
    fun focusingComposerTextFieldDismissesInlineMoreActionsPanel() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()

        assertTrue(
            "ChatComposerBar should observe TextField focus changes so keyboard focus and the inline " +
                "more-actions panel are mutually exclusive.",
            chatScreen.contains("import androidx.compose.ui.focus.onFocusChanged")
        )
        assertTrue(
            "When the TextField becomes focused, ChatComposerBar should dismiss the inline more-actions panel.",
            chatScreen.contains(".onFocusChanged {") &&
                chatScreen.contains("if (it.isFocused)") &&
            chatScreen.contains("onDismissMoreActions()")
        )
    }

    @Test
    fun albumPickerPrimaryActionSendsWithoutForcedPreviewStep() {
        val chatScreen = sourceFile("src/main/java/com/buyansong/im/chat/ChatScreen.kt").readText()
        val albumPickerScreen = sourceFile("src/main/java/com/buyansong/im/chat/AlbumPickerScreen.kt").readText()

        assertTrue(
            "Album picker should label the selected-image primary action as Send, because tapping it " +
                "should directly send the selected images.",
            albumPickerScreen.contains("""Text("发送 ${'$'}{state.selected.size}")""")
        )
        assertFalse(
            "Album picker should not label the primary action as Next Step; that implies a mandatory " +
                "preview confirmation screen.",
            albumPickerScreen.contains("下一步")
        )
        assertFalse(
            "ChatScreen should not keep a previewAlbumImages state for a forced album -> preview -> send flow.",
            chatScreen.contains("previewAlbumImages")
        )
        assertFalse(
            "The album send flow should not render ImageSendPreviewScreen as a mandatory intermediate step.",
            chatScreen.contains("ImageSendPreviewScreen(")
        )
    }

    private fun sourceFile(path: String): File {
        val userDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(userDir, path),
            File(userDir, "app/$path")
        )
        return candidates.first { it.exists() }
    }

    private fun extractCallbackBody(source: String, marker: String): String? {
        val start = source.indexOf(marker)
        if (start < 0) return null
        val openBrace = source.indexOf('{', start)
        if (openBrace < 0) return null

        var depth = 0
        var i = openBrace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(openBrace + 1, i)
                    }
                }
            }
            i++
        }
        return null
    }
}
