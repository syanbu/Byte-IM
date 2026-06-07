package com.buyansong.im.alert

data class IncomingMessageAlert(
    val conversationId: String,
    val isGroup: Boolean,
    val title: String,
    val avatarUrl: String?,
    val preview: String,
    val rawTimestamp: Long
)
