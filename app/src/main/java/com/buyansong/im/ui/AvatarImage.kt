package com.buyansong.im.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

@Composable
fun AvatarImage(
    avatarUrl: String?,
    displayName: String,
    isGroup: Boolean = false,
    modifier: Modifier = Modifier.size(48.dp)
) {
    val context = LocalContext.current
    val imageCache = remember(context) { AvatarImageCache.default(context) }
    val normalizedUrl = avatarUrl?.trim()?.takeIf { it.isNotEmpty() }
    var bitmap by remember(normalizedUrl) {
        mutableStateOf(normalizedUrl?.let { AvatarBitmapMemoryCache.get(it) })
    }

    LaunchedEffect(imageCache, normalizedUrl) {
        val url = normalizedUrl
        if (url == null) {
            bitmap = null
            return@LaunchedEffect
        }

        AvatarBitmapMemoryCache.get(url)?.let { cachedBitmap ->
            bitmap = cachedBitmap
            return@LaunchedEffect
        }

        val decodedBitmap = withContext(Dispatchers.IO) {
            imageCache.bytesFor(url)?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }
        if (decodedBitmap != null) {
            AvatarBitmapMemoryCache.put(url, decodedBitmap)
        }
        bitmap = decodedBitmap
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        } else {
            Text(
                text = AvatarPlaceholderPolicy.text(displayName, isGroup = isGroup),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private object AvatarBitmapMemoryCache {
    private const val MaxEntries = 128
    private val bitmaps = object : LinkedHashMap<String, ImageBitmap>(MaxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MaxEntries
        }
    }

    @Synchronized
    fun get(url: String): ImageBitmap? = bitmaps[url]

    @Synchronized
    fun put(url: String, bitmap: ImageBitmap) {
        bitmaps[url] = bitmap
    }
}
