package com.buyansong.im.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buyansong.im.ui.ByteImColors

@Composable
fun GroupReadIndicator(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ByteImColors.PrimaryGreen
) {
    if (count <= 0) return
    Text(
        text = "$count 人已读",
        style = TextStyle(
            fontSize = 12.sp,
            color = color,
            fontWeight = FontWeight.Normal
        ),
        modifier = modifier
            .padding(top = 3.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
    )
}
