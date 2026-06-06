package com.codex.im.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.R
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListRowPolicy
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImShapes
import com.codex.im.ui.ByteImTopBar
import com.codex.im.ui.ConversationCreateMenu
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ContactListScreen(
    viewModel: ContactListViewModel,
    state: ContactListUiState,
    modifier: Modifier = Modifier,
    onStartGroupChat: () -> Unit,
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.firstVisibleItemIndex,
        initialFirstVisibleItemScrollOffset = state.firstVisibleItemScrollOffset
    )
    LaunchedEffect(viewModel, listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.updateScrollPosition(index, offset)
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ContactsTopBar(onStartGroupChat = onStartGroupChat)
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    HorizontalDivider(
                        color = ByteImColors.Divider,
                        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding())
                    )
                    ContactEntryBlock()
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(state.items, key = { it.userId }) { item ->
                    ContactRow(
                        item = item,
                        onClick = { viewModel.openContact(item.userId) }
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
private fun ContactsTopBar(
    onStartGroupChat: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ByteImTopBar(
        title = "通讯录",
        centerTitle = true,
        containerColor = ByteImColors.Surface,
        actions = listOf(
            {
                // 搜索图标：当前为视觉占位
                IconButton(
                    onClick = { },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = ContactTopBarActionPolicy.searchIconResId),
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
                            painter = painterResource(id = ContactTopBarActionPolicy.addIconResId),
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
private fun ContactRow(
    item: ContactListItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .background(ByteImColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
                text = "ID：${item.userId}",
                style = MaterialTheme.typography.bodyMedium,
                color = ByteImColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ContactEntryItem(
    iconResId: Int,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .background(ByteImColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(ByteImDimensions.ListAvatarSize)
                .background(ByteImColors.SurfaceLow, ByteImShapes.Avatar),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = iconResId),
                contentDescription = title,
                tint = ByteImColors.TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = ByteImColors.TextPrimary,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ContactEntryBlock() {
    ContactEntryItem(
        iconResId = R.drawable.ic_contact_new_friend,
        title = "新的朋友",
        onClick = {}
    )
    HorizontalDivider(
        color = ByteImColors.Divider,
        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding())
    )
    ContactEntryItem(
        iconResId = R.drawable.ic_contact_group_chat,
        title = "群聊",
        onClick = {}
    )
}
