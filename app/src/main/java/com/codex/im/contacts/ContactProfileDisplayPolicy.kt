package com.codex.im.contacts

import com.codex.im.storage.Gender

object ContactProfileDisplayPolicy {
    const val title = "详细资料"
    const val nicknameRowLabel = "昵称"
    const val genderRowLabel = "性别"
    const val signatureRowLabel = "个性签名"
    const val genderUnsetLabel = "未设置"
    const val signatureUnsetLabel = "未填写"
    const val sendMessageLabel = "发送消息"
    const val editProfileLabel = "编辑资料"
    const val retryLabel = "重试"
    const val loadFailedMessage = "加载失败"
    const val sessionExpiredMessage = "登录已过期，请重新登录"

    fun genderLabel(gender: Gender?): String = when (gender) {
        Gender.MALE -> "男"
        Gender.FEMALE -> "女"
        null -> genderUnsetLabel
    }
}
