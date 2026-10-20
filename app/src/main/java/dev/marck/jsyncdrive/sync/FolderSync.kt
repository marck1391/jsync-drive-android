package dev.marck.jsyncdrive.sync

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.node.NodeException
import dev.marck.jsyncdrive.node.NodeHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A folder on this phone kept in step with a folder of the Drive. */
data class SyncPair(
    val id: String,
    /** The tree the user picked with the system folder picker (a persisted permission). */
    val treeUri: String,
    val label: String,
    /** Drive folder, no leading slash ("" = the whole Drive). */
    val driveFolder: String,
    val enabled: Boolean = true,
)

data class SyncStatus(
    val running: Boolean = false,
    /** Epoch millis of the last finished run, 0 = never. */
    val lastRun: Long = 0,
    val message: String = "",
    val error: Boolean = false,
)

/**
 * Two-way folder sync, the phone's counterpart of `jsync watch` for a Drive
 * folder: a folder the user picks is kept equal to a folder of the Drive. Android
 * only lets an app into a user-chosen folder through the Storage Access Framework,
 * so the walk of the phone's side happens here and the node (which has the Drive
 * mounted) is asked for the other side and given/asked for the bytes.
 *
 * What to do is decided by [plan]; this class gathers the two sides, carries the
 * actions out and remembers what both looked like afterwards. It runs on a timer,
 * when the Drive reports a change inside a synced folder, when the phone's folder
 * reports one, and on demand.
 */
