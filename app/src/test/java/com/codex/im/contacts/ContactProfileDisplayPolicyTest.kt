package com.codex.im.contacts

import com.codex.im.storage.Gender
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactProfileDisplayPolicyTest {
    @Test
    fun titleIsDetailedProfile() {
        assertEquals("详细资料", ContactProfileDisplayPolicy.title)
    }

    @Test
    fun rowLabelsAreStable() {
        assertEquals("昵称", ContactProfileDisplayPolicy.nicknameRowLabel)
        assertEquals("性别", ContactProfileDisplayPolicy.genderRowLabel)
        assertEquals("个性签名", ContactProfileDisplayPolicy.signatureRowLabel)
    }

    @Test
    fun unsetLabelsAreStable() {
        assertEquals("未设置", ContactProfileDisplayPolicy.genderUnsetLabel)
        assertEquals("未填写", ContactProfileDisplayPolicy.signatureUnsetLabel)
    }

    @Test
    fun bottomBarAndFailureLabelsAreStable() {
        assertEquals("发送消息", ContactProfileDisplayPolicy.sendMessageLabel)
        assertEquals("重试", ContactProfileDisplayPolicy.retryLabel)
        assertEquals("加载失败", ContactProfileDisplayPolicy.loadFailedMessage)
        assertEquals("登录已过期，请重新登录", ContactProfileDisplayPolicy.sessionExpiredMessage)
    }

    @Test
    fun genderLabelMapsMale() {
        assertEquals("男", ContactProfileDisplayPolicy.genderLabel(Gender.MALE))
    }

    @Test
    fun genderLabelMapsFemale() {
        assertEquals("女", ContactProfileDisplayPolicy.genderLabel(Gender.FEMALE))
    }

    @Test
    fun genderLabelMapsNullToUnset() {
        assertEquals(ContactProfileDisplayPolicy.genderUnsetLabel, ContactProfileDisplayPolicy.genderLabel(null))
    }
}
