package com.codex.im.group

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImTopBar

@Composable
fun GroupCreateScreen(
    viewModel: GroupCreateViewModel,
    state: GroupCreateUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stop()
        }
    }
    LaunchedEffect(state.createdConversationId) {
        state.createdConversationId?.let { conversationId ->
            viewModel.consumeCreatedConversation()
            onCreated(conversationId)
        }
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = "发起群聊",
            onBack = onBack,
            action = {
                Button(
                    enabled = state.canCreate && !state.isCreating,
                    onClick = { viewModel.createGroup() },
                    colors = ButtonDefaults.buttonColors(containerColor = ByteImColors.PrimaryGreen)
                ) {
                    Text(text = if (state.isCreating) "创建中" else "创建")
                }
            }
        )
        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = ByteImDimensions.EdgePadding, vertical = 8.dp)
            )
        }
        ByteImListSurface(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.contacts, key = { it.userId }) { item ->
                    GroupCreateContactRow(
                        item = item,
                        onClick = { viewModel.toggleContact(item.userId) }
                    )
                    HorizontalDivider(color = ByteImColors.Divider)
                }
            }
        }
    }
}

@Composable
private fun GroupCreateContactRow(
    item: GroupCreateContactItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = item.isSelected,
            onCheckedChange = { onClick() }
        )
        AvatarImage(
            avatarUrl = item.avatarUrl,
            displayName = item.displayName,
            modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = ByteImColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "ID: ${item.userId}",
                style = MaterialTheme.typography.bodyMedium,
                color = ByteImColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