class FolderSync(private val app: Context, private val host: NodeHost) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cr: ContentResolver = app.contentResolver
    private val dir = File(app.filesDir, "sync").apply { mkdirs() }
    private val runLock = Mutex()
    private val pending = HashMap<String, Job>()

    private val _pairs = MutableStateFlow(loadPairs())
    private val _status = MutableStateFlow<Map<String, SyncStatus>>(emptyMap())
    val pairs: StateFlow<List<SyncPair>> = _pairs.asStateFlow()
    val status: StateFlow<Map<String, SyncStatus>> = _status.asStateFlow()

    fun start() {
        scope.launch {
            // Remote changes: sync the pair the change is in, after things settle.
            host.events.collect { ev ->
                if (ev.kind != "change") return@collect
                for (p in _pairs.value) {
                    if (p.enabled && (inside(p.driveFolder, ev.path) || (ev.old.isNotEmpty() && inside(p.driveFolder, ev.old)))) schedule(p.id, 4_000)
                }
            }
        }
        scope.launch {
            // The slow safety net, and the way a change made on the phone while the app was away is found.
            while (true) {
                delay(5 * 60_000)
                for (p in _pairs.value) if (p.enabled) schedule(p.id, 0)
            }
        }
        scope.launch {
            host.status.collect { s -> if (s.online) for (p in _pairs.value) if (p.enabled) schedule(p.id, 2_000) }
        }
        for (p in _pairs.value) watchLocal(p)
    }

    private fun inside(folder: String, path: String) = folder.isEmpty() || path == folder || path.startsWith("$folder/")

    // --- pairs ---------------------------------------------------------------------------------

    fun add(treeUri: Uri, label: String, driveFolder: String): SyncPair {
        val p = SyncPair(java.util.UUID.randomUUID().toString(), treeUri.toString(), label, driveFolder.trim('/'))
        save(_pairs.value + p)
        watchLocal(p)
        schedule(p.id, 0)
        return p
    }

    fun remove(id: String) {
        pending.remove(id)?.cancel()
        save(_pairs.value.filter { it.id != id })
        File(dir, "$id.base.json").delete()
        observers.remove(id)?.let { cr.unregisterContentObserver(it) }
        _status.value = _status.value - id
    }

    fun setEnabled(id: String, on: Boolean) {
        save(_pairs.value.map { if (it.id == id) it.copy(enabled = on) else it })
        if (on) schedule(id, 0)
    }

    fun syncNow(id: String) = schedule(id, 0)

    private fun save(list: List<SyncPair>) {
        _pairs.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("tree", it.treeUri).put("label", it.label).put("folder", it.driveFolder).put("enabled", it.enabled)) }
        File(dir, "pairs.json").writeText(arr.toString())
    }

    private fun loadPairs(): List<SyncPair> = runCatching {
        val arr = JSONArray(File(dir, "pairs.json").readText())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SyncPair(o.getString("id"), o.getString("tree"), o.optString("label"), o.optString("folder"), o.optBoolean("enabled", true))
        }
    }.getOrDefault(emptyList())

    // --- scheduling ----------------------------------------------------------------------------

    private val observers = HashMap<String, ContentObserver>()

    private fun watchLocal(p: SyncPair) {
        if (observers.containsKey(p.id)) return
        val tree = Uri.parse(p.treeUri)
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { if (!running.contains(p.id)) schedule(p.id, 5_000) }
        }
        runCatching {
            val root = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            cr.registerContentObserver(root, true, obs)
            observers[p.id] = obs
        }
    }

    private val running = HashSet<String>()

    /** Runs the pair after [delayMs]; asking again meanwhile just moves the time. */
    @Synchronized
    private fun schedule(id: String, delayMs: Long) {
        pending.remove(id)?.cancel()
        pending[id] = scope.launch {
            delay(delayMs)
            runLock.withLock { runPair(id) }
        }
    }

    // --- one run -------------------------------------------------------------------------------

    private class LocalFile(val docId: String, val sig: Sig)

    private class Scan(val files: Map<String, LocalFile>, val dirs: Map<String, String>)

    private suspend fun runPair(id: String) {
        val pair = _pairs.value.firstOrNull { it.id == id } ?: return
        if (!pair.enabled) return
        val api = host.api
        if (api == null || !host.status.value.online) return
        running += id
        setStatus(id, SyncStatus(running = true, message = "Syncing…"))
        try {
            val result = sync(pair, api)
            setStatus(id, SyncStatus(lastRun = System.currentTimeMillis(), message = result.first, error = result.second))
        } catch (e: Exception) {
            Log.w(TAG, "sync ${pair.label}: ${e.message}", e)
            setStatus(id, SyncStatus(lastRun = System.currentTimeMillis(), message = e.message ?: "Failed", error = true))
        } finally {
            running -= id
        }
    }

    private fun setStatus(id: String, s: SyncStatus) { _status.value = _status.value + (id to s) }

    private fun sync(pair: SyncPair, api: NodeApi): Pair<String, Boolean> {
        val tree = Uri.parse(pair.treeUri)
        if (cr.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
            return "The permission for this folder was lost — add it again." to true
        }
        val local = scanLocal(tree)
        val remoteAll = try {
            api.tree(pair.driveFolder)
        } catch (e: NodeException) {
            if (e.notFound) { api.mkdir(pair.driveFolder); emptyList() } else if (e.locked) return "That Drive folder is end-to-end encrypted." to true else throw e
        }
        val remote = remoteAll.filter { !it.dir && !hidden(it.path) }.associate { it.path to Sig(it.size, it.mtime) }
        val base = loadBase(pair.id)

        val p = plan(local.files.mapValues { it.value.sig }, remote, base)
        if (p.abort != null) return p.abort to true

        val failed = HashSet<String>()
        var up = 0; var down = 0; var del = 0; var conflicts = 0
        val dirIds = HashMap(local.dirs)
        for (a in p.actions) {
            try {
                when (a) {
                    is Action.Upload -> { upload(api, tree, pair, a.path, local.files.getValue(a.path)); up++ }
                    is Action.Download -> { download(api, tree, pair, a.path, null, dirIds); down++ }
                    is Action.DeleteRemote -> { api.rm(NodeApi.join(pair.driveFolder, a.path)); del++ }
                    is Action.DeleteLocal -> { DocumentsContract.deleteDocument(cr, docUri(tree, local.files.getValue(a.path).docId)); del++ }
                    is Action.Conflict -> {
                        // Keep the phone's version under another name (it syncs up as a new file), then take the Drive's.
                        val lf = local.files.getValue(a.path)
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        DocumentsContract.renameDocument(cr, docUri(tree, lf.docId), conflictName(NodeApi.nameOf(a.path), stamp))
                        download(api, tree, pair, a.path, null, dirIds)
                        conflicts++
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "${a::class.simpleName} ${a.path}: ${e.message}")
                failed += a.path
            }
        }

        // What the two sides look like now is what they have agreed on (apart from what failed).
        val after = scanLocal(tree)
        val remoteAfter = api.tree(pair.driveFolder).filter { !it.dir && !hidden(it.path) }.associate { it.path to Sig(it.size, it.mtime) }
        val newBase = HashMap<String, Base>()
        for ((path, l) in after.files) {
            val r = remoteAfter[path] ?: continue
            if (path in failed) base[path]?.let { newBase[path] = it } else newBase[path] = Base(l.sig, r)
        }
        // Files that disagree only because of a failure keep their old record, so the next run retries them.
        for (path in failed) base[path]?.let { newBase.putIfAbsent(path, it) }
        saveBase(pair.id, newBase)

        val parts = listOfNotNull(
            up.takeIf { it > 0 }?.let { "$it up" }, down.takeIf { it > 0 }?.let { "$it down" },
            del.takeIf { it > 0 }?.let { "$it deleted" }, conflicts.takeIf { it > 0 }?.let { "$it conflict(s) kept both" },
            failed.size.takeIf { it > 0 }?.let { "$it failed" },
        )
        return (if (parts.isEmpty()) "Up to date" else parts.joinToString(", ")) to failed.isNotEmpty()
    }

    private fun conflictName(name: String, stamp: String): String {
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        return if (dot == null) "$name (phone $stamp)" else name.substring(0, dot) + " (phone $stamp)" + name.substring(dot)
    }

    private fun hidden(path: String) = path.split('/').any { it.startsWith(".") }

    // --- the phone's side ----------------------------------------------------------------------

    private fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private fun scanLocal(tree: Uri): Scan {
        val files = HashMap<String, LocalFile>()
        val dirs = HashMap<String, String>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        dirs[""] = rootId
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add("" to rootId)
        while (stack.isNotEmpty()) {
            val (rel, docId) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            cr.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val path = NodeApi.join(rel, name)
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        dirs[path] = id
                        stack.add(path to id)
                    } else {
                        files[path] = LocalFile(id, Sig(if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
                    }
                }
            } ?: error("cannot read the folder")
        }
        return Scan(files, dirs)
    }

    private fun upload(api: NodeApi, tree: Uri, pair: SyncPair, path: String, lf: LocalFile) {
        val uri = docUri(tree, lf.docId)
        val target = NodeApi.join(pair.driveFolder, path)
        cr.openInputStream(uri)!!.use { api.put(target, it, lf.sig.size.takeIf { s -> s > 0 } ?: -1, lf.sig.mtime) }
    }

    /** Fetches [path] from the Drive into the phone's folder, creating folders and replacing a file that is there. */
    private fun download(api: NodeApi, tree: Uri, pair: SyncPair, path: String, existing: Uri?, dirIds: HashMap<String, String>) {
        val remote = NodeApi.join(pair.driveFolder, path)
        val parentRel = NodeApi.parentOf(path)
        val name = NodeApi.nameOf(path)
        val parentId = ensureDir(tree, parentRel, dirIds)
        val uri = existing ?: findChild(tree, parentId, name) ?: DocumentsContract.createDocument(cr, docUri(tree, parentId), "application/octet-stream", name)
            ?: error("could not create $name")
        cr.openOutputStream(uri, "wt")!!.use { api.download(remote, it) }
    }

    private fun ensureDir(tree: Uri, rel: String, dirIds: HashMap<String, String>): String {
        dirIds[rel]?.let { return it }
        val parentId = ensureDir(tree, NodeApi.parentOf(rel), dirIds)
        val name = NodeApi.nameOf(rel)
        val existing = findChild(tree, parentId, name)
        val id = if (existing != null) DocumentsContract.getDocumentId(existing)
        else DocumentsContract.getDocumentId(DocumentsContract.createDocument(cr, docUri(tree, parentId), Document.MIME_TYPE_DIR, name) ?: error("could not create folder $name"))
        dirIds[rel] = id
        return id
    }

    private fun findChild(tree: Uri, parentId: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        cr.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return docUri(tree, c.getString(0))
        }
        return null
    }

    // --- remembering what was agreed -----------------------------------------------------------

    private fun loadBase(id: String): Map<String, Base> = runCatching {
        val o = JSONObject(File(dir, "$id.base.json").readText())
        o.keys().asSequence().associateWith { k ->
            val e = o.getJSONArray(k)
            Base(Sig(e.getLong(0), e.getLong(1)), Sig(e.getLong(2), e.getLong(3)))
        }
    }.getOrDefault(emptyMap())

    private fun saveBase(id: String, base: Map<String, Base>) {
        val o = JSONObject()
        base.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.local.size, v.local.mtime, v.remote.size, v.remote.mtime))) }
        val tmp = File(dir, "$id.base.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "$id.base.json"))
    }

    companion object { private const val TAG = "jsyncsync" }
}
