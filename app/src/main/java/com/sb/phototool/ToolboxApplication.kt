package com.sb.phototool

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sb.phototool.data.DiagnosticLog

class ToolboxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.install(this)
        val channel = NotificationChannel(
            WORK_CHANNEL_ID,
            getString(R.string.work_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.work_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val WORK_CHANNEL_ID = "media_processing"
    }
}
