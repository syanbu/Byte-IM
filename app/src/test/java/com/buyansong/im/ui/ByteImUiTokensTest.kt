package com.buyansong.im.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ByteImUiTokensTest {
    @Test
    fun colors_matchPrototypeDirection() {
        assertEquals(Color(0xFFEDEDED), ByteImColors.AppBackground)
        assertEquals(Color(0xFF95EC69), ByteImColors.SelfBubble)
        assertEquals(Color(0xFFFFFFFF), ByteImColors.PeerBubble)
        assertEquals(Color(0xFF07C160), ByteImColors.PrimaryGreen)
        assertEquals(Color(0xFFFA5151), ByteImColors.BadgeRed)
    }

    @Test
    fun dimensions_matchPrototypeScale() {
        assertEquals(56, ByteImDimensions.TopBarHeight.value.toInt())
        assertEquals(72, ByteImDimensions.ListItemHeight.value.toInt())
        assertEquals(50, ByteImDimensions.ListAvatarSize.value.toInt())
        assertEquals(40, ByteImDimensions.ChatAvatarSize.value.toInt())
        assertEquals(16, ByteImDimensions.EdgePadding.value.toInt())
    }
}
