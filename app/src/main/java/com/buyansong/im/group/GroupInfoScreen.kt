package com.buyansong.im.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buyansong.im.R
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImListSurface
import com.buyansong.im.ui.ByteImTopBar

@Composable
fun GroupInfoScreen(
    viewModel: GroupInfoViewModel,
    state: GroupInfoUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenUserProfile: (userId: String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ByteImColors.AppBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ByteImColors.AppBackground)
        ) {
            ByteImTopBar(
                title = GroupInfoDisplayPolicy.topBarTitle,
                onBack = onBack
            )
            Spacer(modifier = Modifier.height(12.dp))
            GroupInfoBody(
                state = state,
                onRetry = viewModel::retry,
                onOpenUserProfile = onOpenUserProfile,
                onClickGroupName = viewModel::startRename
            )
        }
    }

    if (state.showRenameDialog) {
        GroupRenameDialog(
            draftName = state.draftGroupName,
            isSaving = state.isSaving,
            errorMessage = state.errorMessage,
            onValueChange = viewModel::updateDraftGroupName,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::cancelRename
        )
    }
}

@Composable
private fun GroupInfoBody(
    state: GroupInfoUiState,
    onRetry: () -> Unit,
    onOpenUserProfile: (userId: String) -> Unit,
    onClickGroupName: () -> Unit
) {
    val group = state.group
    val members = state.members
    val isInitialLoading = state.isLoading && members.isEmpty() && group == null
    when {
        isInitialLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = ByteImColors.PrimaryGreen,
                modifier = Modifier.size(32.dp)
            )
        }
        state.errorMessage != null && members.isEmpty() && group == null -> GroupInfoFailureBlock(
            message = state.errorMessage,
            onRetry = onRetry
        )
        else -> Column(modifier = Modifier.fillMaxSize()) {
            GroupMembersGrid(
                members = members,
                onOpenUserProfile = onOpenUserProfile,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            ByteImListSurface {
                GroupNameRow(
                    groupName = group?.name.orEmpty(),
                    onClick = onClickGroupName
                )
            }
        }
    }
}

@Composable
private fun GroupMembersGrid(
    members: List<GroupMember>,
    onOpenUserProfile: (userId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (members.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = GroupInfoDisplayPolicy.emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = ByteImColors.TextSecondary
            )
        }
        return
    }
    ByteImListSurface(modifier = modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(members, key = { it.userId }) { member ->
                GroupMemberCell(
                    member = member,
                    onClick = { onOpenUserProfile(member.userId) }
                )
            }
        }
    }
}

@Composable
private fun GroupMemberCell(
    member: GroupMember,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .aspectRatio(0.85f)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        AvatarImage(
            avatarUrl = member.avatarUrl,
            displayName = member.displayName,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = member.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun GroupNameRow(
    groupName: String,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = GroupInfoDisplayPolicy.groupNameRowLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = groupName,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f)
        )
        Icon(
            painter = painterResource(id = R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = ByteImColors.TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun GroupRenameDialog(
    draftName: String,
    isSaving: Boolean,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(GroupInfoDisplayPolicy.renameDialogTitle) },
        text = {
            Column {
                TextField(
                    value = draftName,
                    onValueChange = onValueChange,
                    singleLine = true,
                    enabled = !isSaving
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSaving
            ) {
                Text(
                    text = if (isSaving) {
                        GroupInfoDisplayPolicy.savingLabel
                    } else {
                        GroupInfoDisplayPolicy.saveLabel
                    }
                )
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                enabled = !isSaving
            ) {
                Text(GroupInfoDisplayPolicy.cancelLabel)
            }
        }
    )
}

@Composable
private fun GroupInfoFailureBlock(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onRetry) {
            Text(
                text = GroupInfoDisplayPolicy.retryLabel,
                color = ByteImColors.PrimaryGreen
            )
        }
    }
}
