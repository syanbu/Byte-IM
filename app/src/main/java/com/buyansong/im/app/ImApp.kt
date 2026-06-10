package com.buyansong.im.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.buyansong.im.push.PushNotifications
import com.buyansong.im.push.PushPollScheduler

class ImApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createPushNotificationChannel()
        PushPollScheduler.scheduleLastKnown(this)
    }

    private fun createPushNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            PushNotifications.CHANNEL_ID,
            "新消息提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "新消息提醒"
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
