package com.codex.im.contacts

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
fun ContactListScreen(
    viewModel: ContactListViewModel,
    state: ContactListUiState,
    modifier: Modifier = Modifier,
    onOpenContact: (String) -> Unit
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
            onOpenContact(peerId)
            viewModel.consumeNavigationTarget()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Contacts", style = MaterialTheme.typography.headlineSmall)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.items, key = { it.userId }) { item ->
                ContactRow(
                    item = item,
                    onClick = { viewModel.openContact(item.userId) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ContactRow(
    item: ContactListItem,
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
