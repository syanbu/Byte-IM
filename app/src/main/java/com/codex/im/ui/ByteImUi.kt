package com.codex.im.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.R

object ByteImColors {
    val AppBackground = Color(0xFFEDEDED)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceLow = Color(0xFFFCF9F8)
    val Divider = Color(0xFFEEEEEE)
    val PrimaryGreen = Color(0xFF07C160)
    val SelfBubble = Color(0xFF95EC69)
    val PeerBubble = Color(0xFFFFFFFF)
    val BadgeRed = Color(0xFFFA5151)
    val TextPrimary = Color(0xFF1C1B1B)
    val TextSecondary = Color(0xFF808080)
    val InverseSurface = Color(0xFF313030)
}

object ByteImDimensions {
    val TopBarHeight = 56.dp
    val BottomBarHeight = 64.dp
    val ListItemHeight = 72.dp
    val ListAvatarSize = 50.dp
    val ChatAvatarSize = 40.dp
    val ProfileAvatarSize = 64.dp
    val EdgePadding = 16.dp
    val Gutter = 12.dp
    val BubbleHorizontalPadding = 12.dp
    val BubbleVerticalPadding = 8.dp
}

object ByteImShapes {
    val Avatar = RoundedCornerShape(8.dp)
    val Bubble = RoundedCornerShape(8.dp)
    val BubbleLarge = RoundedCornerShape(12.dp)
    val Notice = RoundedCornerShape(999.dp)
    val ActionMenu = RoundedCornerShape(8.dp)
}

@Composable
fun ByteImTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: List<@Composable () -> Unit> = emptyList(),
    centerTitle: Boolean = false,
    containerColor: Color = ByteImColors.Surface
) {
    // 微信风格：标题以全宽居中，back / actions 用 Modifier.align 叠加在两侧，
    // 这样无论 back 和 actions 宽度是否相等，标题都落在屏幕中线上。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ByteImDimensions.TopBarHeight)
            .background(containerColor)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = ByteImColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (centerTitle) TextAlign.Center else TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    // 非居中模式下，给标题加一个与 back 按钮等宽的起始内边距，
                    // 避免文字落在 back 按钮底下。
                    if (!centerTitle && onBack != null) {
                        it.padding(start = 48.dp)
                    } else {
                        it
                    }
                }
        )
        if (onBack != null) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.CenterStart)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_chevron_left),
                    contentDescription = "返回",
                    tint = ByteImColors.TextPrimary
                )
            }
        }
        if (actions.isNotEmpty()) {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { it() }
            }
        }
    }
}

@Composable
fun ByteImUnreadBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Badge(
        containerColor = ByteImColors.BadgeRed,
        contentColor = Color.White,
        modifier = modifier
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ByteImListSurface(
    modifier: Modifier = Modifier,
    containerColor: Color = ByteImColors.Surface,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
    ) {
        content()
    }
}

@Composable
fun ByteImSystemNotice(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary,
            modifier = Modifier
                .background(Color(0xFFE5E2E1), ByteImShapes.Notice)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

fun Modifier.byteImListClick(onClick: () -> Unit): Modifier {
    return this.clickable(onClick = onClick)
}

fun byteImBubbleShape(outgoing: Boolean): Shape {
    return if (outgoing) {
        RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp)
    } else {
        RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp)
    }
}

fun byteImBubbleColor(outgoing: Boolean): Color {
    return if (outgoing) ByteImColors.SelfBubble else ByteImColors.PeerBubble
}
