package com.buyansong.im.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import coil.Coil
import com.buyansong.im.R
import com.buyansong.im.message.ChatImageCompressor
import com.buyansong.im.message.SelectedChatImage
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.ConversationType
import com.buyansong.im.storage.GroupMember
import com.buyansong.im.storage.MessageDirection
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.storage.MessageType
import com.buyansong.im.ui.AvatarImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImSystemNotice
import com.buyansong.im.ui.ByteImTopBar
import com.buyansong.im.ui.byteImBubbleColor
import com.buyansong.im.ui.byteImBubbleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenUserProfile: (String) -> Unit = {},
    onOpenGroupInfo: () -> Unit = {}
) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var previewMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var activeActionMessageId by remember { mutableStateOf<String?>(null) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showGroupReadSheet by remember { mutableStateOf(false) }
    var albumPermissionDenied by remember { mutableStateOf(false) }
    var albumSessionId by remember { mutableStateOf(0) }
    var selectedMentions by remember { mutableStateOf(emptyList<ChatMention>()) }
    var showMentionPicker by remember { mutableStateOf(false) }
    val mentionFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val isGroupChat = state.peerId.startsWith("group:")
    val mentionableMembers = state.mentionMembers.filter { it.userId != viewModel.currentUserId }

    fun shouldOpenMentionPicker(text: String): Boolean {
        return ChatMentionPolicy.shouldShowPicker(
            draft = text,
            isGroup = isGroupChat
        ) && mentionableMembers.isNotEmpty()
    }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun handleMemberSelected(member: GroupMember) {
        val result = ChatMentionPolicy.insertMention(draft.text, selectedMentions, member)
        draft = TextFieldValue(
            text = result.draft,
            selection = TextRange(result.cursorPosition)
        )
        selectedMentions = result.selectedMentions
        showMentionPicker = false
        mentionFocusRequester.requestFocus()
        keyboardController?.show()
    }

    val listState = rememberLazyListState()
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    var previousImeBottomPx by remember { mutableStateOf(imeBottomPx) }
    var moreActionsPanelHeightPx by remember { mutableStateOf(0) }
    val lastVisibleMessageIndex by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
        }
    }
    var lastVisibleIndexBeforeImeExpansion by remember { mutableStateOf(lastVisibleMessageIndex) }
    var lastVisibleIndexBeforeMoreActionsExpansion by remember { mutableStateOf(lastVisibleMessageIndex) }
    var didScrollForMoreActionsExpansion by remember { mutableStateOf(false) }
    val albumViewModel = remember(albumSessionId) {
        AlbumPickerViewModel(AndroidAlbumImageRepository(context.contentResolver))
    }
    val albumState by albumViewModel.state.collectAsState()
    val albumPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }
    val albumPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            albumPermissionDenied = false
            albumSessionId += 1
            showAlbumPicker = true
        } else {
            albumPermissionDenied = true
        }
    }
    var previousLatestMessageId by remember { mutableStateOf<String?>(null) }
    val latestMessageId = state.messages.lastOrNull()?.messageId
    val latestMessageIndex = ChatAutoScrollPolicy.scrollToLatestIndex(state.messages.size)
    val autoScrollAction = ChatAutoScrollPolicy.scrollAction(previousLatestMessageId, latestMessageId)
    SideEffect {
        if (autoScrollAction == ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST) {
            listState.requestScrollToItem(latestMessageIndex)
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

    LaunchedEffect(latestMessageId, state.messages.size) {
        when (autoScrollAction) {
            ChatAutoScrollPolicy.ScrollAction.NONE -> Unit
            ChatAutoScrollPolicy.ScrollAction.PRE_MEASURE_ANCHOR_TO_LATEST -> Unit
            ChatAutoScrollPolicy.ScrollAction.ANIMATE_TO_LATEST -> {
                listState.animateScrollToItem(latestMessageIndex)
            }
        }
        previousLatestMessageId = latestMessageId
    }

    LaunchedEffect(imeBottomPx, lastVisibleMessageIndex) {
        if (imeBottomPx == 0) {
            lastVisibleIndexBeforeImeExpansion = lastVisibleMessageIndex
        }
    }

    LaunchedEffect(imeBottomPx, state.messages.size) {
        val imeScrollDeltaPx = ChatAutoScrollPolicy.imeExpansionScrollDeltaPx(
            previousImeBottomPx = previousImeBottomPx,
            currentImeBottomPx = imeBottomPx,
            messageCount = state.messages.size,
            lastVisibleIndexBeforeImeChange = lastVisibleIndexBeforeImeExpansion
        )
        if (imeScrollDeltaPx > 0) {
            listState.animateScrollBy(imeScrollDeltaPx.toFloat())
        }
        previousImeBottomPx = imeBottomPx
    }

    LaunchedEffect(showMoreActions, lastVisibleMessageIndex) {
        if (!showMoreActions) {
            lastVisibleIndexBeforeMoreActionsExpansion = lastVisibleMessageIndex
        }
    }

    LaunchedEffect(showMoreActions, moreActionsPanelHeightPx, state.messages.size) {
        if (!showMoreActions) {
            didScrollForMoreActionsExpansion = false
            return@LaunchedEffect
        }
        if (didScrollForMoreActionsExpansion) return@LaunchedEffect
        if (moreActionsPanelHeightPx <= 0) return@LaunchedEffect

        val moreActionsScrollDeltaPx = ChatAutoScrollPolicy.moreActionsExpansionScrollDeltaPx(
            panelHeightPx = moreActionsPanelHeightPx,
            messageCount = state.messages.size,
            lastVisibleIndexBeforeExpansion = lastVisibleIndexBeforeMoreActionsExpansion
        )
        if (moreActionsScrollDeltaPx > 0) {
            listState.animateScrollBy(moreActionsScrollDeltaPx.toFloat())
        }
        didScrollForMoreActionsExpansion = true
    }

    LaunchedEffect(shouldLoadEarlierHistory) {
        if (shouldLoadEarlierHistory) {
            viewModel.loadMoreHistory()
        }
    }

    LaunchedEffect(draft.text, isGroupChat, mentionableMembers) {
        showMentionPicker = shouldOpenMentionPicker(draft.text)
    }

    LaunchedEffect(showMentionPicker) {
        if (showMentionPicker) {
            keyboardController?.hide()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
            .imePadding()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                onClick = {
                    activeActionMessageId = null
                    showMoreActions = false
                }
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            ByteImTopBar(
                title = state.peerName,
                onBack = onBack,
                centerTitle = true,
                actions = if (state.peerId.startsWith("group:")) {
                    listOf {
                        IconButton(onClick = onOpenGroupInfo) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_horiz),
                                contentDescription = "群聊信息",
                                tint = ByteImColors.TextPrimary
                            )
                        }
                    }
                } else {
                    emptyList()
                }
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(ByteImColors.AppBackground)
                    .padding(horizontal = ByteImDimensions.EdgePadding),
                reverseLayout = false
            ) {
                if (state.messages.isNotEmpty()) {
                    item(key = "history-loader") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 72.dp)
                                .padding(top = 16.dp, bottom = 18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ChatHistoryTopTime(
                                text = ChatDisplayPolicy.topTimelineTimeText(state.messages.first().createdAt)
                            )
                            ChatDisplayPolicy.historyStatusText(state)?.let { statusText ->
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ByteImColors.TextSecondary
                                )
                            }
                        }
                    }
                }
                itemsIndexed(state.messages, key = { _, msg -> msg.messageId }) { index, message ->
                    when (ChatDisplayPolicy.rowKind(message)) {
                        ChatMessageRowKind.CENTERED_NOTICE -> RecalledMessageNotice(
                            text = ChatDisplayPolicy.recalledMessageText(
                                message = message,
                                currentUserId = viewModel.currentUserId,
                                senderDisplayName = recalledSenderDisplayName(message, state)
                            )
                        )
                        ChatMessageRowKind.BUBBLE -> Column(modifier = Modifier.fillMaxWidth()) {
                            val prevMessage = state.messages.getOrNull(index - 1)
                            if (ChatDisplayPolicy.shouldShowTimeSeparator(prevMessage, message)) {
                                ChatMessageTimeSeparator(
                                    text = ChatDisplayPolicy.timeSeparatorText(message.createdAt)
                                )
                            }
                            ChatMessageRow(
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
                                },
                                onOpenUserProfile = onOpenUserProfile
                            )
                            if (
                                state.peerId.startsWith("group:") &&
                                message.messageId == state.latestOwnSentMessageId &&
                                state.groupReadCountForLatest > 0
                            ) {
                                GroupReadIndicator(
                                    count = state.groupReadCountForLatest,
                                    onClick = { showGroupReadSheet = true }
                                )
                            }
                        }
                    }
                }
            }
            if (albumPermissionDenied) {
                Text(
                    text = "需要相册权限才能选择图片",
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
                    showMentionPicker = shouldOpenMentionPicker(it.text)
                },
                canSend = ChatDisplayPolicy.shouldShowSendButton(draft.text) && state.peerId.isNotBlank(),
                showMoreActions = showMoreActions,
                onMoreActionsClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    showMoreActions = !showMoreActions
                },
                onDismissMoreActions = { showMoreActions = false },
                onMoreActionsPanelHeightChanged = { moreActionsPanelHeightPx = it },
                onPickMoreActionImage = {
                    showMoreActions = false
                    if (ContextCompat.checkSelfPermission(context, albumPermission) == PackageManager.PERMISSION_GRANTED) {
                        albumPermissionDenied = false
                        albumSessionId += 1
                        showAlbumPicker = true
                    } else {
                        albumPermissionLauncher.launch(albumPermission)
                    }
                },
                onSend = {
                    val content = draft.text
                    val mentionIds = ChatMentionPolicy.activeMentionIds(content, selectedMentions)
                    draft = TextFieldValue("")
                    selectedMentions = emptyList()
                    showMoreActions = false
                    scope.launch {
                        viewModel.sendText(content, mentionedUserIds = mentionIds)
                    }
                },
                mentionFocusRequester = mentionFocusRequester
            )
        }
        state.errorMessage?.let { message ->
            LaunchedEffect(message) {
                delay(CHAT_ERROR_TOAST_DURATION_MS)
                viewModel.clearErrorMessage()
            }
            ChatBottomToast(
                text = message,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
    // Back handling for in-screen overlays. These overlays live inside
    // ChatScreen (not in the NavHost), so without an explicit BackHandler
    // the system back press would pop the entire Chat destination back to
    // the conversations list, skipping the overlay. Later enabled handlers
    // take priority, so the mention sheet handler is declared last.
    BackHandler(enabled = showAlbumPicker) {
        showAlbumPicker = false
    }
    BackHandler(enabled = showGroupReadSheet) {
        showGroupReadSheet = false
    }
    BackHandler(enabled = previewMessage != null) {
        previewMessage = null
    }
    BackHandler(enabled = showMentionPicker) {
        showMentionPicker = false
    }
    previewMessage?.let { message ->
        ChatImagePreviewScreen(
            message = message,
            onDismiss = { previewMessage = null }
        )
    }
    if (showAlbumPicker) {
        AlbumPickerScreen(
            viewModel = albumViewModel,
            state = albumState,
            onBack = {
                showAlbumPicker = false
            },
            onSendSelected = { selected ->
                showAlbumPicker = false
                scope.launch {
                    val preparedImages = selected.mapIndexedNotNull { index, image ->
                        ChatImageCompressor.prepareSelectedImage(
                            context,
                            context.contentResolver,
                            Uri.parse(image.uriString)
                        )?.copy(selectionOrder = index)
                    }
                    if (preparedImages.isNotEmpty()) {
                        prewarmOutgoingLocalThumbnails(context, preparedImages)
                        viewModel.sendImages(preparedImages)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
    if (showGroupReadSheet) {
        GroupReadDetailSheet(
            readers = state.groupReadersForLatest,
            onDismiss = { showGroupReadSheet = false }
        )
    }
    if (showMentionPicker) {
        MentionPickerSheet(
            members = mentionableMembers,
            onMemberSelected = { member -> handleMemberSelected(member) },
            onDismiss = { showMentionPicker = false }
        )
    }
}

private suspend fun prewarmOutgoingLocalThumbnails(
    context: Context,
    images: List<SelectedChatImage>
) {
    withContext(Dispatchers.IO) {
        images.forEach { image ->
            val request = ChatLocalThumbnailRequest.build(context, image.localThumbnailPath) ?: return@forEach
            Coil.imageLoader(context).execute(request)
        }
    }
}

private fun recalledSenderDisplayName(message: ChatMessage, state: ChatUiState): String? {
    return state.senderProfiles[message.senderId]
        ?.nickname
        ?.takeIf { it.isNotBlank() }
        ?: state.mentionMembers
            .firstOrNull { it.userId == message.senderId }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
}

@Composable
private fun RecalledMessageNotice(text: String) {
    ByteImSystemNotice(text = text, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun ChatComposerBar(
    draft: TextFieldValue,
    onDraftChange: (TextFieldValue) -> Unit,
    canSend: Boolean,
    onSend: () -> Unit,
    showMoreActions: Boolean,
    onMoreActionsClick: () -> Unit,
    onDismissMoreActions: () -> Unit,
    onMoreActionsPanelHeightChanged: (Int) -> Unit,
    onPickMoreActionImage: () -> Unit,
    mentionFocusRequester: androidx.compose.ui.focus.FocusRequester,
    onEmojiClick: () -> Unit = {}
) {
    val barColor = ByteImColors.Surface
    val inputShape = RoundedCornerShape(18.dp)
    val hasText = draft.text.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(barColor)
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
                    .background(ByteImColors.AppBackground, inputShape)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        shape = inputShape
                    )
            ) {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(mentionFocusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                onDismissMoreActions()
                            }
                        },
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
            if (hasText) {
                // 有内容时，绿色"发送"按钮占据表情/加号的位置
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ByteImColors.PrimaryGreen,
                        contentColor = Color.White
                    ),
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text("发送")
                }
            } else {
                // 空草稿：表情占位 + 加号（复用顶部"更多"同款矢量图）
                IconButton(
                    onClick = onEmojiClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_emoji),
                        contentDescription = "表情",
                        tint = ByteImColors.TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(
                    onClick = onMoreActionsClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_add_circle),
                        contentDescription = "更多操作",
                        tint = ByteImColors.TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
        if (showMoreActions && !hasText) {
            ChatMoreActionsPanel(
                modifier = Modifier.onSizeChanged { onMoreActionsPanelHeightChanged(it.height) },
                onPickImage = {
                    onDismissMoreActions()
                    onPickMoreActionImage()
                }
            )
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
    senderProfile: com.buyansong.im.storage.UserProfile?,
    peerReadUpToServerSeq: Long?,
    mentionMembers: List<GroupMember>,
    showActions: Boolean,
    onOpenImagePreview: (ChatMessage) -> Unit,
    onOpenActions: () -> Unit,
    onDismissActions: () -> Unit,
    onRetryImage: (ChatMessage) -> Unit,
    onCopyText: (String) -> Unit,
    onRecall: (ChatMessage) -> Unit,
    onOpenUserProfile: (String) -> Unit
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
    val avatarUserId = ChatDisplayPolicy.bubbleAvatarUserId(message, currentUserId)
    // Use Bottom alignment so the avatar stays glued to the bubble line.
    // When the long-press action bar (复制 / 撤回) appears above the bubble,
    // Top alignment would pull the avatar upward to match the action bar's top edge,
    // breaking the avatar-bubble row layout. Bottom keeps the avatar on the
    // same horizontal line as the bubble regardless of the action bar's height.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!outgoing) {
            AvatarImage(
                avatarUrl = avatar.avatarUrl,
                displayName = avatar.displayName,
                modifier = Modifier
                    .size(ByteImDimensions.ChatAvatarSize)
                    .clickable(enabled = avatarUserId != null) {
                        avatarUserId?.let(onOpenUserProfile)
                    }
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
            onRecall = onRecall,
            modifier = Modifier.padding(horizontal = ByteImDimensions.Gutter)
        )
        if (outgoing) {
            AvatarImage(
                avatarUrl = avatar.avatarUrl,
                displayName = avatar.displayName,
                modifier = Modifier
                    .size(ByteImDimensions.ChatAvatarSize)
                    .clickable(enabled = avatarUserId != null) {
                        avatarUserId?.let(onOpenUserProfile)
                    }
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
    val hasActions = showActions && actions.isNotEmpty()
    val actionPopupOffsetY = with(LocalDensity.current) { -56.dp.roundToPx() }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val maxBubbleWidth = ChatTextBubbleLayoutPolicy.maxBubbleWidth(maxWidth.value.roundToInt()).dp
        ChatBubbleLine(
            message = message,
            outgoing = outgoing,
            peerReadUpToServerSeq = peerReadUpToServerSeq,
            mentionMembers = mentionMembers,
            maxBubbleWidth = maxBubbleWidth,
            onOpenImagePreview = onOpenImagePreview,
            onRetryImage = onRetryImage,
            onLongPressImage = onOpenActions,
            onLongPressText = onOpenActions
        )
        if (hasActions) {
            Popup(
                alignment = if (outgoing) Alignment.TopEnd else Alignment.TopStart,
                offset = IntOffset(x = 0, y = actionPopupOffsetY),
                properties = PopupProperties(focusable = false)
            ) {
                ChatMessageActionBar(
                    actions = actions,
                    onCopy = {
                        onDismissActions()
                        onCopyText(message.content)
                    },
                    onRecall = {
                        onDismissActions()
                        onRecall(message)
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatHistoryTopTime(
    text: String
) {
    ChatMessageTimeSeparator(text = text)
}

@Composable
private fun ChatMessageTimeSeparator(
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = ByteImColors.TextSecondary
        )
    }
}

@Composable
private fun ChatBubbleLine(
    message: ChatMessage,
    outgoing: Boolean,
    peerReadUpToServerSeq: Long?,
    mentionMembers: List<GroupMember>,
    maxBubbleWidth: Dp,
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
                showReadMarker = message.conversationType == ConversationType.SINGLE,
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
                maxBubbleWidth = maxBubbleWidth,
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
    maxBubbleWidth: Dp,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Text(
            text = message.mentionText(mentionMembers),
            style = MaterialTheme.typography.bodyLarge,
            color = ByteImColors.TextPrimary,
            modifier = Modifier
                .widthIn(max = maxBubbleWidth)
                .background(byteImBubbleColor(outgoing), byteImBubbleShape(outgoing))
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
                .padding(
                    horizontal = ByteImDimensions.BubbleHorizontalPadding,
                    vertical = ByteImDimensions.BubbleVerticalPadding
                )
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
    val highlightColor = ByteImColors.PrimaryGreen
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
private fun ChatBottomToast(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(start = 24.dp, end = 24.dp, bottom = 84.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Color(0xFFE0E0E0),
            shape = RoundedCornerShape(18.dp),
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF222222),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
            )
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
            .background(ByteImColors.InverseSurface, RoundedCornerShape(8.dp))
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

private const val CHAT_ERROR_TOAST_DURATION_MS = 2_000L

@Composable
private fun OutgoingMessageStatus(
    message: ChatMessage,
    peerReadUpToServerSeq: Long?,
    showReadMarker: Boolean,
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
                color = ByteImColors.BadgeRed,
                modifier = Modifier.clickable(onClick = onRetry)
            )
            MessageStatus.SENT,
            MessageStatus.RECEIVED -> {
                if (!showReadMarker) return@Box
                val isRead = message.serverSeq != null &&
                    peerReadUpToServerSeq != null &&
                    message.serverSeq <= peerReadUpToServerSeq
                Icon(
                    painter = painterResource(id = R.drawable.ic_msg_check),
                    contentDescription = if (isRead) "已读" else "已送达",
                    tint = if (isRead) ByteImColors.PrimaryGreen else ByteImColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
