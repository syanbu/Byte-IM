package com.buyansong.im.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class PushPollDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PUSH_POLL) {
            return
        }
        Log.d(TAG, "enqueue debug push poll")
        val request = OneTimeWorkRequestBuilder<PushPollWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(DEBUG_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val ACTION_RUN_PUSH_POLL = "com.buyansong.im.DEBUG_RUN_PUSH_POLL"
        private const val DEBUG_WORK_NAME = "push-poll-debug"
        private const val TAG = "PushPollDebug"
    }
}
