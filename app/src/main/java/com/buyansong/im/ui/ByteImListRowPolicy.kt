package com.buyansong.im.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ByteImListRowPolicy {
    fun dividerStartPadding(): Dp {
        return ByteImDimensions.EdgePadding +
            ByteImDimensions.ListAvatarSize
    }

    fun previewEndPaddingForUnreadCount(unreadCount: Int): Dp {
        return if (unreadCount > 0) {
            40.dp
        } else {
            0.dp
        }
    }
}
