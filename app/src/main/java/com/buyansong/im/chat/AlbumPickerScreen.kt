package com.buyansong.im.chat

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.buyansong.im.ui.ByteImColors
import com.buyansong.im.ui.ByteImDimensions
import com.buyansong.im.ui.ByteImTopBar

@Composable
fun AlbumPickerScreen(
    viewModel: AlbumPickerViewModel,
    state: AlbumPickerUiState,
    onBack: () -> Unit,
    onSendSelected: (List<AlbumImageItem>) -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(viewModel) {
        viewModel.start()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ByteImColors.AppBackground)
    ) {
        ByteImTopBar(
            title = "选择图片",
            onBack = onBack,
            centerTitle = true,
            actions = listOf {
                Button(
                    enabled = state.selected.isNotEmpty(),
                    onClick = { onSendSelected(state.selected) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ByteImColors.PrimaryGreen,
                        contentColor = Color.White
                    )
                ) {
                    Text("发送 ${state.selected.size}")
                }
            }
        )
        when {
            state.isLoading -> AlbumStatusText("正在读取相册")
            state.errorMessage != null -> AlbumStatusText(state.errorMessage)
            state.images.isEmpty() -> AlbumStatusText("暂无图片")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(state.images, key = { it.uriString }) { image ->
                    AlbumGridItem(
                        image = image,
                        onClick = { viewModel.toggleSelection(image) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumGridItem(
    image: AlbumImageItem,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(ByteImColors.Divider)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = Uri.parse(image.uriString),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        SelectionBadge(
            order = image.selectionOrder,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
        )
    }
}

@Composable
private fun SelectionBadge(
    order: Int?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (order == null) Color.Black.copy(alpha = 0.35f) else ByteImColors.PrimaryGreen)
            .border(1.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (order != null) {
            Text(
                text = (order + 1).toString(),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AlbumStatusText(text: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text.orEmpty(),
            color = ByteImColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ByteImDimensions.EdgePadding),
            textAlign = TextAlign.Center
        )
    }
}
