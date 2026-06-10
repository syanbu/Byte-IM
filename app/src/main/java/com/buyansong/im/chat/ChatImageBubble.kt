package com.buyansong.im.chat

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.compose.SubcomposeAsyncImage
import com.buyansong.im.storage.ChatMessage
import com.buyansong.im.storage.MessageStatus
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImShapes

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatImageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenPreview: (ChatMessage) -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val bubbleShape = ByteImShapes.BubbleLarge
    val context = LocalContext.current
    val localThumbnailRequest = message.localThumbnailPath?.let { path ->
        ChatLocalThumbnailRequest.build(context, path)
    }
    val model = localThumbnailRequest ?: message.thumbnailUrl
    val bubbleSize = ChatImageBubbleLayoutPolicy.displaySize(
        imageWidth = message.imageWidth,
        imageHeight = message.imageHeight
    )
    // Self-managed preload: warm Coil's memory cache for this bubble's
    // local thumbnail before SubcomposeAsyncImage triggers the same decode.
    // Only local files are preloaded; never fetched from network here, since
    // visible bubbles always have localThumbnailPath set under the strict
    // receiver-side caching policy.
    LaunchedEffect(message.localThumbnailPath) {
        val path = message.localThumbnailPath ?: return@LaunchedEffect
        val request = ChatLocalThumbnailRequest.build(context, path) ?: return@LaunchedEffect
        Coil.imageLoader(context).execute(request)
    }
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
                        text = "加载失败",
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
                text = "上传失败",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.45f), ByteImShapes.Notice)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
            MessageStatus.FAILED -> Text(
                text = "发送失败",
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
