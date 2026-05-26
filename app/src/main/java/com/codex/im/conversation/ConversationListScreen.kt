package com.codex.im.conversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage
import java.text.DateFormat
import java.util.Date

@Composable
fun ConversationListScreen(
    viewModel: ConversationListViewModel,
    state: ConversationListUiState,
    modifier: Modifier = Modifier,
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
    LaunchedEffect(state.navigationTargetPeerId) {
        state.navigationTargetPeerId?.let { peerId ->
            onOpenConversation(peerId)
            viewModel.consumeNavigationTarget()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Messages", style = MaterialTheme.typography.headlineSmall)
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
                    onClick = { viewModel.openConversation(item.peerId) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ConversationRow(
    item: ConversationListItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = item.peerAvatarUrl,
            displayName = item.peerName,
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
                text = item.lastMessagePreview.ifBlank { "Start a conversation" },
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
