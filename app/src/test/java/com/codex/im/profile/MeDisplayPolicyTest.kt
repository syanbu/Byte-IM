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
        assertEquals("Avatar", MeDisplayPolicy.avatarRowLabel)
        assertEquals("Name", MeDisplayPolicy.nameRowLabel)
        assertEquals("ID", MeDisplayPolicy.idRowLabel)
    }

    @Test
    fun nameEditorUsesDedicatedPageSaveAction() {
        assertEquals("Name", MeDisplayPolicy.nameEditorTitle)
        assertEquals("Save", MeDisplayPolicy.nameEditorSaveLabel)
    }

    @Test
    fun nameEditorUsesUnderlineInputStyle() {
        assertEquals("underline", MeDisplayPolicy.nameEditorInputStyle)
    }
}
