package com.buyansong.im.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buyansong.im.ui.ByteImColors

@Composable
fun GroupReadIndicator(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 2.dp, end = 56.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        Text(
            text = "$count 人已读",
            style = TextStyle(
                fontSize = 12.sp,
                color = ByteImColors.PrimaryGreen,
                fontWeight = FontWeight.Normal
            ),
            modifier = Modifier.clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            )
        )
    }
}
