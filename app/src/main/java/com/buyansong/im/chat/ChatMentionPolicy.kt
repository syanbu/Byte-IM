package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember

data class ChatMention(
    val userId: String,
    val displayName: String
)

data class MentionDisplayText(
    val text: String,
    val highlightRanges: List<IntRange>
)

data class ChatMentionInsertResult(
    val draft: String,
    val cursorPosition: Int,
    val selectedMentions: List<ChatMention>
) {
    val mentionedUserIds: List<String> = selectedMentions.map { it.userId }
}

object ChatMentionPolicy {
    fun shouldShowPicker(draft: String, isGroup: Boolean): Boolean {
        return isGroup && draft.endsWith("@")
    }

    fun insertMention(
        draft: String,
        selectedMentions: List<ChatMention>,
        member: GroupMember
    ): ChatMentionInsertResult {
        val displayName = member.displayName.ifBlank { member.userId }
        val prefix = if (draft.endsWith("@")) draft.dropLast(1) else draft
        val mention = ChatMention(member.userId, displayName)
        val nextDraft = "$prefix@$displayName "
        return ChatMentionInsertResult(
            draft = nextDraft,
            cursorPosition = nextDraft.length,
            selectedMentions = (selectedMentions + mention).distinctBy { it.userId }
        )
    }

    fun activeMentionIds(content: String, selectedMentions: List<ChatMention>): List<String> {
        return selectedMentions
            .filter { mention -> content.hasMentionToken(mention.displayName) || content.hasMentionToken(mention.userId) }
            .map { it.userId }
            .distinct()
    }

    fun activeMentions(content: String, selectedMentions: List<ChatMention>): List<ChatMention> {
        return selectedMentions.filter { mention ->
            content.hasMentionToken(mention.displayName) || content.hasMentionToken(mention.userId)
        }
    }

    fun highlightRanges(content: String, selectedMentions: List<ChatMention>): List<IntRange> {
        return displayText(content, selectedMentions).highlightRanges
    }

    fun displayText(content: String, selectedMentions: List<ChatMention>): MentionDisplayText {
        if (selectedMentions.isEmpty()) {
            return MentionDisplayText(content, emptyList())
        }
        val match = firstMentionMatch(content, selectedMentions)
        if (match != null) {
            val displayToken = "@${match.mention.displayName}"
            if (match.range.first == 0) {
                val bodyText = content.substring(match.range.last + 1).trimStart().trimStart(':', '：').trimStart()
                val text = if (bodyText.isEmpty()) {
                    displayToken
                } else {
                    "$displayToken $bodyText"
                }
                return MentionDisplayText(text, listOf(0 until displayToken.length))
            }
            val text = replaceMentionIdsForDisplay(content, selectedMentions)
            val ranges = rangesFor(text, displayToken)
            return MentionDisplayText(text, ranges)
        }
        if (selectedMentions.isNotEmpty() && content.startsWith("@")) {
            prefixFallbackMatch(content)?.let { range ->
                val displayToken = content.substring(range.first, range.last + 1)
                val bodyText = content.substring(range.last + 1).trimStart().trimStart(':', '：').trimStart()
                val text = if (bodyText.isEmpty()) {
                    displayToken
                } else {
                    "$displayToken $bodyText"
                }
                return MentionDisplayText(text, listOf(0 until displayToken.length))
            }
        }
        return MentionDisplayText(replaceMentionIdsForDisplay(content, selectedMentions), emptyList())
    }

    fun mentionsForMessage(mentionedUserIds: List<String>, members: List<GroupMember>): List<ChatMention> {
        val membersById = members.associateBy { it.userId }
        return mentionedUserIds.distinct().map { userId ->
            val member = membersById[userId]
            ChatMention(
                userId = userId,
                displayName = member?.displayName?.takeIf { it.isNotBlank() } ?: userId
            )
        }
    }

    private fun String.hasMentionToken(mentionText: String): Boolean {
        return rangesFor(this, "@$mentionText").isNotEmpty()
    }

    private fun firstMentionMatch(content: String, selectedMentions: List<ChatMention>): MentionTokenMatch? {
        return selectedMentions
            .flatMap { mention ->
                rangesFor(content, "@${mention.displayName}").map { range -> MentionTokenMatch(mention, range) } +
                    rangesFor(content, "@${mention.userId}").map { range -> MentionTokenMatch(mention, range) }
            }
            .minByOrNull { it.range.first }
    }

    private fun replaceMentionIdsForDisplay(content: String, selectedMentions: List<ChatMention>): String {
        return selectedMentions.fold(content) { preview, mention ->
            preview.replace("@${mention.userId}", "@${mention.displayName}")
        }
    }

    private fun rangesFor(content: String, token: String): List<IntRange> {
        if (token.isBlank()) {
            return emptyList()
        }
        val ranges = mutableListOf<IntRange>()
        var start = content.indexOf(token)
        while (start >= 0) {
            val end = start + token.length
            if (content.isTokenBoundary(end)) {
                ranges += start until end
            }
            start = content.indexOf(token, startIndex = start + token.length)
        }
        return ranges
    }

    private fun prefixFallbackMatch(content: String): IntRange? {
        val firstSeparatorIndex = content.indexOfFirst { it.isWhitespace() || it == ':' || it == '：' }
        if (firstSeparatorIndex <= 1) {
            return null
        }
        return 0 until firstSeparatorIndex
    }

    private fun String.isTokenBoundary(index: Int): Boolean {
        if (index >= length) {
            return true
        }
        val next = this[index]
        return next.isWhitespace() || next == ':' || next == '：'
    }

    private data class MentionTokenMatch(
        val mention: ChatMention,
        val range: IntRange
    )
}
