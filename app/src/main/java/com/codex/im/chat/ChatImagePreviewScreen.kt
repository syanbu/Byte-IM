package com.codex.im.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import com.codex.im.storage.ChatMessage
import com.codex.im.ui.ByteImColors

@Composable
fun ChatImagePreviewScreen(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit
) {
    val model = message.localOriginalPath ?: message.imageUrl ?: message.localThumbnailPath ?: message.thumbnailUrl
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = message.content,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = {
                CircularProgressIndicator(color = ByteImColors.PrimaryGreen)
            },
            error = {
                Text(
                    text = "图片加载失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White
                )
            }
        )
    }
}
