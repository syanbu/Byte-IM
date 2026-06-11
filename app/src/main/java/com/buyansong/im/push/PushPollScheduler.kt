package com.buyansong.im.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// 单例对象: 负责调度和管理 PushPollWorker 的周期性执行
object PushPollScheduler {
    private const val WORK_NAME_PREFIX = "push-poll-"

    fun schedule(context: Context, userId: String) {
        if (userId.isBlank()) {
            return
        }
        MockPushTokenStore(context).saveLastKnownUserId(userId)
        // 创建一个周期任务，每 15 分钟执行一次 PushPollWorker，要求网络连接
        val request = PeriodicWorkRequestBuilder<PushPollWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        // 让WorkerManager 登记一个唯一的周期任务
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(workName(userId), ExistingPeriodicWorkPolicy.KEEP, request)
        // WorkManager 会向 Android 系统注册一个 Job
    }

    fun scheduleLastKnown(context: Context) {
        MockPushTokenStore(context).lastKnownUserId()?.let { schedule(context, it) }
    }

    fun cancel(context: Context, userId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(userId))
    }

    private fun workName(userId: String): String = "$WORK_NAME_PREFIX$userId"
}
