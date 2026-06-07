package com.buyansong.im.group

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
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImListRowPolicy
import com.buyansong.im.ui.ByteImListSurface
import com.buyansong.im.ui.ByteImTopBar

@Composable
fun JoinedGroupsScreen(
    viewModel: JoinedGroupsViewModel,
    state: JoinedGroupsUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenGroup: (groupId: String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stop()
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = "群聊",
            onBack = onBack,
            centerTitle = true,
            containerColor = ByteImColors.Surface
        )
        ByteImListSurface(
            modifier = Modifier.weight(1f),
            containerColor = ByteImColors.AppBackground
        ) {
            if (state.items.isEmpty() && !state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无群聊",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ByteImColors.TextSecondary
                    )
                }
                return@ByteImListSurface
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    HorizontalDivider(
                        color = ByteImColors.Divider,
                        modifier = Modifier.padding(start = ByteImListRowPolicy.dividerStartPadding())
                    )
                }
                items(state.items, key = { it.groupId }) { item ->
                    JoinedGroupRow(
                        item = item,
                        onClick = { onOpenGroup(item.groupId) }
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
private fun JoinedGroupRow(
    item: JoinedGroupItem,
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
            displayName = item.name,
            isGroup = true,
            modifier = Modifier.size(ByteImDimensions.ListAvatarSize)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = ByteImColors.TextPrimary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.memberCount > 0) {
                Text(
                    text = "${item.memberCount} 位成员",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ByteImColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
