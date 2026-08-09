package com.buyansong.im.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.buyansong.im.push.PushNotifications
import com.buyansong.im.push.PushPollScheduler
import java.io.File

class ImApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        createPushNotificationChannel()
        PushPollScheduler.scheduleLastKnown(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "chat-image-thumbnails"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()
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
