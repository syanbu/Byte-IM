package com.buyansong.im.push

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.buyansong.im.R
import com.buyansong.im.app.MockServerConfig
import com.buyansong.im.auth.AuthRepository
import com.buyansong.im.auth.OkHttpAuthApi
import com.buyansong.im.auth.SharedPreferencesTokenStore

class PushPollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Log.d(TAG, "start")
        val appContext = applicationContext
        val config = MockServerConfig.load(appContext)
        val authRepository = AuthRepository(
            authApi = OkHttpAuthApi(baseUrl = config.httpBaseUrl),
            tokenStore = SharedPreferencesTokenStore(appContext)
        )
        val session = authRepository.ensureValidSession()
        if (session == null) {
            Log.d(TAG, "skip: no valid session")
            return Result.success()
        }
        val tokenStore = MockPushTokenStore(appContext)
        tokenStore.saveLastKnownUserId(session.userId)
        val api = OkHttpPushApi(baseUrl = config.httpBaseUrl)
        val since = tokenStore.lastSeenPushId(session.userId)

        val pendingResult = api.pending(session.accessToken, since, 50)
        if (pendingResult !is PushPendingResult.Success) {
            Log.d(TAG, "pending failed: $pendingResult")
            return Result.retry()
        }
        Log.d(TAG, "pending count=${pendingResult.pending.size} latest=${pendingResult.latestPushId} since=$since")
        if (pendingResult.pending.isEmpty()) {
            tokenStore.saveLastSeenPushId(session.userId, maxOf(since, pendingResult.latestPushId))
            return Result.success()
        }

        if (canPostNotifications(appContext)) {
            pendingResult.pending.forEach { item -> postNotification(appContext, item) }
        } else {
            Log.d(TAG, "notification skipped: permission/channel disabled")
        }

        val pushIds = pendingResult.pending.map { it.pushId }
        when (api.ack(session.accessToken, pushIds)) {
            PushSimpleResult.Success -> {
                Log.d(TAG, "ack success pushIds=$pushIds")
                tokenStore.saveLastSeenPushId(session.userId, maxOf(since, pendingResult.latestPushId))
                return Result.success()
            }
            is PushSimpleResult.Failure -> {
                Log.d(TAG, "ack failed pushIds=$pushIds")
                return Result.retry()
            }
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    // 发送系统级通知
    private fun postNotification(context: Context, item: PushPendingItem) {
        val notification = NotificationCompat.Builder(context, PushNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav_message)
            .setContentTitle(item.senderId)
            .setContentText(item.preview)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 用户点击通知后，打开对应的聊天界面
            .setContentIntent(
                PushDeepLink.buildPendingIntent(
                    context = context,
                    pushId = item.pushId,
                    conversationId = item.conversationId,
                    messageId = item.messageId,
                    senderId = item.senderId
                )
            )
            .build()
        NotificationManagerCompat.from(context).notify((item.pushId and Int.MAX_VALUE.toLong()).toInt(), notification)
    }

    private companion object {
        const val TAG = "PushPollWorker"
    }
}
