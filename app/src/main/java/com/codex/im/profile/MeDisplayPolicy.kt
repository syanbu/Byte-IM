package com.codex.im.profile

import com.codex.im.storage.Gender

object MeDisplayPolicy {
    val editProfileButtonLabel: String? = null
    const val profileTitle = "个人信息"
    const val avatarRowLabel = "头像"
    const val nameRowLabel = "昵称"
    const val genderRowLabel = "性别"
    const val signatureRowLabel = "签名"
    const val idRowLabel = "ID"
    const val nameEditorTitle = "昵称"
    const val nameEditorSaveLabel = "保存"
    const val nameEditorInputStyle = "underline"
    const val genderEditorTitle = "性别"
    const val genderEditorDoneLabel = "完成"
    const val genderMaleLabel = "男"
    const val genderFemaleLabel = "女"
    const val signatureEditorTitle = "签名"
    const val signatureEditorSaveLabel = "保存"
    const val signatureMaxLength = 30
    const val genderUnsetLabel = "未设置"
    const val signatureUnsetLabel = "未填写"

    fun genderLabel(gender: Gender?): String = when (gender) {
        Gender.MALE -> genderMaleLabel
        Gender.FEMALE -> genderFemaleLabel
        null -> genderUnsetLabel
    }
}
