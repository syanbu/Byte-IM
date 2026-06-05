package com.codex.im.alert

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.im.ui.AvatarImage
import com.codex.im.ui.ByteImColors

@Composable
fun MessageAlertHost(
    controller: MessageAlertController,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val alert by controller.currentAlert.collectAsState()
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = alert != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut()
        ) {
            alert?.let { currentAlert ->
                MessageToastPopup(
                    alert = currentAlert,
                    onClick = { controller.openCurrent(onOpenConversation) }
                )
            }
        }
    }
}

@Composable
private fun MessageToastPopup(
    alert: IncomingMessageAlert,
    onClick: () -> Unit
) {
    Surface(
        color = ByteImColors.Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 0.dp,
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = 56.dp)
            .fillMaxWidth(0.85f)
            .widthIn(min = 280.dp, max = 360.dp)
            .shadow(elevation = 6.dp, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .background(ByteImColors.Surface)
                .padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarImage(
                avatarUrl = alert.avatarUrl,
                displayName = alert.title,
                isGroup = alert.isGroup,
                modifier = Modifier.size(40.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = alert.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ByteImColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = MessageAlertPolicy.formatTime(alert.rawTimestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = ByteImColors.TextSecondary,
                        maxLines = 1
                    )
                }
                Text(
                    text = alert.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = ByteImColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
