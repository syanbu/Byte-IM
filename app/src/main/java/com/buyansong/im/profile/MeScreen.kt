package com.buyansong.im.profile

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.buyansong.im.R
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImListSurface
import com.buyansong.im.ui.ByteImTopBar
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
    var showGenderEditor by remember { mutableStateOf(false) }
    var showSignatureEditor by remember { mutableStateOf(false) }
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
        showGenderEditor = false
        showSignatureEditor = false
        closeNameEditorAfterSave = false
        showProfileDetail = false
    }
    val closeNameEditor = {
        viewModel.cancelEditing()
        showNameEditor = false
        closeNameEditorAfterSave = false
    }
    val closeGenderEditor = { showGenderEditor = false }
    val closeSignatureEditor = { showSignatureEditor = false }
    val handleBack = {
        when (MeBackPolicy.action(
            showProfileDetail = showProfileDetail,
            showNameEditor = showNameEditor,
            showGenderEditor = showGenderEditor,
            showSignatureEditor = showSignatureEditor
        )) {
            MeBackAction.CloseNameEditor -> closeNameEditor()
            MeBackAction.CloseGenderEditor -> closeGenderEditor()
            MeBackAction.CloseSignatureEditor -> closeSignatureEditor()
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
    LaunchedEffect(state.isSaving) {
        if (!state.isSaving && !showProfileDetail) {
            // No-op: only auto-close when triggered from inside the editor pages
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
    } else if (showProfileDetail && showGenderEditor) {
        ProfileGenderEditorScreen(
            state = state,
            onBack = handleBack,
            onSelectGender = viewModel::updateDraftGender,
            onDone = {
                viewModel.saveGender()
                showGenderEditor = false
            }
        )
    } else if (showProfileDetail && showSignatureEditor) {
        ProfileSignatureEditorScreen(
            state = state,
            onBack = handleBack,
            onSignatureChange = viewModel::updateDraftSignature,
            onSave = {
                viewModel.saveSignature()
                showSignatureEditor = false
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
            },
            onEditGender = {
                viewModel.startEditingGender()
                showGenderEditor = true
            },
            onEditSignature = {
                viewModel.startEditingSignature()
                showSignatureEditor = true
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
    profile: com.buyansong.im.storage.UserProfile?,
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
    profile: com.buyansong.im.storage.UserProfile?,
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
    onEditName: () -> Unit,
    onEditGender: () -> Unit,
    onEditSignature: () -> Unit
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
            ProfileValueRow(
                label = MeDisplayPolicy.genderRowLabel,
                value = MeDisplayPolicy.genderLabel(profile?.gender),
                onClick = onEditGender
            )
            HorizontalDivider(color = ByteImColors.Divider)
            ProfileValueRow(
                label = MeDisplayPolicy.signatureRowLabel,
                value = profile?.signature?.takeIf { it.isNotBlank() } ?: MeDisplayPolicy.signatureUnsetLabel,
                onClick = onEditSignature
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
private fun ProfileValueRow(
    label: String,
    value: String,
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
        ChevronRightIcon()
    }
}

@Composable
private fun ProfileGenderEditorScreen(
    state: MeUiState,
    onBack: () -> Unit,
    onSelectGender: (com.buyansong.im.storage.Gender) -> Unit,
    onDone: () -> Unit
) {
    val draft = state.draftGender
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = MeDisplayPolicy.genderEditorTitle,
            onBack = onBack,
            actions = listOf {
                EditorActionButton(
                    enabled = !state.isSaving,
                    onClick = onDone,
                    label = MeDisplayPolicy.genderEditorDoneLabel
                )
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            GenderOptionRow(
                label = MeDisplayPolicy.genderMaleLabel,
                selected = draft == com.buyansong.im.storage.Gender.MALE,
                onClick = { onSelectGender(com.buyansong.im.storage.Gender.MALE) }
            )
            HorizontalDivider(color = ByteImColors.Divider)
            GenderOptionRow(
                label = MeDisplayPolicy.genderFemaleLabel,
                selected = draft == com.buyansong.im.storage.Gender.FEMALE,
                onClick = { onSelectGender(com.buyansong.im.storage.Gender.FEMALE) }
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
private fun GenderOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ByteImDimensions.ListItemHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                painter = painterResource(id = R.drawable.ic_msg_check),
                contentDescription = "已选中",
                tint = ByteImColors.PrimaryGreen,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ProfileSignatureEditorScreen(
    state: MeUiState,
    onBack: () -> Unit,
    onSignatureChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val profile = state.profile
    val original = profile?.signature.orEmpty()
    val dirty = state.draftSignature != original
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = MeDisplayPolicy.signatureEditorTitle,
            onBack = onBack,
            actions = listOf {
                EditorActionButton(
                    enabled = dirty && !state.isSaving,
                    onClick = onSave,
                    label = if (state.isSaving) "保存中" else MeDisplayPolicy.signatureEditorSaveLabel
                )
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ByteImDimensions.EdgePadding)
            ) {
                BasicTextField(
                    value = state.draftSignature,
                    onValueChange = onSignatureChange,
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
                Text(
                    text = "${state.draftSignature.length}/${MeDisplayPolicy.signatureMaxLength}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ByteImColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    textAlign = TextAlign.End
                )
            }
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
    profile: com.buyansong.im.storage.UserProfile?,
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
    val profile = state.profile
    val original = profile?.nickname.orEmpty()
    val dirty = state.draftNickname.trim() != original
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = MeDisplayPolicy.nameEditorTitle,
            onBack = onBack,
            actions = listOf {
                EditorActionButton(
                    enabled = dirty && !state.isSaving,
                    onClick = onSave,
                    label = if (state.isSaving) "保存中" else MeDisplayPolicy.nameEditorSaveLabel
                )
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

@Composable
private fun EditorActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
    label: String
) {
    Button(
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ByteImColors.PrimaryGreen,
            contentColor = Color.White,
            disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            disabledContentColor = Color.White
        )
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
