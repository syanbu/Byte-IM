package com.buyansong.im.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.buyansong.im.MainActivity

object PushDeepLink {
    const val EXTRA_CONVERSATION_ID = "conversation_id"
    const val EXTRA_SENDER_ID = "sender_id"
    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_PUSH_ID = "push_id"

    fun extractConversationId(intent: Intent?): String? {
        return intent?.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() }
    }

    fun buildPendingIntent(
        context: Context,
        pushId: Long,
        conversationId: String,
        messageId: String,
        senderId: String
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_SENDER_ID, senderId)
            putExtra(EXTRA_PUSH_ID, pushId)
        }
        return PendingIntent.getActivity(
            context,
            pushId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
