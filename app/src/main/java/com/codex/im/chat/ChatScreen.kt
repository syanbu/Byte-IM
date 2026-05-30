package com.codex.im.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codex.im.message.ChatImageCompressor
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageDirection
import com.codex.im.storage.MessageStatus
import com.codex.im.storage.MessageType
import com.codex.im.ui.AvatarImage
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var draft by remember { mutableStateOf("") }
    var previewMessage by remember { mutableStateOf<ChatMessage?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    var previousLatestMessageId by remember { mutableStateOf<String?>(null) }
    val latestMessageId = state.messages.firstOrNull()?.messageId
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val preparedImages = uris.mapNotNull { uri ->
                    ChatImageCompressor.prepareSelectedImage(context, context.contentResolver, uri)
                }
                if (preparedImages.isNotEmpty()) {
                    viewModel.sendImages(preparedImages)
                }
            }
        }
    }
    val shouldLoadEarlierHistory by remember(
        listState,
        state.messages.size,
        state.hasMoreLocal,
        state.isLoadingMore
    ) {
        derivedStateOf {
            val visibleMaxIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
            ChatAutoScrollPolicy.shouldLoadEarlierHistory(
                visibleMaxIndex = visibleMaxIndex,
                messageCount = state.messages.size,
                hasMoreLocal = state.hasMoreLocal,
                isLoadingMore = state.isLoadingMore
            )
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stop()
        }
    }

    LaunchedEffect(latestMessageId) {
        if (ChatAutoScrollPolicy.shouldScrollToLatest(previousLatestMessageId, latestMessageId)) {
            listState.animateScrollToItem(0)
        }
        previousLatestMessageId = latestMessageId
    }

    LaunchedEffect(shouldLoadEarlierHistory) {
        if (shouldLoadEarlierHistory) {
            viewModel.loadMoreHistory()
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Text(
                        text = ChatDisplayPolicy.backButtonSymbol,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(text = state.peerName, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            reverseLayout = true
        ) {
            items(state.messages, key = { it.messageId }) { message ->
                ChatMessageRow(
                    message = message,
                    peerName = state.peerName,
                    peerAvatarUrl = state.peerAvatarUrl,
                    currentUserAvatarUrl = state.currentUserAvatarUrl,
                    onOpenImagePreview = { previewMessage = it },
                    onRetryImage = { message ->
                        scope.launch {
                            viewModel.retryImageMessage(message.messageId)
                        }
                    }
                )
            }
            if (state.messages.isNotEmpty()) {
                item(key = "history-loader") {
                    ChatDisplayPolicy.historyStatusText(state)?.let { statusText ->
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        ChatComposerBar(
            draft = draft,
            onDraftChange = { draft = it },
            canSend = ChatDisplayPolicy.shouldShowSendButton(draft) && state.peerId.isNotBlank(),
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSend = {
                val content = draft
                draft = ""
                scope.launch {
                    viewModel.sendText(content)
                }
            }
        )
    }
    previewMessage?.let { message ->
        ChatImagePreviewScreen(
            message = message,
            onDismiss = { previewMessage = null }
        )
    }
}

@Composable
private fun ChatComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSend: Boolean,
    onPickImage: () -> Unit,
    onSend: () -> Unit
) {
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    val inputShape = RoundedCornerShape(18.dp)
    val action = ChatDisplayPolicy.composerAction(draft)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
            .imePadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface, inputShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape = inputShape
                    )
            ) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = ChatDisplayPolicy.composerLabel?.let { label ->
                        { Text(label) }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
            when (action) {
                ChatComposerAction.PICK_IMAGE -> Button(
                    onClick = onPickImage,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("Image")
                }
                ChatComposerAction.SEND_TEXT -> Button(
                    onClick = onSend,
                    enabled = canSend,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    peerName: String,
    peerAvatarUrl: String?,
    currentUserAvatarUrl: String?,
    onOpenImagePreview: (ChatMessage) -> Unit,
    onRetryImage: (ChatMessage) -> Unit
) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!outgoing) {
            AvatarImage(
                avatarUrl = peerAvatarUrl,
                displayName = peerName,
                modifier = Modifier.size(36.dp)
            )
        }
        if (outgoing) {
            OutgoingMessageStatus(
                status = message.status,
                onRetry = {
                    if (message.type == MessageType.IMAGE) {
                        onRetryImage(message)
                    }
                }
            )
        }
        if (message.type == MessageType.IMAGE) {
            ChatImageBubble(
                message = message,
                modifier = Modifier.padding(horizontal = 10.dp),
                onOpenPreview = onOpenImagePreview
            )
        } else {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        if (outgoing) {
            AvatarImage(
                avatarUrl = currentUserAvatarUrl,
                displayName = "Me",
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun OutgoingMessageStatus(status: MessageStatus, onRetry: () -> Unit = {}) {
    Box(
        modifier = Modifier.size(18.dp),
        contentAlignment = Alignment.Center
    ) {
        when (status) {
            MessageStatus.UPLOADING,
            MessageStatus.SENDING -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp
            )
            MessageStatus.UPLOAD_FAILED,
            MessageStatus.FAILED -> Text(
                text = "!",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onRetry)
            )
            MessageStatus.SENT,
            MessageStatus.RECEIVED -> Unit
        }
    }
}
