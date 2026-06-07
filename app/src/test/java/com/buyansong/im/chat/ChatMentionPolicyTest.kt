package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMentionPolicyTest {
    @Test
    fun pickerShowsOnlyForGroupDraftEndingWithAt() {
        assertTrue(ChatMentionPolicy.shouldShowPicker("hello @", isGroup = true))
        assertFalse(ChatMentionPolicy.shouldShowPicker("hello @", isGroup = false))
        assertFalse(ChatMentionPolicy.shouldShowPicker("hello @B", isGroup = true))
    }

    @Test
    fun selectingMemberAppendsDisplayNameAndTracksMention() {
        val result = ChatMentionPolicy.insertMention(
            draft = "hello @",
            selectedMentions = emptyList(),
            member = member(userId = "13900113900", displayName = "ByteDance2")
        )

        assertEquals("hello @ByteDance2 ", result.draft)
        assertEquals("hello @ByteDance2 ".length, result.cursorPosition)
        assertEquals(listOf("13900113900"), result.mentionedUserIds)
    }

    @Test
    fun activeMentionIdsDoNotKeepMentionWhenMoreLettersAreTypedIntoToken() {
        assertEquals(
            emptyList<String>(),
            ChatMentionPolicy.activeMentionIds(
                content = "@userBiloveu",
                selectedMentions = listOf(ChatMention("13900113900", "userB"))
            )
        )
    }

    @Test
    fun activeMentionIdsKeepOnlyMentionsStillPresentInText() {
        assertEquals(
            listOf("13900113900"),
            ChatMentionPolicy.activeMentionIds(
                content = "hello @ByteDance2",
                selectedMentions = listOf(
                    ChatMention("13900113900", "ByteDance2"),
                    ChatMention("17724734511", "Deleted")
                )
            )
        )
    }

    @Test
    fun highlightRangesMatchMentionDisplayText() {
        assertEquals(
            listOf(6 until 17),
            ChatMentionPolicy.highlightRanges(
                content = "hello @ByteDance2：",
                selectedMentions = listOf(ChatMention("13900113900", "ByteDance2"))
            )
        )
    }

    @Test
    fun displayTextFormatsMentionPrefixWithSpaceAndBodyText() {
        assertEquals(
            MentionDisplayText("@userB What", listOf(0 until 6)),
            ChatMentionPolicy.displayText(
                content = "@13900113900 What",
                selectedMentions = listOf(ChatMention("13900113900", "userB"))
            )
        )
    }

    @Test
    fun displayTextDoesNotMergeMentionWithBodyWithoutWhitespace() {
        assertEquals(
            MentionDisplayText("@userBaaaaaa", emptyList()),
            ChatMentionPolicy.displayText(
                content = "@13900113900aaaaaa",
                selectedMentions = listOf(ChatMention("13900113900", "userB"))
            )
        )
    }

    @Test
    fun displayTextKeepsNonPrefixMentionInOriginalSentence() {
        assertEquals(
            MentionDisplayText("hello @userB", listOf(6 until 12)),
            ChatMentionPolicy.displayText(
                content = "hello @13900113900",
                selectedMentions = listOf(ChatMention("13900113900", "userB"))
            )
        )
    }

    @Test
    fun displayTextNormalizesPrefixMentionSeparatorEvenWhenDisplayNameIsNotResolved() {
        assertEquals(
            MentionDisplayText("@userB 11", listOf(0 until 6)),
            ChatMentionPolicy.displayText(
                content = "@userB: 11",
                selectedMentions = listOf(ChatMention("13900113900", "13900113900"))
            )
        )
    }

    @Test
    fun mentionsForMessageUseGroupMemberDisplayNames() {
        assertEquals(
            listOf(ChatMention("13900113900", "ByteDance2")),
            ChatMentionPolicy.mentionsForMessage(
                mentionedUserIds = listOf("13900113900"),
                members = listOf(member(userId = "13900113900", displayName = "ByteDance2"))
            )
        )
    }

    private fun member(userId: String, displayName: String): GroupMember {
        return GroupMember(
            groupId = "g_1001",
            userId = userId,
            displayName = displayName,
            avatarUrl = null,
            role = GroupMemberRole.MEMBER,
            joinedAt = 1_000L,
            updatedAt = 1_000L
        )
    }
}
