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
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import com.codex.im.storage.ChatMessage

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
            .background(MaterialTheme.colorScheme.scrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = message.content,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = {
                CircularProgressIndicator()
            },
            error = {
                Text(
                    text = "Image load failed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        )
    }
}
