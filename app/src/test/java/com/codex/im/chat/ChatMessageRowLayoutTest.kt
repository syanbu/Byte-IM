package com.codex.im.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression coverage for the chat message row layout.
 *
 * When a user long-presses a chat bubble, the long-press action bar
 * (复制 / 撤回) appears above the bubble inside the [ChatMessageContent]
 * Column. The Row that hosts the avatar + [ChatMessageContent] must use
 * `verticalAlignment = Alignment.Bottom` so the avatar stays on the same
 * horizontal line as the bubble, instead of being pulled upward to match
 * the action bar's top edge.
 */
class ChatMessageRowLayoutTest {

    @Test
    fun chatMessageRowAlignsAvatarToBubbleNotToActionBarColumn() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val rowCallArgs = extractFirstCallArgs(
            source = chatScreen,
            functionSignature = "private fun ChatMessageRow(",
            callName = "Row"
        ) ?: error("Row call inside ChatMessageRow not found")

        assertTrue(
            "ChatMessageRow Row must use verticalAlignment = Alignment.Bottom " +
                "so the avatar stays on the bubble's line when the action bar is shown.",
            rowCallArgs.contains("verticalAlignment = Alignment.Bottom")
        )
        assertFalse(
            "ChatMessageRow Row must not use verticalAlignment = Alignment.Top; " +
                "Top alignment drags the avatar up to the action bar instead of " +
                "keeping it on the bubble's row.",
            rowCallArgs.contains("verticalAlignment = Alignment.Top")
        )
    }

    @Test
    fun actionBarRendersAboveBubbleInTheNormalElseBranch() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        val contentBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageContent("
        ) ?: error("ChatMessageContent declaration not found")

        // Find the else branch (the normal, non-near-top layout) and verify
        // that within it the action bar is rendered ABOVE the bubble, the
        // behavior used in the original implementation.
        val elseMarker = contentBlock.indexOf("} else {")
        assertTrue(
            "ChatMessageContent must have an else branch for the normal layout",
            elseMarker >= 0
        )
        val elseBranch = contentBlock.substring(elseMarker)
        val elseActionIdx = elseBranch.indexOf("ChatMessageActionBar(")
        val elseBubbleIdx = elseBranch.indexOf("ChatBubbleLine(")

        assertTrue("ChatMessageActionBar must be present in the else branch", elseActionIdx >= 0)
        assertTrue("ChatBubbleLine must be present in the else branch", elseBubbleIdx >= 0)
        assertTrue(
            "In the else (non-near-top) branch ChatMessageActionBar must render " +
                "before ChatBubbleLine so the action bar appears above the bubble " +
                "and the avatar stays on the bubble's row.",
            elseActionIdx < elseBubbleIdx
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
    fun chatMessageContentFlipsActionBarBelowBubbleWhenMessageIsNearTop() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        // ChatMessageContent should accept an isNearTop parameter and use it
        // to swap the order of the action bar and the bubble. When isNearTop
        // is true the bubble is rendered first and the action bar is rendered
        // below it, so the action bar does not overflow above the LazyColumn
        // when the bubble is at the top of the chat area.
        assertTrue(
            "ChatMessageContent must accept an isNearTop: Boolean parameter",
            chatScreen.contains("isNearTop: Boolean,")
        )

        val contentBlock = extractFunctionBody(
            source = chatScreen,
            signature = "private fun ChatMessageContent("
        ) ?: error("ChatMessageContent declaration not found")

        val nearTopBranchStart = contentBlock.indexOf("if (isNearTop) {")
        assertTrue(
            "ChatMessageContent must branch on isNearTop to flip the action bar position",
            nearTopBranchStart >= 0
        )
        val elseMarker = contentBlock.indexOf("} else {", nearTopBranchStart)
        assertTrue(
            "ChatMessageContent must provide an else branch for the normal (non-near-top) layout",
            elseMarker > nearTopBranchStart
        )

        // In the isNearTop branch, the bubble must come BEFORE the action bar
        // so the action bar renders below the bubble.
        val nearTopBranch = contentBlock.substring(nearTopBranchStart, elseMarker)
        val nearTopBubbleIdx = nearTopBranch.indexOf("ChatBubbleLine(")
        val nearTopActionIdx = nearTopBranch.indexOf("ChatMessageActionBar(")
        assertTrue(
            "In the isNearTop branch ChatBubbleLine must render before ChatMessageActionBar " +
                "so the action bar appears below the bubble.",
            nearTopBubbleIdx >= 0 && nearTopActionIdx >= 0 && nearTopBubbleIdx < nearTopActionIdx
        )

        // In the else branch, the action bar must come BEFORE the bubble
        // (the original behavior).
        val elseBranch = contentBlock.substring(elseMarker)
        val elseActionIdx = elseBranch.indexOf("ChatMessageActionBar(")
        val elseBubbleIdx = elseBranch.indexOf("ChatBubbleLine(")
        assertTrue(
            "In the else branch ChatMessageActionBar must render before ChatBubbleLine " +
                "to preserve the original above-bubble action bar position.",
            elseActionIdx >= 0 && elseBubbleIdx >= 0 && elseActionIdx < elseBubbleIdx
        )
    }

    @Test
    fun chatScreenComputesIsNearTopAndPassesItToEachMessageRow() {
        val chatScreen = sourceFile("src/main/java/com/codex/im/chat/ChatScreen.kt").readText()

        // The chat screen must observe the LazyColumn layout to detect which
        // message is at the visual top, then forward that decision to each
        // ChatMessageRow.
        assertTrue(
            "ChatScreen should use itemsIndexed so the per-row index is available",
            chatScreen.contains("itemsIndexed(")
        )
        assertTrue(
            "ChatScreen must derive the topmost visible message's data index from listState.layoutInfo",
            chatScreen.contains("visibleItemsInfo.maxOfOrNull { it.index }")
        )
        assertTrue(
            "ChatScreen must detect 'at the top' via canScrollForward so we don't flip " +
                "action bar position in the middle of the chat unnecessarily",
            chatScreen.contains("canScrollForward")
        )
        assertTrue(
            "ChatMessageRow call site must pass isNearTop = isNearTop",
            Regex("isNearTop\\s*=\\s*isNearTop").containsMatchIn(chatScreen)
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
