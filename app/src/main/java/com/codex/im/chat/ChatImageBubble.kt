package com.codex.im.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageStatus
import com.codex.im.ui.ByteImColors
import com.codex.im.ui.ByteImShapes

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatImageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenPreview: (ChatMessage) -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val bubbleShape = ByteImShapes.BubbleLarge
    val model = message.localThumbnailPath ?: message.thumbnailUrl
    val bubbleSize = ChatImageBubbleLayoutPolicy.displaySize(
        imageWidth = message.imageWidth,
        imageHeight = message.imageHeight
    )
    Box(
        modifier = modifier
            .size(width = bubbleSize.widthDp.dp, height = bubbleSize.heightDp.dp)
            .clip(bubbleShape)
            .background(Color(0xFFE5E2E1))
            .combinedClickable(
                onClick = {
                    if (message.localOriginalPath != null || message.imageUrl != null) {
                        onOpenPreview(message)
                    }
                },
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = message.content,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    if (ChatImageBubbleLoadingPolicy.showInlineProgress()) {
                        CircularProgressIndicator()
                    }
                },
                error = {
                    Text(
                        text = "Load failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
        when (message.status) {
            MessageStatus.UPLOADING,
            MessageStatus.SENDING -> {
                if (ChatImageBubbleLoadingPolicy.showBubbleStatusProgress()) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Color.Black.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = ByteImColors.PrimaryGreen)
                    }
                }
            }
            MessageStatus.UPLOAD_FAILED -> Text(
                text = "Upload failed",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), ByteImShapes.Notice)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            MessageStatus.FAILED -> Text(
                text = "Send failed",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), ByteImShapes.Notice)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            MessageStatus.SENT,
            MessageStatus.RECEIVED -> Unit
        }
    }
}
