package com.codex.im.conversation

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
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.MessagesTabUnreadBadgePolicy
import com.codex.im.ui.AvatarImage
import java.text.DateFormat
import java.util.Date

object MessageTopBarTitlePolicy {
    fun titleForUnreadCount(unreadCount: Int): String {
        val badgeText = MessagesTabUnreadBadgePolicy.badgeTextForCount(unreadCount)
        return if (badgeText == null) {
            "Message"
        } else {
            "Message($badgeText)"
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MessagesTopBar(
            unreadCount = unreadCount,
            onStartGroupChat = onStartGroupChat
        )
        HorizontalDivider()
        ConversationConnectionStatusPolicy.visibleLabel(state.connectionStatus)?.let { statusLabel ->
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items, key = { it.conversationId }) { item ->
                ConversationRow(
                    item = item,
                    onClick = {
                        viewModel.openConversation(if (item.isGroup) item.conversationId else item.peerId)
                    }
                )
                HorizontalDivider()
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = MessageTopBarTitlePolicy.titleForUnreadCount(unreadCount),
            style = MaterialTheme.typography.titleLarge
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            IconButton(
                onClick = { menuExpanded = !menuExpanded },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    painter = painterResource(id = MessageTopBarActionPolicy.addIconResId),
                    contentDescription = "More actions",
                    tint = Color.Unspecified,
                    modifier = Modifier.size(28.dp)
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(text = "发起群聊") },
                    onClick = {
                        menuExpanded = false
                        onStartGroupChat()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = "添加朋友") },
                    onClick = { menuExpanded = false }
                )
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationListItem,
    onClick: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = item.peerAvatarUrl,
            displayName = item.peerName,
            isGroup = item.isGroup,
            modifier = Modifier.size(48.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = item.peerName, style = MaterialTheme.typography.titleMedium)
                Text(text = item.lastMessageTime.displayTime(), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = ConversationListPreviewPolicy.previewAnnotatedText(
                    item = item,
                    mentionColor = MaterialTheme.colorScheme.error
                ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (item.unreadCount > 0) {
            Badge {
                Text(text = item.unreadCount.toString())
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
