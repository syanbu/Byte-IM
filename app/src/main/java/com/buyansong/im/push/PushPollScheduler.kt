package com.buyansong.im.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PushPollScheduler {
    private const val WORK_NAME_PREFIX = "push-poll-"

    fun schedule(context: Context, userId: String) {
        if (userId.isBlank()) {
            return
        }
        MockPushTokenStore(context).saveLastKnownUserId(userId)
        val request = PeriodicWorkRequestBuilder<PushPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(workName(userId), ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun scheduleLastKnown(context: Context) {
        MockPushTokenStore(context).lastKnownUserId()?.let { schedule(context, it) }
    }

    fun cancel(context: Context, userId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(userId))
    }

    private fun workName(userId: String): String = "$WORK_NAME_PREFIX$userId"
}
