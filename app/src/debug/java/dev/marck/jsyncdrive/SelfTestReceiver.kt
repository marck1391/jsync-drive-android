package dev.marck.jsyncdrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import dev.marck.jsyncdrive.provider.JsyncDocumentsProvider
import java.io.FileInputStream

/**
 * `adb shell am broadcast -n dev.marck.jsyncdrive/.SelfTestReceiver -a x --es op <op> [--es arg ...]`
 * Runs one provider operation as this app and logs "SELFTEST <op>: ..." (tag jsyncselftest).
 */
class SelfTestReceiver : BroadcastReceiver() {
    private fun doc(id: String): Uri = DocumentsContract.buildDocumentUri(JsyncDocumentsProvider.AUTHORITY, id)

    override fun onReceive(ctx: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return
        val a = intent.getStringExtra("arg") ?: ""
        val b = intent.getStringExtra("arg2") ?: ""
        val pending = goAsync()
        Thread {
            val cr = ctx.contentResolver
            val out = try {
                when (op) {
                    "ls" -> {
                        val uri = DocumentsContract.buildChildDocumentsUri(JsyncDocumentsProvider.AUTHORITY, a)
                        cr.query(uri, null, null, null, null)!!.use { c ->
                            val names = mutableListOf<String>()
                            while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) +
                                "(" + c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) + "," +
                                c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)) + ")"
                            names.toString()
                        }
                    }
                    "read" -> cr.openInputStream(doc(a))!!.use { String(it.readBytes()) }.let { "${it.length} bytes: ${it.take(60).trim()}" }
                    "readat" -> cr.openFileDescriptor(doc(a), "r")!!.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                            val buf = java.nio.ByteBuffer.allocate(b.toInt())
                            ch.read(buf, 15000)
                            "size=${ch.size()} @15000: ${String(buf.array())}"
                        }
                    }
                    "create" -> DocumentsContract.createDocument(cr, doc(a), "text/plain", b)!!.toString()
                    "mkdir" -> DocumentsContract.createDocument(cr, doc(a), DocumentsContract.Document.MIME_TYPE_DIR, b)!!.toString()
                    "write" -> { cr.openOutputStream(doc(a), "wt")!!.use { it.write(b.toByteArray()) }; "ok" }
                    "append" -> { cr.openOutputStream(doc(a), "wa")!!.use { it.write(b.toByteArray()) }; "ok" }
                    "rename" -> DocumentsContract.renameDocument(cr, doc(a), b)?.toString() ?: "null"
                    "move" -> DocumentsContract.moveDocument(cr, doc(a), doc(a.substringBeforeLast('/').ifEmpty { "/" }), doc(b))?.toString() ?: "null"
                    "delete" -> DocumentsContract.deleteDocument(cr, doc(a)).toString()
                    "p2pdevices" -> dev.marck.jsyncdrive.node.NodeHost.get(ctx).ensureStarted().p2pDevices().toString()
                    "p2psend" -> {
                        // a: device id, b: size in bytes; sends that many pseudo-random bytes (seed 7)
                        val host = dev.marck.jsyncdrive.node.NodeHost.get(ctx)
                        val dev = host.ensureStarted().p2pDevices().first { it.id == a }
                        val rnd = java.util.Random(7)
                        val bytes = ByteArray(b.toInt()).also { rnd.nextBytes(it) }
                        host.p2p.send(dev, "from-phone.bin", "application/octet-stream") { bytes.inputStream() }
                        "queued sha256=" + java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
                    }
                    "observe" -> {
                        val uri = DocumentsContract.buildChildDocumentsUri(JsyncDocumentsProvider.AUTHORITY, a)
                        val latch = java.util.concurrent.CountDownLatch(1)
                        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
                            override fun onChange(selfChange: Boolean) { latch.countDown() }
                        }
                        cr.registerContentObserver(uri, false, obs)
                        // The listing registers the notification URI.
                        cr.query(uri, null, null, null, null)!!.close()
                        val fired = latch.await(b.toLongOrNull() ?: 30, java.util.concurrent.TimeUnit.SECONDS)
                        cr.unregisterContentObserver(obs)
                        if (fired) "CHANGE-NOTIFIED" else "no notification"
                    }
                    else -> "unknown op"
                }
            } catch (e: Throwable) {
                "ERROR ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i("jsyncselftest", "SELFTEST $op $a: $out")
            pending.finish()
        }.start()
    }
}
