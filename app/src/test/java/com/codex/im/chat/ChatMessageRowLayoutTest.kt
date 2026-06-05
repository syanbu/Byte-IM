package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the chat message row layout.
 *
 * Regression coverage for keeping the long-press action bar as an overlay.
 * The action bar must not be measured as part of the message row, otherwise
 * opening it pushes the bubble and avatar around.
 */
class ChatMessageRowLayoutTest {

    @Test
    fun chatMessageRowAlignsAvatarToBubbleNotToActionBarColumn() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val rowBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageRow("
        ) ?: error("ChatMessageRow declaration not found")

        assertTrue(
            "ChatMessageRow Row must use verticalAlignment = Alignment.Bottom " +
                "so the avatar stays on the bubble's line when the action bar is shown.",
            rowBlock.contains("verticalAlignment = Alignment.Bottom")
        )
        assertFalse(
            "ChatMessageRow Row must not use verticalAlignment = Alignment.Top; " +
                "Top alignment drags the avatar up to the action bar instead of " +
                "keeping it on the bubble's row.",
            rowBlock.contains("verticalAlignment = Alignment.Top")
        )
    }

    @Test
    fun actionBarRendersInPopupOverlayNotInMessageColumn() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val contentBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageContent("
        ) ?: error("ChatMessageContent declaration not found")

        assertTrue(
            "ChatMessageContent must render long-press actions in a Popup so the " +
                "action bar does not participate in message row measurement.",
            contentBlock.contains("Popup(")
        )
        assertTrue(
            "ChatMessageActionBar must still be rendered by ChatMessageContent.",
            contentBlock.contains("ChatMessageActionBar(")
        )
        assertTrue(
            "ChatBubbleLine must render outside the Popup so the bubble remains the " +
                "measured message row content.",
            contentBlock.indexOf("ChatBubbleLine(") < contentBlock.indexOf("Popup(")
        )
    }

    @Test
    fun actionBarAppearsOnlyWhenMessageHasAvailableActions() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val contentBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageContent("
        ) ?: error("ChatMessageContent declaration not found")

        // The action bar must only render when both showActions is true AND the
        // message has at least one available action (COPY or RECALL). Without
        // this guard the action bar would always be visible and the avatar
        // would always be misaligned. The implementation can express this
        // either inline (`if (showActions && actions.isNotEmpty())`) or via a
        // derived local (`val hasActions = ...; if (hasActions)`); both keep
        // the invariant intact.
        val inlineGuard = Regex("if\\s*\\(\\s*showActions\\s*&&\\s*actions\\.isNotEmpty\\(\\)\\s*\\)")
            .containsMatchIn(contentBlock)
        val derivedGuard = Regex(
            "val\\s+hasActions\\s*=\\s*showActions\\s*&&\\s*actions\\.isNotEmpty\\(\\)\\s*"
        ).containsMatchIn(contentBlock) && Regex("if\\s*\\(\\s*hasActions\\s*\\)").containsMatchIn(contentBlock)

        assertTrue(
            "ChatMessageContent must guard ChatMessageActionBar with " +
                "showActions && actions.isNotEmpty() (inline or via a derived `hasActions` " +
                "local) to avoid permanent avatar misalignment.",
            inlineGuard || derivedGuard
        )
    }

    @Test
    fun chatMessageContentDoesNotFlipActionBarIntoTheMeasuredRow() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val contentBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageContent("
        ) ?: error("ChatMessageContent declaration not found")

        assertFalse("ChatMessageContent should not keep the old near-top flip parameter.", chatScreen.contains("isNearTop: Boolean,"))
        assertFalse("ChatMessageContent should not branch on isNearTop anymore.", contentBlock.contains("if (isNearTop)"))
        assertFalse("ChatMessageContent should not render the action bar below the bubble inside the measured row.", contentBlock.contains("} else {"))
    }

    @Test
    fun chatScreenProvidesOldestHistoryTimeSpacer() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        assertTrue(
            "ChatScreen should use itemsIndexed so the per-row index is available",
            chatScreen.contains("itemsIndexed(")
        )
        assertTrue(
            "ChatScreen must render an oldest-history time spacer above the oldest visual message.",
            chatScreen.contains("ChatHistoryTopTime(") &&
                chatScreen.contains("ChatDisplayPolicy.topTimelineTimeText(state.messages.last().createdAt)")
        )
    }

    @Test
    fun chatMessageRowAvatarsOpenUserProfile() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val rowBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageRow("
        ) ?: error("ChatMessageRow declaration not found")

        assertTrue(
            "ChatScreen must expose an onOpenUserProfile callback for avatar taps.",
            chatScreen.contains("onOpenUserProfile: (String) -> Unit = {}")
        )
        assertTrue(
            "ChatScreen must pass the onOpenUserProfile callback to each ChatMessageRow.",
            chatScreen.contains("onOpenUserProfile = onOpenUserProfile")
        )
        assertTrue(
            "ChatMessageRow must resolve the avatar's user id before invoking navigation.",
            rowBlock.contains("ChatDisplayPolicy.bubbleAvatarUserId(message, currentUserId)")
        )
        assertTrue(
            "Both incoming and outgoing AvatarImage modifiers must be clickable.",
            Regex("\\.clickable\\(enabled = avatarUserId != null\\)").findAll(rowBlock).count() >= 2
        )
        assertTrue(
            "Avatar clicks must invoke the user-profile callback with the resolved user id.",
            rowBlock.contains("avatarUserId?.let(onOpenUserProfile)")
        )
    }

    @Test
    fun chatErrorsRenderAsTransientBottomToastInsteadOfInlineErrorText() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        assertTrue(
            "ChatScreen should render transient chat errors through a bottom toast component.",
            chatScreen.contains("ChatBottomToast(")
        )
        assertTrue(
            "ChatScreen should auto-clear transient chat errors after a short delay.",
            chatScreen.contains("LaunchedEffect(message)") &&
                chatScreen.contains("delay(CHAT_ERROR_TOAST_DURATION_MS)") &&
                chatScreen.contains("viewModel.clearErrorMessage()")
        )

        val toastBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatBottomToast("
        ) ?: error("ChatBottomToast declaration not found")

        assertTrue(
            "The chat toast should use a neutral gray rounded surface rather than red inline error text.",
            toastBlock.contains("Color(0xFFE0E0E0)") &&
                toastBlock.contains("RoundedCornerShape(") &&
                toastBlock.contains("Alignment.BottomCenter")
        )
        assertFalse(
            "ChatScreen should no longer render state.errorMessage as Material error-colored inline Text.",
            chatScreen.contains("state.errorMessage?.let { message ->\n            Text(")
        )
    }

    /**
     * Returns the argument list of the first occurrence of `callName(` found
     * inside the body of the function whose declaration starts with [functionSignature].
     *
     * The match is anchored by a negative lookbehind so substrings like
     * `ChatMessageRow(` (when [callName] is `Row`) are not picked up from the
     * function name itself. Parenthesis-aware: the returned substring is the
     * text between the matching `(` and `)`, properly handling nested parens
     * such as `(String) -> Unit` and `Modifier.fillMaxWidth().padding(...)`.
     */
    private fun extractFirstCallArgs(
        source: String,
        functionSignature: String,
        callName: String
    ): String? {
        val body = extractFunctionBody(source, functionSignature) ?: return null
        // Skip past the function's opening brace so we don't accidentally
        // match the function name (e.g. "ChatMessageRow(").
        val bodyOpenBrace = body.indexOf('{')
        if (bodyOpenBrace < 0) return null
        val bodyOnly = body.substring(bodyOpenBrace)
        // Negative lookbehind for an identifier character so we don't pick up
        // a substring of a longer name like "MyRow(".
        val pattern = Regex("(?<![A-Za-z0-9_])${Regex.escape(callName)}\\(")
        val match = pattern.find(bodyOnly) ?: return null
        // The match ends just past the '('; step back by 1 to point AT the '('.
        val parenStart = match.range.last + 1
        var depth = 0
        var i = parenStart
        while (i < bodyOnly.length) {
            when (bodyOnly[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        // Return the text BETWEEN the matching parens.
                        return bodyOnly.substring(parenStart + 1, i)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Returns the full body block of a `private fun <name>(...) { ... }`
     * declaration in [source], from the signature line up to the matching
     * closing brace. Brace-aware: handles nested braces correctly.
     */
    private fun extractFunctionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
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
                        return source.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
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
