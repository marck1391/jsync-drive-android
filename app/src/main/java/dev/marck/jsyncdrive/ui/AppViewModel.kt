package dev.marck.jsyncdrive.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.marck.jsyncdrive.Prefs
import dev.marck.jsyncdrive.R
import dev.marck.jsyncdrive.node.Entry
import dev.marck.jsyncdrive.node.NodeApi
import dev.marck.jsyncdrive.node.NodeException
import dev.marck.jsyncdrive.node.NodeHost
import dev.marck.jsyncdrive.node.NodeService
import dev.marck.jsyncdrive.node.ReceivedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One upload in progress or finished, for the list at the bottom of the file screen. */
data class Transfer(val id: Long, val name: String, val done: Boolean = false, val error: String? = null)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val host = NodeHost.get(app)
    val prefs = Prefs(app)
    val status = host.status

    /** Current folder, no leading slash. */
    var path by mutableStateOf("")
        private set
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Set when the folder being shown is an encrypted one that has to be opened first. */
    var lockedFolder by mutableStateOf<String?>(null)
        private set
    var transfers by mutableStateOf<List<Transfer>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)

    /** The encrypted folder that was just opened: the screen plays the vault door once for it. */
    var justUnlocked by mutableStateOf<String?>(null)

    // --- search ---
    var searching by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<Entry>>(emptyList())
        private set
    private var searchJob: Job? = null

    // --- what other devices send ---
    val received get() = p2p.received

    /** The transfer whose window is open (a request, one in progress, or a file that arrived). */
    var transferDialog by mutableStateOf<String?>(null)
    private val shownRequests = HashSet<String>()

    fun showTransfer(id: String) { transferDialog = id }

    /** True once per incoming request: the window is raised by itself the first time one is seen while the app is open. */
    fun claimRequestWindow(id: String): Boolean = shownRequests.add(id)

    private fun receivedIntent(f: ReceivedFile, share: Boolean): android.content.Intent {
        val uri = Uri.parse(f.uri)
        val intent = if (share) {
            android.content.Intent(android.content.Intent.ACTION_SEND).setType(f.mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
        } else {
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, f.mime)
        }
        intent.clipData = android.content.ClipData.newRawUri(f.name, uri)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return android.content.Intent.createChooser(intent, f.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun openReceived(f: ReceivedFile) {
        runCatching { getApplication<Application>().startActivity(receivedIntent(f, share = false)) }
            .onFailure { message = str(R.string.err_open) }
    }

    fun shareReceived(f: ReceivedFile) {
        runCatching { getApplication<Application>().startActivity(receivedIntent(f, share = true)) }
            .onFailure { message = str(R.string.err_open) }
    }

    fun deleteReceived(f: ReceivedFile) = p2p.removeReceived(f, deleteFile = true)
    fun forgetReceived(f: ReceivedFile) = p2p.removeReceived(f, deleteFile = false)

    // --- updates from the Drive ---
    var update by mutableStateOf<dev.marck.jsyncdrive.update.UpdateState>(dev.marck.jsyncdrive.update.UpdateState.Idle)
        private set

    /** Asks the Drive whether a newer build was published. A silent check keeps quiet when there is nothing new. */
    fun checkUpdate(manual: Boolean = false) {
        val cur = update
        if (cur is dev.marck.jsyncdrive.update.UpdateState.Checking || cur is dev.marck.jsyncdrive.update.UpdateState.Downloading ||
            cur is dev.marck.jsyncdrive.update.UpdateState.Installing
        ) return
        val app = getApplication<Application>()
        prefs.lastUpdateCheck = System.currentTimeMillis()
        if (manual) update = dev.marck.jsyncdrive.update.UpdateState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            val next = try {
                val info = dev.marck.jsyncdrive.update.Updater.fetchLatest(host.ensureStarted())
                when {
                    info == null -> dev.marck.jsyncdrive.update.UpdateState.NoneYet
                    info.versionCode > dev.marck.jsyncdrive.update.Updater.installedCode(app) -> dev.marck.jsyncdrive.update.UpdateState.Available(info)
                    else -> dev.marck.jsyncdrive.update.UpdateState.UpToDate
                }
            } catch (e: Exception) {
                if (manual) dev.marck.jsyncdrive.update.UpdateState.Failed(e.message ?: str(R.string.err_failed)) else dev.marck.jsyncdrive.update.UpdateState.Idle
            }
            withContext(Dispatchers.Main) { update = next }
        }
    }

    /** Downloads the published build, verifies it and hands it to the system installer. */
    fun installUpdate() {
        val info = (update as? dev.marck.jsyncdrive.update.UpdateState.Available)?.info ?: return
        val app = getApplication<Application>()
        if (!dev.marck.jsyncdrive.update.Updater.ensureMayInstall(app)) {
            message = str(R.string.update_allow_installs)
            return
        }
        update = dev.marck.jsyncdrive.update.UpdateState.Downloading(info, 0, info.size)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var last = 0L
                val file = dev.marck.jsyncdrive.update.Updater.download(app, host.ensureStarted(), info) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 150) { last = now; update = dev.marck.jsyncdrive.update.UpdateState.Downloading(info, done, total) }
                }
                withContext(Dispatchers.Main) { update = dev.marck.jsyncdrive.update.UpdateState.Installing(info) }
                dev.marck.jsyncdrive.update.Updater.install(app, file)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { update = dev.marck.jsyncdrive.update.UpdateState.Failed(e.message ?: str(R.string.err_failed)) }
            }
        }
    }

    // --- the account's folder colours, pins and shares ---
    var colors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var pins by mutableStateOf<Set<String>>(emptySet())
        private set
    var shares by mutableStateOf<List<dev.marck.jsyncdrive.node.DriveShare>>(emptyList())
        private set

    /** Reads the colours and pins the Drive keeps for the account (changes made on the web show up here). */
    fun loadMeta() {
        viewModelScope.launch(Dispatchers.IO) {
            val api = host.api ?: return@launch
            runCatching { api.folderColors() }.onSuccess { c -> withContext(Dispatchers.Main) { colors = c } }
            runCatching { api.pins() }.onSuccess { p -> withContext(Dispatchers.Main) { pins = p } }
        }
    }

    /** [color] is one of the palette's hex values, or empty for the default. */
    fun setColor(folder: String, color: String) {
        val before = colors
        colors = if (color.isEmpty()) colors - folder else colors + (folder to color)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                host.ensureStarted().setFolderColor(folder, color)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { colors = before; message = str(R.string.err_color) + ": " + (e.message ?: "") }
            }
        }
    }

    fun togglePin(folder: String) {
        val on = folder !in pins
        val before = pins
        pins = if (on) pins + folder else pins - folder
        viewModelScope.launch(Dispatchers.IO) {
            try {
                host.ensureStarted().setPinned(folder, on)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { pins = before; message = str(R.string.err_pin) + ": " + (e.message ?: "") }
            }
        }
    }

    fun loadShares() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.ensureStarted().shares() }.onSuccess { s -> withContext(Dispatchers.Main) { shares = s } }
        }
    }

    /** Shares [folder] with the person at [email]; [onResult] gets an error message or null. */
    fun share(folder: String, email: String, permission: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = try {
                host.ensureStarted().createShare(folder, email.trim(), permission)
                null
            } catch (e: Exception) {
                e.message ?: str(R.string.err_failed)
            }
            val list = runCatching { host.ensureStarted().shares() }.getOrNull()
            withContext(Dispatchers.Main) {
                if (list != null) shares = list
                onResult(err)
            }
        }
    }

    fun unshare(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.ensureStarted().removeShare(id) }.onFailure { message = it.message }
            runCatching { host.ensureStarted().shares() }.onSuccess { s -> withContext(Dispatchers.Main) { shares = s } }
        }
    }

    /** Saves a Drive file to the phone's Downloads folder. */
    fun download(e: Entry) {
        val app = getApplication<Application>()
        message = str(R.string.msg_downloading, e.name)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = host.ensureStarted()
                val mime = dev.marck.jsyncdrive.provider.JsyncDocumentsProvider.mimeOf(e)
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val cr = app.contentResolver
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, e.name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("could not create the file")
                    try {
                        cr.openOutputStream(uri)!!.use { api.download(e.path, it) }
                        cr.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    } catch (ex: Exception) {
                        cr.delete(uri, null, null)
                        throw ex
                    }
                } else {
                    val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!.apply { mkdirs() }
                    java.io.File(dir, e.name).outputStream().use { api.download(e.path, it) }
                }
                withContext(Dispatchers.Main) { message = str(R.string.msg_downloaded, e.name) }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) { message = str(R.string.err_download) + ": " + (ex.message ?: "") }
            }
        }
    }

    private fun str(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    // --- other devices ---
    val p2p = host.p2p
    val folderSync = host.folderSync
    val p2pTransfers = p2p.transfers
    var devices by mutableStateOf<List<dev.marck.jsyncdrive.node.Device>>(emptyList())
        private set
    var devicesError by mutableStateOf<String?>(null)
        private set
    var trusted by mutableStateOf(prefs.trustedDevices)
        private set

    private var nextTransfer = 1L

    init {
        dev.marck.jsyncdrive.update.Updater.cleanUp(app)
        viewModelScope.launch {
            dev.marck.jsyncdrive.update.Updater.installFailure.collect { why ->
                if (why != null) update = dev.marck.jsyncdrive.update.UpdateState.Failed(why)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.ensureStarted(); host.refresh() }.onFailure { error = it.message }
            if (host.status.value.loggedIn && prefs.keepConnected) NodeService.start(getApplication())
        }
        viewModelScope.launch {
            host.events.collect { ev ->
                val here = path
                val touchesHere = ev.kind != "change" ||
                    NodeApi.parentOf(ev.path) == here || ev.path == here ||
                    (ev.old.isNotEmpty() && NodeApi.parentOf(ev.old) == here)
                if (touchesHere) load(quiet = true)
            }
        }
        viewModelScope.launch {
            host.lastError.collect { e -> if (e != null) { message = e; host.lastError.value = null } }
        }
        viewModelScope.launch {
            var wasOnline = false
            host.status.collect { s ->
                if (s.online && !wasOnline) {
                    error = null; load(quiet = true); loadMeta()
                    if (System.currentTimeMillis() - prefs.lastUpdateCheck > 6 * 3600_000L) checkUpdate()
                }
                wasOnline = s.online
            }
        }
    }

    fun loadDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list = host.ensureStarted().p2pDevices()
                withContext(Dispatchers.Main) { devices = list; devicesError = null }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { devicesError = e.message }
            }
        }
    }

    fun sendTo(device: dev.marck.jsyncdrive.node.Device, uris: List<Uri>) {
        val cr = getApplication<Application>().contentResolver
        for (uri in uris) {
            val (name, _) = describe(uri)
            val mime = cr.getType(uri) ?: "application/octet-stream"
            p2p.send(device, name, mime) { cr.openInputStream(uri)!! }
        }
        message = str(R.string.msg_sending, uris.size, device.name)
    }

    fun setTrusted(id: String, on: Boolean) {
        prefs.trustedDevices = if (on) prefs.trustedDevices + id else prefs.trustedDevices - id
        trusted = prefs.trustedDevices
    }

    fun startSearch() { searching = true }

    fun stopSearch() {
        searching = false
        query = ""
        results = emptyList()
        searchJob?.cancel()
    }

    /** Looks the name up in the mounted Drive (what is open of an encrypted folder is included). */
    fun search(q: String) {
        query = q
        searchJob?.cancel()
        if (q.isBlank()) { results = emptyList(); return }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(220)
            val found = runCatching { host.api?.search(q.trim(), 100) }.getOrNull().orEmpty()
            withContext(Dispatchers.Main) { if (query == q) results = found.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() })) }
        }
    }

    fun load(quiet: Boolean = false) {
        val p = path
        if (!quiet) loadMeta()
        viewModelScope.launch(Dispatchers.IO) {
            val api = host.api ?: return@launch
            if (!host.status.value.online) return@launch
            if (!quiet) loading = true
            try {
                val list = api.ls(p)
                withContext(Dispatchers.Main) {
                    if (path == p) {
                        val sorted = list.sortedWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))
                        if (sorted != entries) entries = sorted
                        error = null
                        lockedFolder = null
                    }
                }
            } catch (e: NodeException) {
                withContext(Dispatchers.Main) {
                    if (path == p) {
                        if (e.locked) { lockedFolder = p; entries = emptyList(); error = null } else { error = e.message }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { if (path == p) error = e.message }
            } finally {
                loading = false
            }
        }
    }

    fun open(folder: String) {
        path = folder
        entries = emptyList()
        lockedFolder = null
        load()
    }

    fun up(): Boolean {
        if (path.isEmpty()) return false
        open(NodeApi.parentOf(path))
        return true
    }

    // --- sign-in ---------------------------------------------------------------------------

    fun signIn(server: String, hub: String, device: String) {
        prefs.lastServer = server
        prefs.lastHub = hub
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val api = host.ensureStarted()
                api.settings("hub" to hub.trim(), "server" to server.trim(), "device" to device.trim())
                api.loginStart(server.trim(), device.trim())
                host.refresh()
            } catch (e: Exception) {
                message = e.message
            }
        }
    }

    /** Trusts the certificate authority in the picked file (a private CA that signed the Drive's certificate). */
    fun importCertificate(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val pem = NodeHost.toPem(bytes)
                val n = host.ensureStarted().ca(pem, false)
                host.refresh()
                message = str(R.string.msg_trusting, n)
            } catch (e: Exception) {
                message = str(R.string.msg_bad_certificate, e.message ?: "")
            }
        }
    }

    fun cancelSignIn() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { host.api?.loginCancel(); host.refresh() } }
    }

    fun signOut() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.api?.logout() }
            NodeService.stop(getApplication())
            host.refresh()
            withContext(Dispatchers.Main) { path = ""; entries = emptyList() }
        }
    }

    fun applySettings(hub: String? = null, server: String? = null, readOnly: Boolean? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val kv = mutableListOf<Pair<String, Any>>()
                hub?.let { kv += "hub" to it.trim() }
                server?.let { kv += "server" to it.trim() }
                readOnly?.let { kv += "read_only" to it }
                host.api?.settings(*kv.toTypedArray())
                host.refresh()
            }.onFailure { message = it.message }
        }
    }

    fun setKeepConnected(on: Boolean) {
        prefs.keepConnected = on
        if (on) NodeService.start(getApplication()) else NodeService.stop(getApplication())
    }

    // --- encrypted folders -----------------------------------------------------------------

    fun unlock(folder: String, password: String, minutes: Int, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = try {
                host.api?.protectedOpen(folder, password, minutes * 60)
                host.refresh()
                null
            } catch (e: NodeException) {
                if (e.code == 401) str(R.string.err_wrong_password) else e.message
            } catch (e: Exception) {
                e.message
            }
            withContext(Dispatchers.Main) {
                onResult(err)
                if (err == null) { justUnlocked = folder; load() }
            }
        }
    }

    fun lock(folder: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.api?.protectedLock(folder); host.refresh() }
            withContext(Dispatchers.Main) {
                if (path == folder || path.startsWith("$folder/")) open(folder)
                load()
            }
        }
    }

    // --- changing files --------------------------------------------------------------------

    fun newFolder(name: String) {
        run(R.string.err_create_folder) { it.mkdir(NodeApi.join(path, name)) }
    }

    fun rename(e: Entry, newName: String) {
        run(R.string.err_rename) { it.mv(e.path, NodeApi.join(NodeApi.parentOf(e.path), newName)) }
    }

    fun delete(e: Entry) {
        run(R.string.err_delete) { it.rm(e.path) }
    }

    private fun run(failure: Int, block: (NodeApi) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block(host.ensureStarted())
                load(quiet = true)
            } catch (e: NodeException) {
                message = if (e.readOnly) str(R.string.err_read_only) else str(failure) + ": " + e.message
            } catch (e: Exception) {
                message = str(failure) + ": " + e.message
            }
        }
    }

    /** Uploads the documents the user picked into [folder]. */
    fun upload(uris: List<Uri>, folder: String = path, onDone: (() -> Unit)? = null) {
        val cr = getApplication<Application>().contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            val api = host.ensureStarted()
            for (uri in uris) {
                val (name, size) = describe(uri)
                val id = nextTransfer++
                transfers = transfers + Transfer(id, name)
                try {
                    val taken = api.ls(folder).map { it.name }.toSet()
                    val target = NodeApi.join(folder, uniqueName(name, taken))
                    cr.openInputStream(uri)!!.use { api.put(target, it, size, System.currentTimeMillis()) }
                    transfers = transfers.map { if (it.id == id) it.copy(done = true) else it }
                } catch (e: Exception) {
                    android.util.Log.w("jsyncupload", "upload $name: $e")
                    val msg = if (e is NodeException && e.readOnly) str(R.string.err_read_only) else e.message ?: str(R.string.err_failed)
                    transfers = transfers.map { if (it.id == id) it.copy(error = msg) else it }
                }
            }
            load(quiet = true)
            withContext(Dispatchers.Main) { onDone?.invoke() }
        }
    }

    /**
     * Opens a Drive file in another app. The file is first copied to the app's cache and handed over through a
     * FileProvider: viewers (the Huawei PDF reader, galleries) cannot read the Drive's own content addresses.
     */
    fun openExternally(e: Entry) = handOver(e, share = false)

    /** Offers a Drive file to the system share sheet (messaging, mail, other cloud apps…), as a cached copy like [openExternally]. */
    fun shareExternally(e: Entry) = handOver(e, share = true)

    private fun handOver(e: Entry, share: Boolean) {
        val app = getApplication<Application>()
        message = str(R.string.msg_opening, e.name)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = java.io.File(app.cacheDir, "open").apply { mkdirs() }
                dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 3600_000L }?.forEach { it.deleteRecursively() }
                val folder = java.io.File(dir, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
                val file = java.io.File(folder, e.name.replace('/', '_'))
                val api = host.ensureStarted()
                file.outputStream().use { api.download(e.path, it) }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val mime = dev.marck.jsyncdrive.provider.JsyncDocumentsProvider.mimeOf(e)
                val intent = if (share) {
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime)
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, e.name)
                } else {
                    android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                }
                intent.clipData = android.content.ClipData.newRawUri(e.name, uri)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val chooser = android.content.Intent.createChooser(intent, e.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                withContext(Dispatchers.Main) {
                    message = null
                    app.startActivity(chooser)
                }
            } catch (ex: Exception) {
                withContext(Dispatchers.Main) { message = str(R.string.err_open) + ": " + (ex.message ?: "") }
            }
        }
    }

    fun clearTransfers() { transfers = transfers.filter { !it.done && it.error == null } }

    private fun describe(uri: Uri): Pair<String, Long> {
        val cr = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        cr.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        return name to size
    }

    private fun uniqueName(want: String, taken: Set<String>): String {
        if (want !in taken) return want
        val dot = want.lastIndexOf('.').takeIf { it > 0 }
        val base = dot?.let { want.substring(0, it) } ?: want
        val ext = dot?.let { want.substring(it) } ?: ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }
}
