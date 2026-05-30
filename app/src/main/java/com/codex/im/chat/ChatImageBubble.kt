package com.codex.im.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.codex.im.storage.ChatMessage
import com.codex.im.storage.MessageStatus

@Composable
fun ChatImageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenPreview: (ChatMessage) -> Unit = {}
) {
    val bubbleShape = RoundedCornerShape(18.dp)
    val model = message.localThumbnailPath ?: message.thumbnailUrl
    val bubbleSize = ChatImageBubbleLayoutPolicy.displaySize(
        imageWidth = message.imageWidth,
        imageHeight = message.imageHeight
    )
    Box(
        modifier = modifier
            .size(width = bubbleSize.widthDp.dp, height = bubbleSize.heightDp.dp)
            .clip(bubbleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(
                enabled = message.localOriginalPath != null || message.imageUrl != null
            ) {
                onOpenPreview(message)
            },
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            SubcomposeAsyncImage(
                model = model,
                contentDescription = message.content,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    CircularProgressIndicator()
                },
                error = {
                    Text(
                        text = "Load failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            )
        } else {
            Text(
                text = "[图片]",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (message.status) {
            MessageStatus.UPLOADING,
            MessageStatus.SENDING -> CircularProgressIndicator()
            MessageStatus.UPLOAD_FAILED -> Text(
                text = "Upload failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            MessageStatus.FAILED -> Text(
                text = "Send failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            MessageStatus.SENT,
            MessageStatus.RECEIVED -> Unit
        }
    }
}
