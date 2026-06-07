package com.buyansong.im.profile

import com.buyansong.im.storage.Gender
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
        assertEquals("性别", MeDisplayPolicy.genderRowLabel)
        assertEquals("签名", MeDisplayPolicy.signatureRowLabel)
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

    @Test
    fun genderEditorLabels() {
        assertEquals("性别", MeDisplayPolicy.genderEditorTitle)
        assertEquals("完成", MeDisplayPolicy.genderEditorDoneLabel)
        assertEquals("男", MeDisplayPolicy.genderMaleLabel)
        assertEquals("女", MeDisplayPolicy.genderFemaleLabel)
    }

    @Test
    fun genderLabelMapsEnumToDisplayString() {
        assertEquals("男", MeDisplayPolicy.genderLabel(Gender.MALE))
        assertEquals("女", MeDisplayPolicy.genderLabel(Gender.FEMALE))
        assertEquals("未设置", MeDisplayPolicy.genderLabel(null))
    }

    @Test
    fun signatureEditorLabelsAndLimit() {
        assertEquals("签名", MeDisplayPolicy.signatureEditorTitle)
        assertEquals("保存", MeDisplayPolicy.signatureEditorSaveLabel)
        assertEquals(30, MeDisplayPolicy.signatureMaxLength)
        assertEquals("未填写", MeDisplayPolicy.signatureUnsetLabel)
    }
}
