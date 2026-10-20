package dev.marck.jsyncdrive.node

import dev.marck.jsyncdrive.R
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import dev.marck.jsyncdrive.JsyncApp
import dev.marck.jsyncdrive.Prefs
import dev.marck.jsyncdrive.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file that came from another device and was filed on this phone. [uri] is where it is now. */
data class ReceivedFile(val id: String, val name: String, val mime: String, val size: Long, val from: String, val uri: String, val at: Long)

/**
 * Device-to-device transfers as the app sees them: keeps the list the screens
 * show, raises the notifications (a request with Accept/Decline, progress, "received"),
 * accepts requests from trusted devices on its own, and files a received file
 * under Downloads/jsync. It lives as long as the process, so it works with no
 * screen open.
 */
class P2PHub(private val app: Context, private val host: NodeHost) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = Prefs(app)
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    private val lastStatus = HashMap<String, String>()
    private val outgoingDir = File(app.cacheDir, "outgoing")

    /** Every transfer the node knows, oldest first. */
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private val _received = MutableStateFlow(loadReceived())

    /** What arrived and was saved, newest first (kept across restarts). */
    val received: StateFlow<List<ReceivedFile>> = _received.asStateFlow()

    private fun loadReceived(): List<ReceivedFile> = runCatching {
        val a = JSONArray(prefs.receivedJson)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            ReceivedFile(o.getString("id"), o.getString("name"), o.optString("mime"), o.optLong("size"), o.optString("from"), o.getString("uri"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun addReceived(f: ReceivedFile) {
        val list = (listOf(f) + _received.value.filter { it.id != f.id }).take(50)
        _received.value = list
        prefs.receivedJson = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size)
                .put("from", it.from).put("uri", it.uri).put("at", it.at)
        }).toString()
    }

    /** Forgets a received file; with [deleteFile] it is also removed from the phone. */
    fun removeReceived(f: ReceivedFile, deleteFile: Boolean) {
        if (deleteFile) runCatching { app.contentResolver.delete(Uri.parse(f.uri), null, null) }
        val list = _received.value.filter { it.id != f.id }
        _received.value = list
        prefs.receivedJson = JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("size", it.size)
                .put("from", it.from).put("uri", it.uri).put("at", it.at)
        }).toString()
    }

    /** Opens the app on Devices with the window of this transfer. */
    private fun openApp(id: String): PendingIntent =
        PendingIntent.getActivity(
            app, notificationId(id),
            Intent(app, MainActivity::class.java).putExtra("screen", "devices").putExtra("transfer", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun start() {
        scope.launch {
            host.events.collect { ev -> if (ev.kind == "p2p") onEvent(ev) }
        }
        scope.launch {
            // After the node (re)starts, pick up what it already holds.
            var wasUp = false
            host.status.collect { s ->
                val up = s.state != "stopped" && s.state != "starting"
                if (up && !wasUp) runCatching { host.api?.p2pTransfers()?.let { _transfers.value = it } }
                wasUp = up
            }
        }
    }

    private fun onEvent(ev: NodeEvent) {
        if (ev.state == "dismissed") {
            _transfers.value = _transfers.value.filter { it.id != ev.path }
            lastStatus.remove(ev.path)
            nm.cancel(notificationId(ev.path))
            return
        }
        val t = ev.transfer ?: return
        _transfers.value = _transfers.value.filter { it.id != t.id } + t
        val before = lastStatus.put(t.id, t.status)
        when {
            t.status == "incoming" && before != "incoming" -> onIncoming(t)
            t.status == "active" || t.status == "connecting" -> progress(t)
            t.finished && before != t.status -> onFinished(t)
        }
    }

    private fun onIncoming(t: Transfer) {
        if (t.peerId.isNotEmpty() && t.peerId in prefs.trustedDevices) {
            scope.launch { runCatching { host.api?.p2pAccept(t.id) } }
            return
        }
        val open = openApp(t.id)
        val n = NotificationCompat.Builder(app, JsyncApp.CHANNEL_REQUESTS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(app.getString(R.string.notif_wants_to_send, t.peerName))
            .setContentText("${t.name} · ${Formatter.formatShortFileSize(app, t.size)}")
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, app.getString(R.string.accept), action(t.id, ACTION_ACCEPT))
            .addAction(0, app.getString(R.string.decline), action(t.id, ACTION_DECLINE))
            .build()
        runCatching { nm.notify(notificationId(t.id), n) }
    }

    private fun progress(t: Transfer) {
        val pct = if (t.size > 0) (t.bytes * 100 / t.size).toInt() else 0
        val n = NotificationCompat.Builder(app, JsyncApp.CHANNEL_TRANSFERS)
            .setSmallIcon(if (t.send) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(app.getString(if (t.send) R.string.notif_sending_to else R.string.notif_receiving_from, t.peerName))
            .setContentText(t.name)
            .setProgress(100, pct, t.status == "connecting")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(t.id))
            .addAction(0, app.getString(R.string.cancel), action(t.id, ACTION_CANCEL))
            .build()
        runCatching { nm.notify(notificationId(t.id), n) }
    }

    private fun onFinished(t: Transfer) {
        nm.cancel(notificationId(t.id))
        if (t.send) {
            if (t.path.startsWith(outgoingDir.path)) File(t.path).parentFile?.deleteRecursively()
            return
        }
        if (t.status != "done") return
        scope.launch {
            val saved = runCatching { saveToDownloads(t) }
            val b = NotificationCompat.Builder(app, JsyncApp.CHANNEL_TRANSFERS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setSilent(true)
            saved.onSuccess { uri ->
                b.setContentTitle(app.getString(R.string.notif_received, t.name)).setContentText(app.getString(R.string.notif_received_body, t.peerName))
                addReceived(ReceivedFile(t.id, t.name, t.mime.ifEmpty { mimeOf(t.name) }, t.size, t.peerName, uri.toString(), System.currentTimeMillis()))
                // Tapping it opens the window with the preview and what can be done with the file.
                b.setContentIntent(openApp(t.id))
                runCatching { host.api?.p2pDismiss(t.id) }
            }.onFailure {
                Log.w(TAG, "save ${t.name}: ${it.message}")
                b.setContentTitle(app.getString(R.string.notif_save_failed, t.name)).setContentText(it.message ?: "")
            }
            runCatching { nm.notify(notificationId(t.id), b.build()) }
        }
    }

    /** Moves a received file out of the node's staging folder to Downloads/jsync. */
    private fun saveToDownloads(t: Transfer): Uri {
        val src = File(t.path)
        val mime = t.mime.ifEmpty { mimeOf(t.name) }
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = app.contentResolver
            // Pictures and videos go where the gallery looks for them; everything else to Downloads.
            val (collection, folder) = when {
                mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_PICTURES
                mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MOVIES
                mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to Environment.DIRECTORY_MUSIC
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, t.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/jsync")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(collection, values) ?: error("could not create the file in $folder")
            try {
                cr.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out, 256 * 1024) } }
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                cr.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "jsync").apply { mkdirs() }
        val base = t.name.substringBeforeLast('.')
        val ext = t.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var dst = File(dir, t.name)
        var i = 1
        while (dst.exists()) { dst = File(dir, "$base ($i)$ext"); i++ }
        src.copyTo(dst)
        return Uri.fromFile(dst)
    }

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun notificationId(id: String) = 1000 + (id.hashCode() and 0x3fffffff) % 100000

    private fun action(id: String, what: String): PendingIntent {
        val i = Intent(app, P2PActionReceiver::class.java).setAction(what).putExtra("id", id)
        return PendingIntent.getBroadcast(app, notificationId(id) + what.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    // --- what the screens ask for ----------------------------------------------------------

    fun accept(id: String, trust: Boolean = false) {
        if (trust) _transfers.value.firstOrNull { it.id == id }?.peerId?.takeIf { it.isNotEmpty() }?.let { prefs.trustedDevices = prefs.trustedDevices + it }
        scope.launch { runCatching { host.api?.p2pAccept(id) } }
    }

    fun decline(id: String) { scope.launch { runCatching { host.api?.p2pDecline(id) } } }
    fun cancel(id: String) { scope.launch { runCatching { host.api?.p2pCancel(id) } } }
    fun dismiss(id: String) { scope.launch { runCatching { host.api?.p2pDismiss(id) } } }

    /** Copies [src] into the app's own staging folder (the node cannot open a content URI) and offers it to [to]. */
    fun send(to: Device, name: String, mime: String, open: () -> java.io.InputStream) {
        scope.launch {
            try {
                val dir = File(outgoingDir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                val f = File(dir, name.replace('/', '_'))
                open().use { ins -> f.outputStream().use { ins.copyTo(it, 256 * 1024) } }
                val api = host.ensureStarted()
                try {
                    api.p2pSend(to.id, to.name, name, mime, f.path)
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "send $name: ${e.message}")
                host.lastError.value = "Could not send $name: ${e.message}"
            }
        }
    }

    companion object {
        private const val TAG = "jsyncp2p"
        const val ACTION_ACCEPT = "dev.marck.jsyncdrive.P2P_ACCEPT"
        const val ACTION_DECLINE = "dev.marck.jsyncdrive.P2P_DECLINE"
        const val ACTION_CANCEL = "dev.marck.jsyncdrive.P2P_CANCEL"
    }
}

/** Handles the buttons of the transfer notifications. */
class P2PActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val hub = NodeHost.get(ctx).p2p
        when (intent.action) {
            P2PHub.ACTION_ACCEPT -> hub.accept(id)
            P2PHub.ACTION_DECLINE -> hub.decline(id)
            P2PHub.ACTION_CANCEL -> hub.cancel(id)
        }
        ctx.getSystemService(NotificationManager::class.java).cancel(1000 + (id.hashCode() and 0x3fffffff) % 100000)
    }
}
