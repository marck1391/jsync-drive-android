package dev.marck.jsyncdrive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.node.NodeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Previews of the pictures in the Drive. A picture is fetched once through the node, shrunk, and kept in a small
 * memory cache and as a JPEG in the app's cache folder (keyed by path, size and modification time), so the next
 * visit costs nothing.
 */
object Thumbnails {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    private const val MaxBytes = 40L * 1024 * 1024
    private val gate = Semaphore(3)
    private val failed = HashSet<String>()
    private val mem = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun canThumb(e: Entry): Boolean =
        !e.dir && e.size in 1..MaxBytes && e.name.substringAfterLast('.', "").lowercase() in imageExt

    private fun key(e: Entry, px: Int): String {
        val d = MessageDigest.getInstance("SHA-1").digest("${e.path}|${e.size}|${e.mtime}|$px".toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    /** The picture if it is already in memory, so a list scrolled back to it shows it in the first frame. */
    fun peek(e: Entry, px: Int = 400): Bitmap? = mem.get(key(e, px))

    suspend fun load(ctx: Context, api: () -> NodeApi?, e: Entry, px: Int = 400): Bitmap? {
        if (!canThumb(e)) return null
        val k = key(e, px)
        mem.get(k)?.let { return it }
        if (synchronized(failed) { k in failed }) return null
        return withContext(Dispatchers.IO) {
            val file = File(File(ctx.cacheDir, "thumbs").apply { mkdirs() }, "$k.jpg")
            val cached = if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
            if (cached != null) { mem.put(k, cached); return@withContext cached }
            gate.withPermit {
                mem.get(k)?.let { return@withPermit it }
                // Decoding pictures must not take the frame budget of the list being scrolled.
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                val bmp = try {
                    runCatching { fetch(api() ?: return@withPermit null, e, px) }.getOrNull()
                } finally {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                }
                if (bmp == null) {
                    synchronized(failed) { failed += k }
                    return@withPermit null
                }
                runCatching { FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
                mem.put(k, bmp)
                bmp
            }
        }
    }

    private fun fetch(api: NodeApi, e: Entry, px: Int): Bitmap? {
        val out = ByteArrayOutputStream(e.size.toInt().coerceIn(1024, 8 * 1024 * 1024))
        api.download(e.path, out)
        return decodeBytes(out.toByteArray(), px)
    }

    /** A picture the phone itself holds (what another device sent, say), shrunk to about [px]. Null when it is not one. */
    suspend fun decodeUri(ctx: Context, uri: android.net.Uri, px: Int = 1024): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            if (bytes.size > MaxBytes) null else decodeBytes(bytes, px)
        }.getOrNull()
    }

    private fun decodeBytes(bytes: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val rotation = runCatching {
            when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }
        return bmp
    }
}

/** The preview of [e] when it is a picture, once it has loaded (null before, and for everything that is not a picture). */
@Composable
fun rememberThumbnail(vm: AppViewModel, e: Entry): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(Thumbnails.peek(e)?.asImageBitmap(), e.path, e.size, e.mtime) {
        if (value == null && Thumbnails.canThumb(e)) value = Thumbnails.load(ctx, { vm.host.api }, e)?.asImageBitmap()
    }.value
}
