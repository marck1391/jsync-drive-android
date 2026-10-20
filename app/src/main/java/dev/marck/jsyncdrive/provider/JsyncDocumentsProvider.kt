package dev.marck.jsyncdrive.provider

import android.app.AuthenticationRequiredException
import android.app.PendingIntent
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.node.NodeException
import dev.marck.jsyncdrive.node.NodeHost
import dev.marck.jsyncdrive.ui.MainActivity
import dev.marck.jsyncdrive.ui.UnlockActivity
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * The Drive as a storage location every app can use (Files, "Open with", the
 * picker of any app that asks for a document). This is the Android counterpart of
 * the Cloud Filter API mount on Windows: the system asks "list this folder" /
 * "give me the bytes at this offset" and the node answers from the mounted Drive,
 * fetching only what is read.
 *
 * Document ids are Drive paths with a leading slash; the root is "/".
 */
class JsyncDocumentsProvider : DocumentsProvider() {

    private val ioThread = HandlerThread("jsync-docs-io").apply { start() }
    private val ioHandler by lazy { Handler(ioThread.looper) }

    private val host get() = NodeHost.get(context!!)

    override fun onCreate(): Boolean {
        // Remote changes tell the file managers to refresh the folder they show.
        val ctx = context!!
        Thread({
            // The listener lives as long as the process; it starts when the node does.
            val h = NodeHost.get(ctx)
            runCatching {
                kotlinx.coroutines.runBlocking {
                    h.events.collect { ev ->
                        Log.i(TAG, "event ${ev.kind} ${ev.op} ${ev.path}")
                        if (ev.kind == "change" || ev.kind == "protected") {
                            notifyFolder(parentOf(ev.path))
                            if (ev.old.isNotEmpty()) notifyFolder(parentOf(ev.old))
                            notifyFolder(ev.path) // a folder whose own listing changed (an encrypted folder opened)
                        } else if (ev.kind == "state" || ev.kind == "mounted") {
                            notifyRoots()
                            notifyFolder("")
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "event listener stopped: $it") }
        }, "jsync-docs-events").apply { isDaemon = true; start() }
        return true
    }

    // --- roots -------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val s = host.status.value
        val row = c.newRow()
        row.add(Root.COLUMN_ROOT_ID, ROOT_ID)
        row.add(Root.COLUMN_DOCUMENT_ID, "/")
        row.add(Root.COLUMN_TITLE, context!!.getString(R.string.root_title))
        row.add(
            Root.COLUMN_SUMMARY,
            when {
                s.online -> s.email
                s.loggedIn -> "Offline"
                else -> "Not signed in"
            },
        )
        row.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH)
        return c
    }

    private fun notifyRoots() {
        context?.contentResolver?.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
    }

