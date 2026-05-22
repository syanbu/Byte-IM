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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    modifier: Modifier = Modifier
) {
    var peerId by remember(state.peerId) { mutableStateOf(state.peerId) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Logged in as ${session.username}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = peerId,
            onValueChange = {
                peerId = it
                viewModel.selectPeer(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Peer userId") },
            singleLine = true
        )
        LazyColumn(
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
                enabled = draft.isNotBlank() && peerId.isNotBlank(),
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
