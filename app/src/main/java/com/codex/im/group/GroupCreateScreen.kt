package com.codex.im.group

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "发起群聊", style = MaterialTheme.typography.headlineSmall)
            Button(
                enabled = state.canCreate && !state.isCreating,
                onClick = { viewModel.createGroup() }
            ) {
                Text(text = if (state.isCreating) "创建中" else "创建")
            }
        }
        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.contacts, key = { it.userId }) { item ->
                GroupCreateContactRow(
                    item = item,
                    onClick = { viewModel.toggleContact(item.userId) }
                )
                HorizontalDivider()
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
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
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
            modifier = Modifier.size(48.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.displayName, style = MaterialTheme.typography.titleMedium)
            Text(text = item.userId, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
