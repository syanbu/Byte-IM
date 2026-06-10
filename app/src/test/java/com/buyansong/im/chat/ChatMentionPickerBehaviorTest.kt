package com.buyansong.im.chat

import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.GroupMemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMentionPickerBehaviorTest {

    @Test
    fun pickerIsShownWhenGroupDraftEndsWithAt() {
        assertTrue(ChatMentionPolicy.shouldShowPicker(draft = "@", isGroup = true))
        assertTrue(ChatMentionPolicy.shouldShowPicker(draft = "hello @", isGroup = true))
    }

    @Test
    fun pickerIsHiddenWhenSingleChatDraftEndsWithAt() {
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "@", isGroup = false))
    }

    @Test
    fun pickerIsHiddenWhenGroupDraftDoesNotEndWithAt() {
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "@alice hello", isGroup = true))
        assertFalse(ChatMentionPolicy.shouldShowPicker(draft = "", isGroup = true))
    }

    @Test
    fun insertMentionClosesPickerByChangingDraft() {
        val member = GroupMember("g", "u1", "Alice", null, GroupMemberRole.MEMBER, 0L, 0L)
        val result = ChatMentionPolicy.insertMention("@", emptyList(), member)
        assertFalse(ChatMentionPolicy.shouldShowPicker(result.draft, isGroup = true))
    }

    @Test
    fun insertMentionPlacesCursorAfterInsertedMention() {
        val member = GroupMember("g", "u1", "Alice", null, GroupMemberRole.MEMBER, 0L, 0L)
        val result = ChatMentionPolicy.insertMention("@", emptyList(), member)
        assertEquals(result.draft.length, result.cursorPosition)
    }
}
