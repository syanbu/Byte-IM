package com.codex.im.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codex.im.R
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImDimensions
import com.codex.im.ui.ByteImListSurface
import com.codex.im.ui.ByteImTopBar
import kotlinx.coroutines.launch

@Composable
fun MeScreen(
    viewModel: MeViewModel,
    state: MeUiState,
    modifier: Modifier = Modifier,
    onMoveTaskToBack: () -> Unit,
    onLogout: suspend () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = AvatarImageCompressor.compressJpegUnderLimit(context.contentResolver, uri)
                if (bytes != null) {
                    viewModel.saveAvatarBytes(bytes)
                }
            }
        }
    }
    var showProfileDetail by remember { mutableStateOf(false) }
    var showNameEditor by remember { mutableStateOf(false) }
    var closeNameEditorAfterSave by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    val profile = state.profile
    val closeProfileDetail = {
        if (state.isEditing) {
            viewModel.cancelEditing()
        }
        showNameEditor = false
        closeNameEditorAfterSave = false
        showProfileDetail = false
    }
    val closeNameEditor = {
        viewModel.cancelEditing()
        showNameEditor = false
        closeNameEditorAfterSave = false
    }
    val handleBack = {
        when (MeBackPolicy.action(showProfileDetail = showProfileDetail, showNameEditor = showNameEditor)) {
            MeBackAction.CloseNameEditor -> closeNameEditor()
            MeBackAction.CloseProfileDetail -> closeProfileDetail()
            MeBackAction.MoveTaskToBack -> onMoveTaskToBack()
        }
    }
    BackHandler(onBack = handleBack)
    LaunchedEffect(closeNameEditorAfterSave, state.isEditing, state.isSaving, state.errorMessage) {
        if (closeNameEditorAfterSave && !state.isEditing && !state.isSaving && state.errorMessage == null) {
            closeNameEditorAfterSave = false
            showNameEditor = false
        }
    }

    if (showProfileDetail && showNameEditor) {
        ProfileNameEditorScreen(
            state = state,
            onBack = handleBack,
            onNicknameChange = viewModel::updateDraftNickname,
            onSave = {
                closeNameEditorAfterSave = true
                viewModel.saveProfile()
            }
        )
    } else if (showProfileDetail) {
        ProfileDetailScreen(
            state = state,
            onBack = handleBack,
            onChooseAvatar = {
                avatarPicker.launch("image/*")
            },
            onEditName = {
                viewModel.startEditing()
                closeNameEditorAfterSave = false
                showNameEditor = true
            }
        )
    } else {
        MeHomeScreen(
            profile = profile,
            state = state,
            modifier = modifier,
            onOpenProfile = { showProfileDetail = true },
            onLogout = { scope.launch { onLogout() } }
        )
    }
}

@Composable
private fun MeHomeScreen(
    profile: com.codex.im.storage.UserProfile?,
    state: MeUiState,
    modifier: Modifier = Modifier,
    onOpenProfile: () -> Unit,
    onLogout: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ProfileSummaryRow(
            profile = profile,
            onClick = onOpenProfile
        )
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            ServicesRow()
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onLogout,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ByteImDimensions.EdgePadding),
            colors = ButtonDefaults.buttonColors(
                containerColor = ByteImColors.Surface,
                contentColor = ByteImColors.BadgeRed
            )
        ) {
            Text("退出登录")
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = ByteImDimensions.EdgePadding)
            )
        }
    }
}

@Composable
private fun ProfileSummaryRow(
    profile: com.codex.im.storage.UserProfile?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(ByteImColors.Surface)
            .padding(horizontal = ByteImDimensions.EdgePadding, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = profile?.avatarUrl,
            displayName = profile?.nickname ?: profile?.phone ?: "",
            modifier = Modifier.size(ByteImDimensions.ProfileAvatarSize)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.nickname.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                color = ByteImColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ID：${profile?.phone.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
                color = ByteImColors.TextSecondary
            )
        }
        ChevronRightIcon()
    }
}

@Composable
private fun ServicesRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "服务",
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        ChevronRightIcon()
    }
}

@Composable
private fun ProfileDetailScreen(
    state: MeUiState,
    onBack: () -> Unit,
    onChooseAvatar: () -> Unit,
    onEditName: () -> Unit
) {
    val profile = state.profile
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(title = MeDisplayPolicy.profileTitle, onBack = onBack)
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            ProfileAvatarRow(
                profile = profile,
                onClick = onChooseAvatar
            )
            HorizontalDivider(color = ByteImColors.Divider)
            ProfileNameRow(
                state = state,
                onEditName = onEditName
            )
            HorizontalDivider(color = ByteImColors.Divider)
            ProfileReadOnlyRow(
                label = MeDisplayPolicy.idRowLabel,
                value = profile?.phone.orEmpty()
            )
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = ByteImDimensions.EdgePadding, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ProfileAvatarRow(
    profile: com.codex.im.storage.UserProfile?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = MeDisplayPolicy.avatarRowLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        AvatarImage(
            avatarUrl = profile?.avatarUrl,
            displayName = profile?.nickname ?: profile?.phone ?: "",
            modifier = Modifier.size(48.dp)
        )
        ChevronRightIcon()
    }
}

@Composable
private fun ProfileNameRow(
    state: MeUiState,
    onEditName: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .clickable(onClick = onEditName)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = MeDisplayPolicy.nameRowLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = state.profile?.nickname.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f)
        )
        ChevronRightIcon()
    }
}

@Composable
private fun ProfileNameEditorScreen(
    state: MeUiState,
    onBack: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = MeDisplayPolicy.nameEditorTitle,
            onBack = onBack,
            actions = listOf {
                TextButton(
                    enabled = !state.isSaving,
                    onClick = onSave
                ) {
                    Text(
                        text = if (state.isSaving) "保存中" else MeDisplayPolicy.nameEditorSaveLabel,
                        color = ByteImColors.PrimaryGreen
                    )
                }
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            UnderlinedNameTextField(
                value = state.draftNickname,
                onValueChange = onNicknameChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ByteImDimensions.EdgePadding)
            )
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = ByteImDimensions.EdgePadding, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun ChevronRightIcon() {
    Icon(
        painter = painterResource(id = R.drawable.ic_chevron_right),
        contentDescription = "打开",
        tint = ByteImColors.TextSecondary,
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun UnderlinedNameTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = ByteImColors.TextPrimary
            ),
            cursorBrush = SolidColor(ByteImColors.PrimaryGreen),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
        HorizontalDivider(color = ByteImColors.Divider)
    }
}

@Composable
private fun ProfileReadOnlyRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.65f)
        )
    }
}
