package com.codex.im.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.codex.im.message.ChatImageCompressor
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.GroupMember
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
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var previewMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var activeActionMessageId by remember { mutableStateOf<String?>(null) }
    var showGroupRename by remember { mutableStateOf(false) }
    var groupNameDraft by remember { mutableStateOf(state.peerName) }
    var selectedMentions by remember { mutableStateOf(emptyList<ChatMention>()) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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

    LaunchedEffect(state.peerName) {
        if (!showGroupRename) {
            groupNameDraft = state.peerName
        }
    }

    LaunchedEffect(shouldLoadEarlierHistory) {
        if (shouldLoadEarlierHistory) {
            viewModel.loadMoreHistory()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = { activeActionMessageId = null }
            )
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
                    Icon(
                        painter = painterResource(id = ChatDisplayPolicy.backButtonIconRes),
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                text = state.peerName,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            if (state.peerId.startsWith("group:")) {
                IconButton(onClick = { showGroupRename = true }) {
                    Text(
                        text = "...",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
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
                when (ChatDisplayPolicy.rowKind(message)) {
                    ChatMessageRowKind.CENTERED_NOTICE -> RecalledMessageNotice(
                        text = ChatDisplayPolicy.recalledMessageText(message, viewModel.currentUserId)
                    )
                    ChatMessageRowKind.BUBBLE -> ChatMessageRow(
                        message = message,
                        peerName = state.peerName,
                        peerAvatarUrl = state.peerAvatarUrl,
                        currentUserAvatarUrl = state.currentUserAvatarUrl,
                        currentUserId = viewModel.currentUserId,
                        senderProfile = state.senderProfiles[message.senderId],
                        peerReadUpToServerSeq = state.peerReadUpToServerSeq,
                        mentionMembers = state.mentionMembers,
                        showActions = activeActionMessageId == message.messageId,
                        onOpenImagePreview = { previewMessage = it },
                        onOpenActions = { activeActionMessageId = message.messageId },
                        onDismissActions = { activeActionMessageId = null },
                        onRetryImage = { message ->
                            scope.launch {
                                viewModel.retryImageMessage(message.messageId)
                            }
                        },
                        onCopyText = { text ->
                            clipboard.setText(AnnotatedString(text))
                        },
                        onRecall = { message ->
                            scope.launch {
                                viewModel.recallMessage(message.messageId)
                            }
                        }
                    )
                }
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
            onDraftChange = {
                draft = it
                selectedMentions = ChatMentionPolicy.activeMentions(it.text, selectedMentions)
            },
            isGroup = state.peerId.startsWith("group:"),
            mentionMembers = state.mentionMembers.filter { it.userId != viewModel.currentUserId },
            onMentionSelected = { member ->
                val result = ChatMentionPolicy.insertMention(draft.text, selectedMentions, member)
                draft = TextFieldValue(
                    text = result.draft,
                    selection = TextRange(result.cursorPosition)
                )
                selectedMentions = result.selectedMentions
            },
            canSend = ChatDisplayPolicy.shouldShowSendButton(draft.text) && state.peerId.isNotBlank(),
            onPickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onSend = {
                val content = draft.text
                val mentionIds = ChatMentionPolicy.activeMentionIds(content, selectedMentions)
                draft = TextFieldValue("")
                selectedMentions = emptyList()
                scope.launch {
                    viewModel.sendText(content, mentionedUserIds = mentionIds)
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
    if (showGroupRename) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showGroupRename = false },
            title = { Text("修改群名称") },
            text = {
                TextField(
                    value = groupNameDraft,
                    onValueChange = { groupNameDraft = it },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nextName = groupNameDraft
                        showGroupRename = false
                        scope.launch {
                            viewModel.renameGroup(nextName)
                        }
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                Button(onClick = { showGroupRename = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun RecalledMessageNotice(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatComposerBar(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    isGroup: Boolean,
    mentionMembers: List<GroupMember>,
    onMentionSelected: (GroupMember) -> Unit,
    canSend: Boolean,
    onPickImage: () -> Unit,
    onSend: () -> Unit
) {
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    val inputShape = RoundedCornerShape(18.dp)
    val action = ChatDisplayPolicy.composerAction(draft.text)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        if (ChatMentionPolicy.shouldShowPicker(draft.text, isGroup) && mentionMembers.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                mentionMembers.forEach { member ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .clickable { onMentionSelected(member) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarImage(
                            avatarUrl = member.avatarUrl,
                            displayName = member.displayName,
                            modifier = Modifier.size(36.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = member.displayName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "ID: ${member.userId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
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
    currentUserId: String,
    senderProfile: com.codex.im.storage.UserProfile?,
    peerReadUpToServerSeq: Long?,
    mentionMembers: List<GroupMember>,
    showActions: Boolean,
    onOpenImagePreview: (ChatMessage) -> Unit,
    onOpenActions: () -> Unit,
    onDismissActions: () -> Unit,
    onRetryImage: (ChatMessage) -> Unit,
    onCopyText: (String) -> Unit,
    onRecall: (ChatMessage) -> Unit
) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val avatar = ChatDisplayPolicy.bubbleAvatar(
        message = message,
        groupTitle = peerName,
        peerName = peerName,
        peerAvatarUrl = peerAvatarUrl,
        currentUserAvatarUrl = currentUserAvatarUrl,
        currentUserId = currentUserId,
        senderProfile = senderProfile
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!outgoing) {
            AvatarImage(
                avatarUrl = avatar.avatarUrl,
                displayName = avatar.displayName,
                modifier = Modifier.size(36.dp)
            )
        }
        ChatMessageContent(
            message = message,
            currentUserId = currentUserId,
            outgoing = outgoing,
            peerReadUpToServerSeq = peerReadUpToServerSeq,
            mentionMembers = mentionMembers,
            showActions = showActions,
            onOpenImagePreview = onOpenImagePreview,
            onOpenActions = onOpenActions,
            onDismissActions = onDismissActions,
            onRetryImage = onRetryImage,
            onCopyText = onCopyText,
            onRecall = onRecall
        )
        if (outgoing) {
            AvatarImage(
                avatarUrl = avatar.avatarUrl,
                displayName = avatar.displayName,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatMessageContent(
    message: ChatMessage,
    currentUserId: String,
    outgoing: Boolean,
    peerReadUpToServerSeq: Long?,
    mentionMembers: List<GroupMember>,
    showActions: Boolean,
    onOpenImagePreview: (ChatMessage) -> Unit,
    onOpenActions: () -> Unit,
    onDismissActions: () -> Unit,
    onRetryImage: (ChatMessage) -> Unit,
    onCopyText: (String) -> Unit,
    onRecall: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val actions = ChatDisplayPolicy.messageActions(message, currentUserId, now = System.currentTimeMillis())
    Column(
        modifier = modifier,
        horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start
    ) {
        if (showActions && actions.isNotEmpty()) {
            ChatMessageActionBar(
                actions = actions,
                onCopy = {
                    onDismissActions()
                    onCopyText(message.content)
                },
                onRecall = {
                    onDismissActions()
                    onRecall(message)
                },
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        ChatBubbleLine(
            message = message,
            outgoing = outgoing,
            peerReadUpToServerSeq = peerReadUpToServerSeq,
            mentionMembers = mentionMembers,
            onOpenImagePreview = onOpenImagePreview,
            onRetryImage = onRetryImage,
            onLongPressImage = onOpenActions,
            onLongPressText = onOpenActions
        )
    }
}

@Composable
private fun ChatBubbleLine(
    message: ChatMessage,
    outgoing: Boolean,
    peerReadUpToServerSeq: Long?,
    mentionMembers: List<GroupMember>,
    onOpenImagePreview: (ChatMessage) -> Unit,
    onRetryImage: (ChatMessage) -> Unit,
    onLongPressImage: () -> Unit,
    onLongPressText: () -> Unit
) {
    Row(
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (outgoing) {
            OutgoingMessageStatus(
                message = message,
                peerReadUpToServerSeq = peerReadUpToServerSeq,
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
                onOpenPreview = onOpenImagePreview,
                onLongPress = onLongPressImage
            )
        } else {
            ChatTextBubble(
                message = message,
                outgoing = outgoing,
                mentionMembers = mentionMembers,
                onLongPress = onLongPressText,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ChatTextBubble(
    message: ChatMessage,
    outgoing: Boolean,
    mentionMembers: List<GroupMember>,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bubbleColor = if (outgoing) {
        Color(0xFFD8F5D0)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Box(modifier = modifier) {
        Text(
            text = message.mentionText(mentionMembers),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .background(bubbleColor, RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ChatMessage.mentionText(mentionMembers: List<GroupMember>): AnnotatedString {
    if (mentionedUserIds.isEmpty()) {
        return AnnotatedString(content)
    }
    val mentions = ChatMentionPolicy.mentionsForMessage(mentionedUserIds, mentionMembers)
    val displayText = ChatMentionPolicy.displayText(content, mentions)
    val ranges = displayText.highlightRanges
    if (ranges.isEmpty()) {
        return AnnotatedString(displayText.text)
    }
    val highlightColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var cursor = 0
        ranges.forEach { range ->
            if (range.first > cursor) {
                append(displayText.text.substring(cursor, range.first))
            }
            withStyle(SpanStyle(color = highlightColor)) {
                append(displayText.text.substring(range.first, range.last + 1))
            }
            cursor = range.last + 1
        }
        if (cursor < displayText.text.length) {
            append(displayText.text.substring(cursor))
        }
    }
}

@Composable
private fun ChatMessageActionBar(
    actions: List<ChatMessageAction>,
    onCopy: () -> Unit,
    onRecall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.inverseSurface, RoundedCornerShape(8.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            Text(
                text = when (action) {
                    ChatMessageAction.COPY -> "复制"
                    ChatMessageAction.RECALL -> "撤回"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .widthIn(min = 48.dp)
                    .clickable(
                        onClick = when (action) {
                            ChatMessageAction.COPY -> onCopy
                            ChatMessageAction.RECALL -> onRecall
                        }
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun OutgoingMessageStatus(
    message: ChatMessage,
    peerReadUpToServerSeq: Long?,
    status: MessageStatus,
    onRetry: () -> Unit = {}
) {
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
            MessageStatus.RECEIVED -> Text(
                text = if (message.serverSeq != null && peerReadUpToServerSeq != null && message.serverSeq <= peerReadUpToServerSeq) "✓" else "○",
                style = MaterialTheme.typography.labelMedium,
                color = if (message.serverSeq != null && peerReadUpToServerSeq != null && message.serverSeq <= peerReadUpToServerSeq) Color(0xFF20A464) else MaterialTheme.colorScheme.outline
            )
        }
    }
}
