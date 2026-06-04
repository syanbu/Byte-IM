package com.codex.im.conversation

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.MessagesTabUnreadBadgePolicy
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListRowPolicy
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImSystemNotice
import com.codex.im.ui.ByteImTopBar
import com.codex.im.ui.ByteImUnreadBadge
import com.codex.im.ui.ConversationCreateMenu
import java.text.DateFormat
import java.util.Date

object MessageTopBarTitlePolicy {
    fun titleForUnreadCount(unreadCount: Int): String {
        val badgeText = MessagesTabUnreadBadgePolicy.badgeTextForCount(unreadCount)
        return if (badgeText == null) {
            "消息"
        } else {
            "消息($badgeText)"
        }
    }
}

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    state: ConversationListUiState,
    unreadCount: Int,
    modifier: Modifier = Modifier,
    onStartGroupChat: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stop()
        }
    }
    LaunchedEffect(state.navigationTargetConversationId) {
        state.navigationTargetConversationId?.let { conversationId ->
            onOpenConversation(conversationId)
            viewModel.consumeNavigationTarget()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        MessagesTopBar(
            unreadCount = unreadCount,
            onStartGroupChat = onStartGroupChat
        )
        ConversationConnectionStatusPolicy.visibleLabel(state.connectionStatus)?.let { statusLabel ->
            ByteImSystemNotice(
                text = statusLabel,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { _, item -> item.conversationId }) { index, item ->
                    if (index == 0) {
                        HorizontalDivider(
                            color = ByteImColors.Divider,
                            modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding())
                        )
                    }
                    ConversationRow(
                        item = item,
                        onClick = {
                            viewModel.openConversation(if (item.isGroup) item.conversationId else item.peerId)
                        }
                    )
                    HorizontalDivider(
                        color = ByteImColors.Divider,
                        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding())
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagesTopBar(
    unreadCount: Int,
    onStartGroupChat: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ByteImTopBar(
        title = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
        centerTitle = true,
        containerColor = ByteImColors.Surface,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位，后续接搜索路由
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = MessageTopBarActionPolicy.searchIconResId),
                        contentDescription = "搜索",
                        tint = ByteImColors.TextPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            },
            {
                Box {
                    IconButton(
                        onClick = { menuExpanded = !menuExpanded },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = MessageTopBarActionPolicy.addIconResId),
                            contentDescription = "更多操作",
                            tint = ByteImColors.TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    ConversationCreateMenu(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onStartGroupChat = onStartGroupChat
                    )
                }
            }
        )
    )
}

@Composable
private fun ConversationRow(
    item: ConversationListItem,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .background(ByteImColors.Surface)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = item.peerAvatarUrl,
            displayName = item.peerName,
            isGroup = item.isGroup,
            modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = ByteImColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = item.lastMessageTime.displayTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ByteImColors.TextSecondary
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = ConversationListPreviewPolicy.previewAnnotatedText(
                        item = item,
                        mentionColor = ByteImColors.BadgeRed
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ByteImColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        end = ByteImListRowPolicy.previewEndPaddingForUnreadCount(item.unreadCount)
                    )
                )
                if (item.unreadCount > 0) {
                    ByteImUnreadBadge(
                        text = if (item.unreadCount >= 100) "99+" else item.unreadCount.toString(),
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }
    }
}

private fun Long.displayTime(): String {
    if (this <= 0L) {
        return ""
    }
    return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(this))
}