    private fun notifyFolder(path: String) {
        context?.contentResolver?.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, idOf(path)), null)
    }

    // --- metadata ----------------------------------------------------------------------------

    /** The node, started and mounted, or an exception the system shows as "can't open". */
    private fun api(): NodeApi {
        val api = host.ensureStarted()
        val s = if (host.status.value.online) host.status.value else host.awaitOnline(20_000)
        if (!s.online) {
            // Not signed in (or not set up): send the user to the app to do it.
            if (s.state == "logged_out" || s.state == "needs_setup" || s.state == "pending") {
                val intent = Intent(context, MainActivity::class.java)
                val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                throw AuthenticationRequiredException(IOException("jsync Drive is not signed in"), pi)
            }
            throw IOException(s.detail.ifEmpty { "jsync Drive is not connected" })
        }
        return api
    }

    private fun <T> node(block: (NodeApi) -> T): T {
        val api = api()
        try {
            return block(api)
        } catch (e: NodeException) {
            throw when {
                e.notFound -> FileNotFoundException(e.message)
                e.locked -> locked()
                else -> IOException(e.message)
            }
        }
    }

    private fun locked(folder: String = ""): AuthenticationRequiredException {
        val intent = Intent(context, UnlockActivity::class.java).putExtra(UnlockActivity.EXTRA_FOLDER, folder)
        val pi = PendingIntent.getActivity(context, folder.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return AuthenticationRequiredException(IOException("This folder is end-to-end encrypted"), pi)
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val path = pathOf(documentId)
        if (path.isEmpty()) {
            addRoot(c)
        } else {
            addEntry(c, node { it.stat(path) })
        }
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val path = pathOf(parentDocumentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        c.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        val api = api()
        val kids = try {
            api.ls(path)
        } catch (e: NodeException) {
            when {
                e.locked -> throw locked(path)
                e.notFound -> throw FileNotFoundException(e.message)
                else -> throw IOException(e.message)
            }
        }
        // Folders first, then by name, like every file manager expects.
        kids.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() })).forEach { addEntry(c, it) }
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        node { it.search(query, 100) }.forEach { addEntry(c, it) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = pathOf(parentDocumentId)
        val doc = pathOf(documentId)
        return parent.isEmpty() || doc.startsWith("$parent/")
    }

    override fun getDocumentType(documentId: String): String {
        val path = pathOf(documentId)
        if (path.isEmpty()) return Document.MIME_TYPE_DIR
        val e = node { it.stat(path) }
        return mimeOf(e)
    }

    private fun addRoot(c: MatrixCursor) {
        val rw = host.status.value.readWrite
        val row = c.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, "/")
        row.add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.root_title))
        row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
        row.add(Document.COLUMN_SIZE, null)
        row.add(Document.COLUMN_LAST_MODIFIED, null)
        row.add(Document.COLUMN_FLAGS, if (rw) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
    }

    private fun addEntry(c: MatrixCursor, e: Entry) {
        val rw = host.status.value.readWrite
        var flags = 0
        if (e.dir) {
            if (rw && !e.locked) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (rw) {
            flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        // A protected folder is managed from the web app: it can be opened, not renamed/moved/deleted here.
        if (rw && !e.protected) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        }
        val row = c.newRow()
        row.add(Document.COLUMN_DOCUMENT_ID, idOf(e.path))
        row.add(Document.COLUMN_DISPLAY_NAME, e.name)
        row.add(Document.COLUMN_MIME_TYPE, mimeOf(e))
        row.add(Document.COLUMN_SIZE, if (e.dir) null else e.size)
        row.add(Document.COLUMN_LAST_MODIFIED, e.mtime.takeIf { it > 0 })
        row.add(Document.COLUMN_FLAGS, flags)
        if (e.locked) row.add(Document.COLUMN_SUMMARY, "End-to-end encrypted — tap to unlock")
        else if (e.protected) row.add(Document.COLUMN_SUMMARY, "End-to-end encrypted")
    }

    // --- reading -----------------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val path = pathOf(documentId)
        val api = api()
        val writing = mode.any { it == 'w' || it == 'a' }
        return if (!writing) openForRead(api, path) else openForWrite(api, path, mode)
    }

    /** A file descriptor the system reads through; each read is a range request answered from the node's cache or the Drive. */
    private fun openForRead(api: NodeApi, path: String): ParcelFileDescriptor {
        val e = try {
            api.stat(path)
        } catch (ex: NodeException) {
            throw if (ex.notFound) FileNotFoundException(path) else if (ex.locked) locked(NodeApi.parentOf(path)) else IOException(ex.message)
        }
        if (e.dir) throw FileNotFoundException("$path is a folder")
        val sm = context!!.getSystemService(StorageManager::class.java)
        return sm.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = e.size

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= e.size) return 0
                    return try {
                        api.read(path, offset, data, minOf(size, data.size))
                    } catch (ex: Exception) {
                        Log.w(TAG, "read $path @$offset: ${ex.message}")
                        throw ErrnoException("onRead", OsConstants.EIO)
                    }
                }

                override fun onRelease() {}
            },
            ioHandler,
        )
    }

    /**
     * Writing goes to a scratch file; when the writer closes it, the node gets the
     * whole file and publishes it (the Drive replaces a file as a whole). Appending
     * or editing in place starts from the current contents.
     */
    private fun openForWrite(api: NodeApi, path: String, mode: String): ParcelFileDescriptor {
        val scratch = File(context!!.cacheDir, "uploads").apply { mkdirs() }.let { File(it, UUID.randomUUID().toString()) }
        val truncate = mode.contains('t') || mode == "w"
        if (!truncate) {
            // r/w or append over an existing file: begin from what is there.
            try {
                scratch.outputStream().use { api.download(path, it) }
            } catch (ex: NodeException) {
                if (!ex.notFound) { scratch.delete(); throw IOException(ex.message) }
            }
        }
        val pfdMode = ParcelFileDescriptor.parseMode(if (mode == "w") "wt" else mode)
        return ParcelFileDescriptor.open(scratch, pfdMode, ioHandler) { err ->
            try {
                if (err == null) {
                    scratch.inputStream().use { api.put(path, it, scratch.length(), System.currentTimeMillis()) }
                    notifyFolder(NodeApi.parentOf(path))
                } else {
                    Log.w(TAG, "write $path: ${err.message}")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "upload $path: ${ex.message}")
            } finally {
                scratch.delete()
            }
        }
    }

    // --- changing ----------------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = pathOf(parentDocumentId)
        return node { api ->
            val taken = api.ls(parent).map { it.name }.toSet()
            val name = uniqueName(displayName, taken)
            val path = NodeApi.join(parent, name)
            if (mimeType == Document.MIME_TYPE_DIR) api.mkdir(path) else api.put(path, ByteArray(0).inputStream(), 0)
            notifyFolder(parent)
            idOf(path)
        }
    }

    override fun deleteDocument(documentId: String) {
        val path = pathOf(documentId)
        node { it.rm(path) }
        notifyFolder(NodeApi.parentOf(path))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val path = pathOf(documentId)
        val parent = NodeApi.parentOf(path)
        val target = NodeApi.join(parent, displayName)
        node { it.mv(path, target) }
        notifyFolder(parent)
        return idOf(target)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val from = pathOf(sourceDocumentId)
        val toParent = pathOf(targetParentDocumentId)
        val to = NodeApi.join(toParent, NodeApi.nameOf(from))
        node { it.mv(from, to) }
        notifyFolder(pathOf(sourceParentDocumentId))
        notifyFolder(toParent)
        return idOf(to)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun uniqueName(want: String, taken: Set<String>): String {
        if (want !in taken) return want
        val dot = want.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { want.substring(0, it) } ?: want
        val ext = dot?.let { want.substring(it) } ?: ""
        var i = 1
        while (true) {
            val n = "$base ($i)$ext"
            if (n !in taken) return n
            i++
        }
    }

    companion object {
        private const val TAG = "jsyncdocs"
        const val AUTHORITY = "dev.marck.jsyncdrive.documents"
        const val ROOT_ID = "drive"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SUMMARY,
        )

        fun idOf(path: String) = "/$path"
        fun pathOf(id: String) = id.trim('/')
        private fun parentOf(path: String) = NodeApi.parentOf(path.trim('/'))

        fun mimeOf(e: Entry): String {
            if (e.dir) return Document.MIME_TYPE_DIR
            val ext = e.name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
