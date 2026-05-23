package com.codex.im.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.im.auth.AuthSession
import com.codex.im.storage.MessageDirection
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    session: AuthSession,
    viewModel: ChatViewModel,
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var previousLatestMessageId by remember { mutableStateOf<String?>(null) }
    val latestMessageId = state.messages.firstOrNull()?.messageId
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
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = "Logged in as ${session.username}", style = MaterialTheme.typography.titleMedium)
            }
            if (onBack != null) {
                Button(onClick = onBack) {
                    Text("Back")
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            reverseLayout = true
        ) {
            items(state.messages, key = { it.messageId }) { message ->
                val prefix = if (message.direction == MessageDirection.OUTGOING) "Me" else message.senderId
                Text(
                    text = "$prefix: ${message.content} [${message.status}]",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            if (state.messages.isNotEmpty()) {
                item(key = "history-loader") {
                    when {
                        state.isLoadingMore -> {
                            Text(
                                text = "Loading earlier messages...",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        state.isHistoryMemoryLimitReached -> {
                            Text(
                                text = "Loaded 2000 messages in this chat",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        !state.hasMoreLocal -> {
                            Text(
                                text = "No more local messages",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                singleLine = true
            )
            Button(
                enabled = draft.isNotBlank() && state.peerId.isNotBlank(),
                onClick = {
                    val content = draft
                    draft = ""
                    scope.launch {
                        viewModel.sendText(content)
                    }
                }
            ) {
                Text("Send")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
