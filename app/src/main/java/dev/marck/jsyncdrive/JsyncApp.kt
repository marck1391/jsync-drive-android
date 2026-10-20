package dev.marck.jsyncdrive

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class JsyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CONNECTION, getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REQUESTS, getString(R.string.channel_requests), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFERS, getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_TRANSFERS = "transfers"
        const val CHANNEL_REQUESTS = "requests"
    }
}
