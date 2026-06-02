package com.codex.im.ui

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/**
 * 共用的"+"下拉菜单：发起群聊 + 添加朋友。
 * 消息页和联系人页顶栏的 + 按钮复用同一份菜单，确保两个入口完全一致。
 */
@Composable
fun ConversationCreateMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onStartGroupChat: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(text = "发起群聊") },
            onClick = {
                onDismiss()
                onStartGroupChat()
            }
        )
        DropdownMenuItem(
            text = { Text(text = "添加朋友") },
            onClick = onDismiss
        )
    }
}
