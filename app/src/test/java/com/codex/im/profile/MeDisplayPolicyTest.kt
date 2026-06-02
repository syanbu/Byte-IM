package com.codex.im.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeDisplayPolicyTest {
    @Test
    fun meHomeDoesNotShowEditProfileButton() {
        assertNull(MeDisplayPolicy.editProfileButtonLabel)
    }

    @Test
    fun profileDetailRowsMatchPersonalInfoList() {
        assertEquals("头像", MeDisplayPolicy.avatarRowLabel)
        assertEquals("昵称", MeDisplayPolicy.nameRowLabel)
        assertEquals("ID", MeDisplayPolicy.idRowLabel)
    }

    @Test
    fun nameEditorUsesDedicatedPageSaveAction() {
        assertEquals("昵称", MeDisplayPolicy.nameEditorTitle)
        assertEquals("保存", MeDisplayPolicy.nameEditorSaveLabel)
    }

    @Test
    fun nameEditorUsesUnderlineInputStyle() {
        assertEquals("underline", MeDisplayPolicy.nameEditorInputStyle)
    }
}
