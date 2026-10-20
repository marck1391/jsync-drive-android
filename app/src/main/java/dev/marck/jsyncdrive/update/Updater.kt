package dev.marck.jsyncdrive.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.node.NodeException
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A newer build published in the Drive. */
data class UpdateInfo(val versionCode: Long, val versionName: String, val apk: String, val size: Long, val sha256: String, val notes: String)

/**
 * Updates from the user's own Drive: `publish-update.ps1` puts the APK and a small `latest.json` in
 * [FOLDER]; the app reads it through the node it is already signed in with, so there is no server to run and
 * only the account's owner can publish. The APK is checked against the SHA-256 in the manifest, then handed to
 * the system installer (which itself refuses anything not signed with the key of the installed app).
 */
object Updater {
    const val FOLDER = "Apps/jsync-android"
    private const val MANIFEST = "$FOLDER/latest.json"
    const val ACTION_RESULT = "dev.marck.jsyncdrive.UPDATE_RESULT"

    /** A failure reported by the system installer after the install was handed over (null when none is pending). */
    val installFailure = MutableStateFlow<String?>(null)

    fun installedCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /** The published build, or null when nothing has been published to the Drive yet. */
    fun fetchLatest(api: NodeApi): UpdateInfo? {
        val out = ByteArrayOutputStream()
        try {
            api.download(MANIFEST, out)
        } catch (e: NodeException) {
            if (e.code == 404 || e.message == "not found") return null
            throw e
        }
        val o = JSONObject(out.toString("UTF-8"))
        return UpdateInfo(
            o.getLong("versionCode"), o.optString("versionName"), o.getString("apk"),
            o.optLong("size"), o.optString("sha256").lowercase(), o.optString("notes"),
        )
    }

    /** Downloads the APK into the app's cache, reporting progress, and verifies it. */
    fun download(ctx: Context, api: NodeApi, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        file.outputStream().use { raw ->
            val counting = object : FilterOutputStream(raw) {
                override fun write(b: Int) { out.write(b); md.update(b.toByte()); done++ }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len); md.update(b, off, len); done += len
                    onProgress(done, info.size)
                }
            }
            api.download("$FOLDER/${info.apk}", counting as OutputStream)
        }
        val sum = md.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256.isNotEmpty() && sum != info.sha256) {
            file.delete()
            error(ctx.getString(R.string.update_bad_checksum))
        }
        return file
    }

    /** False when the user still has to allow this app to install packages (the settings page is opened for them). */
    fun ensureMayInstall(ctx: Context): Boolean {
        if (ctx.packageManager.canRequestPackageInstalls()) return true
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }

    /** Hands [apk] to the system installer. On success the app is replaced and restarted by the system. */
    fun install(ctx: Context, apk: File) {
        installFailure.value = null
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // After the first approval, later updates from this app need no confirmation (Android 12+).
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { ins ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    ins.copyTo(out, 256 * 1024)
                    session.fsync(out)
                }
            }
            val result = Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_RESULT)
            val pending = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    /** Whatever an earlier update left in the cache. */
    fun cleanUp(ctx: Context) {
        File(ctx.cacheDir, "update").deleteRecursively()
    }
}

/** Receives what the system installer says about an update: asks the user to confirm when it needs to, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                Updater.installFailure.value = why.ifEmpty { context.getString(R.string.err_failed) }
                Toast.makeText(context, context.getString(R.string.update_failed, why), Toast.LENGTH_LONG).show()
            }
        }
    }
}
