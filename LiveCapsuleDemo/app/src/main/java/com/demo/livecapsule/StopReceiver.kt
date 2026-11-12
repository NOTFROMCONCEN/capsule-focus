package com.demo.livecapsule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TaskNotifier.ACTION_STOP) {
            TaskNotifier.cancel(context)
        }
    }
}
