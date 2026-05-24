package com.codex.im.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage
import kotlinx.coroutines.launch

@Composable
fun MeScreen(
    viewModel: MeViewModel,
    state: MeUiState,
    modifier: Modifier = Modifier,
    onExitApp: () -> Unit,
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
            MeBackAction.ExitApp -> onExitApp()
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(text = "Me", style = MaterialTheme.typography.headlineSmall)
        ProfileSummaryRow(
            profile = profile,
            onClick = onOpenProfile
        )
        HorizontalDivider()
        Button(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Logout")
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
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
            .padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AvatarImage(
            avatarUrl = profile?.avatarUrl,
            displayName = profile?.nickname ?: profile?.phone ?: "",
            modifier = Modifier.size(72.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile?.nickname.orEmpty(),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "ID: ${profile?.phone.orEmpty()}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("<")
            }
            Text(text = MeDisplayPolicy.profileTitle, style = MaterialTheme.typography.headlineSmall)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            ProfileAvatarRow(
                profile = profile,
                onClick = onChooseAvatar
            )
            HorizontalDivider()
            ProfileNameRow(
                state = state,
                onEditName = onEditName
            )
            HorizontalDivider()
            ProfileReadOnlyRow(
                label = MeDisplayPolicy.idRowLabel,
                value = profile?.phone.orEmpty()
            )
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
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
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = MeDisplayPolicy.avatarRowLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        AvatarImage(
            avatarUrl = profile?.avatarUrl,
            displayName = profile?.nickname ?: profile?.phone ?: "",
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            .clickable(onClick = onEditName)
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = MeDisplayPolicy.nameRowLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = state.profile?.nickname.orEmpty(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(0.65f)
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("<")
            }
            Text(
                text = MeDisplayPolicy.nameEditorTitle,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                enabled = !state.isSaving,
                onClick = onSave
            ) {
                Text(if (state.isSaving) "Saving" else MeDisplayPolicy.nameEditorSaveLabel)
            }
        }
        UnderlinedNameTextField(
            value = state.draftNickname,
            onValueChange = onNicknameChange,
            modifier = Modifier.fillMaxWidth()
        )
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
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
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            .padding(vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.65f)
        )
    }
}
