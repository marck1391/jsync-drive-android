package dev.marck.jsyncdrive.node

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.marck.jsyncdrive.JsyncApp
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the node alive while the app is in the background, so remote changes
 * keep reaching file managers and incoming transfers can be received. The
 * notification says whether the Drive is connected.
 */
class NodeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(NodeStatus()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        val host = NodeHost.get(this)
        scope.launch(Dispatchers.IO) { runCatching { host.ensureStarted() } }
        scope.launch {
            host.status.collect { s ->
                getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification(s))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(s: NodeStatus): Notification {
        val text = when (s.state) {
            "online" -> getString(R.string.notif_connected, s.email)
            "connecting" -> getString(R.string.notif_connecting)
            "offline" -> getString(R.string.notif_offline)
            "pending" -> getString(R.string.notif_pending)
            "logged_out" -> getString(R.string.notif_logged_out)
            "needs_setup" -> getString(R.string.notif_needs_setup)
            else -> getString(R.string.notif_starting)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, JsyncApp.CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NodeService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NodeService::class.java))
        }
    }
}
