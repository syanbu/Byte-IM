package com.buyansong.im.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.buyansong.im.storage.UserProfile
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImListSurface
import com.buyansong.im.ui.ByteImTopBar

@Composable
fun ContactProfileScreen(
    viewModel: ContactProfileViewModel,
    state: ContactProfileUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSendMessage: (peerUserId: String) -> Unit
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.stop() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ByteImColors.AppBackground,
        bottomBar = {
            ContactProfileBottomBar(
                enabled = state.profile != null,
                onClick = { onSendMessage(viewModel.userId) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ByteImColors.AppBackground)
        ) {
            ByteImTopBar(title = ContactProfileDisplayPolicy.title, onBack = onBack)
            Spacer(modifier = Modifier.height(12.dp))
            ContactProfileBody(
                state = state,
                onRetry = viewModel::retry
            )
        }
    }
}

@Composable
private fun ContactProfileBody(
    state: ContactProfileUiState,
    onRetry: () -> Unit
) {
    val profile = state.profile
    when {
        profile != null -> ContactProfileContent(profile = profile)
        state.errorMessage != null -> ContactProfileFailureBlock(
            message = state.errorMessage,
            onRetry = onRetry
        )
        else -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = ByteImColors.PrimaryGreen,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun ContactProfileContent(profile: UserProfile) {
    Column(modifier = Modifier.fillMaxSize()) {
        ContactProfileHeader(profile = profile)
        Spacer(modifier = Modifier.height(12.dp))
        ByteImListSurface {
            ContactProfileDataRows(profile = profile)
        }
    }
}

@Composable
private fun ContactProfileHeader(profile: UserProfile) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ByteImColors.AppBackground)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AvatarImage(
            avatarUrl = profile.avatarUrl,
            displayName = profile.nickname,
            modifier = Modifier.size(ByteImDimensions.ProfileAvatarSize)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = profile.nickname,
            style = MaterialTheme.typography.titleLarge,
            color = ByteImColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactProfileDataRows(profile: UserProfile) {
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.nicknameRowLabel,
        value = profile.nickname
    )
    HorizontalDivider(color = ByteImColors.Divider)
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.genderRowLabel,
        value = ContactProfileDisplayPolicy.genderLabel(profile.gender)
    )
    HorizontalDivider(color = ByteImColors.Divider)
    ContactProfileValueRow(
        label = ContactProfileDisplayPolicy.signatureRowLabel,
        value = profile.signature?.takeIf { it.isNotBlank() }
            ?: ContactProfileDisplayPolicy.signatureUnsetLabel,
        maxLines = 2
    )
}

@Composable
private fun ContactProfileValueRow(
    label: String,
    value: String,
    maxLines: Int = 1
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (maxLines > 1) 80.dp else ByteImDimensions.ListItemHeight)
            .padding(horizontal = ByteImDimensions.EdgePadding),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ContactProfileFailureBlock(
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
                text = ContactProfileDisplayPolicy.retryLabel,
                color = ByteImColors.PrimaryGreen
            )
        }
    }
}

@Composable
private fun ContactProfileBottomBar(
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ByteImColors.Surface)
    ) {
        HorizontalDivider(color = ByteImColors.Divider)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    PaddingValues(
                        horizontal = ByteImDimensions.EdgePadding,
                        vertical = 12.dp
                    )
                )
        ) {
            Button(
                enabled = enabled,
                onClick = onClick,
                shape = RoundedCornerShape(6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ByteImColors.PrimaryGreen,
                    contentColor = Color.White,
                    disabledContainerColor = ByteImColors.PrimaryGreen.copy(alpha = 0.4f),
                    disabledContentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(
                    text = ContactProfileDisplayPolicy.sendMessageLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
