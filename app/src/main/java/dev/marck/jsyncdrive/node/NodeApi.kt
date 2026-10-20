package dev.marck.jsyncdrive.node

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An error answer from the node. [code] is the HTTP status (404 = not found, 423 = locked, 403 = read-only, 503 = not mounted). */
class NodeException(val code: Int, message: String) : IOException(message) {
    /** The file or folder is not there (as opposed to the node not knowing the request at all, which is also a 404). */
    val notFound get() = code == 404 && message == "not found"
    val locked get() = code == 423
    val readOnly get() = code == 403
    val notMounted get() = code == 503
}

data class Entry(
    val name: String,
    val dir: Boolean,
    val size: Long,
    val mtime: Long,
    val protected: Boolean,
    val locked: Boolean,
    /** Full Drive path, no leading slash. Set by [NodeApi.ls] and [NodeApi.search]. */
    val path: String,
)

data class LoginInfo(val userCode: String, val uri: String, val expiresAt: Long, val error: String)

data class ProtectedFolder(val path: String, val open: Boolean)

data class NodeStatus(
    val state: String = "starting",
    val detail: String = "",
    val email: String = "",
    val hub: String = "",
    val server: String = "",
    val device: String = "",
    val readOnly: Boolean = false,
    val readWrite: Boolean = false,
    val machineId: String = "",
    val login: LoginInfo? = null,
    val protectedFolders: List<ProtectedFolder> = emptyList(),
    val seq: Long = 0,
    /** Certificate authorities trusted in addition to Android's own (user-installed ones and imported ones). */
    val extraCas: Int = 0,
) {
    val online get() = state == "online"
    val loggedIn get() = email.isNotEmpty()
}

/** A folder shared with another person: [permission] is "read" or "write". */
data class DriveShare(val id: String, val path: String, val email: String, val permission: String)

/** Another device of the account. */
data class Device(val id: String, val name: String, val online: Boolean)

/** A file going to, or coming from, another device. */
data class Transfer(
    val id: String,
    val direction: String, // send | receive
    val peerId: String,
    val peerName: String,
    val name: String,
    val size: Long,
    val mime: String,
    val status: String, // waiting | incoming | connecting | active | done | error | declined | cancelled
    val bytes: Long,
    val speed: Double,
    val note: String,
    val error: String,
    val route: String,
    val path: String,
) {
    val finished get() = status == "done" || status == "error" || status == "declined" || status == "cancelled"
    val send get() = direction == "send"
}

/** One thing the node reports: a change of the Drive, or of the node itself. */
data class NodeEvent(
    val seq: Long,
    val kind: String,
    val op: String,
    val path: String,
    val old: String,
    val state: String,
    val detail: String,
    /** Set on kind == "p2p": the transfer that changed (null when state == "dismissed"). */
    val transfer: Transfer? = null,
)

/** Client of the node's local HTTP API (see internal/androidnode/server.go). */
class NodeApi(private val port: Int, private val token: String) {

    private fun url(path: String, vararg q: Pair<String, String>): URL {
        val sb = StringBuilder("http://127.0.0.1:").append(port).append(path)
        q.forEachIndexed { i, (k, v) ->
            sb.append(if (i == 0) '?' else '&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        return URL(sb.toString())
    }

    private fun open(u: URL, method: String, timeoutMs: Int = 60_000): HttpURLConnection =
        (u.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun fail(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val body = (c.errorStream ?: c.inputStream)?.use { String(it.readBytes()) }.orEmpty()
        val msg = runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "HTTP $code"
        c.disconnect()
        throw NodeException(code, msg)
    }

    private fun json(c: HttpURLConnection): JSONObject {
        if (c.responseCode !in 200..299) fail(c)
        val text = c.inputStream.use { String(it.readBytes()) }
        c.disconnect()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun call(method: String, path: String, vararg q: Pair<String, String>, body: JSONObject? = null): JSONObject {
        val c = open(url(path, *q), method)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        return json(c)
    }

    fun status(): NodeStatus = parseStatus(call("GET", "/v1/status"))

    fun ls(path: String): List<Entry> {
        val arr = call("GET", "/v1/ls", "path" to path).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { entry(arr.getJSONObject(it), path) }
    }

    fun stat(path: String): Entry = entry(call("GET", "/v1/stat", "path" to path), parentOf(path), path)

    /** Every file and folder under [path], flat, with paths relative to it. */
    fun tree(path: String): List<Entry> {
        val arr = call("GET", "/v1/tree", "path" to path).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); entry(o, "", o.getString("path")) }
    }

    fun search(q: String, limit: Int = 100): List<Entry> {
        val arr = call("GET", "/v1/search", "q" to q, "limit" to limit.toString()).optJSONArray("entries") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); entry(o, parentOf(o.getString("path")), o.getString("path")) }
    }

    /** Reads up to [len] bytes of [path] from [offset] into [buf]. Returns the count (0 at the end of the file). */
    fun read(path: String, offset: Long, buf: ByteArray, len: Int): Int {
        val c = open(url("/v1/file", "path" to path), "GET")
        c.setRequestProperty("Range", "bytes=$offset-${offset + len - 1}")
        val code = c.responseCode
        if (code == 416) { c.disconnect(); return 0 } // past the end
        if (code != 200 && code != 206) fail(c)
        var got = 0
        c.inputStream.use { ins ->
            // A node that ignored the Range (200) sends the whole file: skip to the offset.
            if (code == 200 && offset > 0) ins.skipFully(offset)
            while (got < len) {
                val n = ins.read(buf, got, len - got)
                if (n < 0) break
                got += n
            }
        }
        c.disconnect()
        return got
    }

    /** Streams the whole file to [out]. */
    fun download(path: String, out: java.io.OutputStream) {
        val c = open(url("/v1/file", "path" to path), "GET", timeoutMs = 10 * 60_000)
        if (c.responseCode != 200) fail(c)
        c.inputStream.use { it.copyTo(out) }
        c.disconnect()
    }

    /** Replaces (or creates) [path] with what [input] delivers. [length] < 0 when unknown. */
    fun put(path: String, input: InputStream, length: Long = -1, mtime: Long = 0) {
        val q = mutableListOf("path" to path)
        if (mtime > 0) q += "mtime" to mtime.toString()
        val c = open(url("/v1/file", *q.toTypedArray()), "PUT", timeoutMs = 10 * 60_000)
        c.doOutput = true
        if (length >= 0) c.setFixedLengthStreamingMode(length) else c.setChunkedStreamingMode(256 * 1024)
        c.outputStream.use { input.copyTo(it, 256 * 1024) }
        json(c)
    }

    fun mkdir(path: String) { call("POST", "/v1/mkdir", "path" to path) }
    fun rm(path: String) { call("POST", "/v1/rm", "path" to path) }
    fun mv(from: String, to: String) { call("POST", "/v1/mv", "from" to from, "to" to to) }

    fun loginStart(server: String, device: String): LoginInfo =
        parseLogin(call("POST", "/v1/login/start", body = JSONObject().put("server", server).put("device", device)))

    fun loginCancel() { call("POST", "/v1/login/cancel") }
    fun logout() { call("POST", "/v1/logout") }

    fun settings(vararg kv: Pair<String, Any>): NodeStatus {
        val o = JSONObject()
        kv.forEach { (k, v) -> o.put(k, v) }
        return parseStatus(call("POST", "/v1/settings", body = o))
    }

    /** Tells the node the addresses of the network that is up (Go cannot list interfaces on Android). */
    fun net(name: String, addrs: List<String>) {
        val iface = JSONObject().put("name", name).put("addrs", JSONArray(addrs))
        call("POST", "/v1/net", body = JSONObject().put("interfaces", JSONArray().put(iface)))
    }

    /**
     * Tells the node to trust the certificate authorities in [pem]. [auto] marks the ones read from Android's
     * store (replaced at every start); the others are imported by the user and kept by the node.
     */
    fun ca(pem: String, auto: Boolean): Int =
        call("POST", "/v1/ca", body = JSONObject().put("pem", pem).put("auto", auto)).optInt("added")

    fun dns(servers: List<String>) { call("POST", "/v1/dns", body = JSONObject().put("servers", JSONArray(servers))) }

    fun protectedOpen(folder: String, password: String, forSeconds: Int = 0): NodeStatus =
        parseStatus(call("POST", "/v1/protected/open", body = JSONObject().put("folder", folder).put("password", password).put("for_seconds", forSeconds)))

    fun protectedLock(folder: String): NodeStatus =
        parseStatus(call("POST", "/v1/protected/lock", body = JSONObject().put("folder", folder)))

    fun protectedClean(folder: String): Long =
        call("POST", "/v1/protected/clean", body = JSONObject().put("folder", folder)).optLong("freed")

    /**
     * Blocks, delivering events to [onEvent] (those after [since] first), until the
     * connection ends or [onEvent] throws. The caller reconnects.
     */
    fun events(since: Long, onEvent: (NodeEvent) -> Unit) {
        val c = open(url("/v1/events", "since" to since.toString()), "GET", timeoutMs = 0)
        if (c.responseCode != 200) fail(c)
        c.inputStream.bufferedReader().use { r ->
            var data: String? = null
            while (true) {
                val line = r.readLine() ?: break
                when {
                    line.startsWith("data: ") -> data = line.substring(6)
                    line.isEmpty() && data != null -> {
                        val o = JSONObject(data!!)
                        data = null
                        onEvent(
                            NodeEvent(
                                o.optLong("seq"), o.optString("kind"), o.optString("op"), o.optString("path"),
                                o.optString("old"), o.optString("state"), o.optString("detail"),
                                o.optJSONObject("transfer")?.let(::parseTransfer),
                            ),
                        )
                    }
                }
            }
        }
        c.disconnect()
    }

    fun p2pDevices(): List<Device> {
        val arr = call("GET", "/v1/p2p/devices").optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); Device(o.getString("id"), o.optString("name"), o.optBoolean("online")) }
    }

    fun p2pTransfers(): List<Transfer> {
        val arr = call("GET", "/v1/p2p/transfers").optJSONArray("transfers") ?: JSONArray()
        return (0 until arr.length()).map { parseTransfer(arr.getJSONObject(it)) }
    }

    fun p2pSend(to: String, peerName: String, name: String, mime: String, path: String): String =
        call("POST", "/v1/p2p/send", body = JSONObject().put("to", to).put("peer_name", peerName).put("name", name).put("mime", mime).put("path", path)).getString("id")

    fun p2pAccept(id: String) { call("POST", "/v1/p2p/accept", body = JSONObject().put("id", id)) }
    fun p2pDecline(id: String) { call("POST", "/v1/p2p/decline", body = JSONObject().put("id", id)) }
    fun p2pCancel(id: String) { call("POST", "/v1/p2p/cancel", body = JSONObject().put("id", id)) }
    fun p2pDismiss(id: String) { call("POST", "/v1/p2p/dismiss", body = JSONObject().put("id", id)) }

    // --- the account's folder colours, pinned folders and shares (kept by the Drive server) -------------------

    private fun drive(method: String, path: String, body: JSONObject? = null): String {
        val c = open(url("/v1/drive"), "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val req = JSONObject().put("method", method).put("path", path)
        if (body != null) req.put("body", body)
        c.outputStream.use { it.write(req.toString().toByteArray()) }
        if (c.responseCode !in 200..299) fail(c)
        val text = c.inputStream.use { String(it.readBytes()) }
        c.disconnect()
        return text
    }

    /** Folder path to its colour (one of the palette's hex values). */
    fun folderColors(): Map<String, String> {
        val o = JSONObject(drive("GET", "/api/v1/folder-colors"))
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** An empty [color] puts the folder back to the default. */
    fun setFolderColor(path: String, color: String) {
        drive("POST", "/api/v1/folder-colors", JSONObject().put("path", path).put("color", color))
    }

    fun pins(): Set<String> {
        val a = JSONArray(drive("GET", "/api/v1/pins"))
        return (0 until a.length()).map { a.getString(it) }.toSet()
    }

    fun setPinned(path: String, pinned: Boolean) {
        drive(if (pinned) "POST" else "DELETE", "/api/v1/pins", JSONObject().put("path", path))
    }

    /** The shares the account has made (a folder with another person). */
    fun shares(): List<DriveShare> {
        val a = JSONObject(drive("GET", "/api/v1/shares")).optJSONArray("owned") ?: JSONArray()
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DriveShare(o.getString("share_id"), o.getString("path"), o.optString("shared_with_email"), o.optString("permission"))
        }
    }

    fun createShare(path: String, email: String, permission: String) {
        drive("POST", "/api/v1/shares", JSONObject().put("path", path).put("email", email).put("permission", permission))
    }

    fun removeShare(id: String) {
        drive("DELETE", "/api/v1/shares", JSONObject().put("share_id", id))
    }

    private fun entry(o: JSONObject, parent: String, fullPath: String? = null): Entry {
        val name = o.getString("name")
        return Entry(
            name = name,
            dir = o.optBoolean("dir"),
            size = o.optLong("size"),
            mtime = o.optLong("mtime"),
            protected = o.optBoolean("protected"),
            locked = o.optBoolean("locked"),
            path = fullPath ?: join(parent, name),
        )
    }

    private fun parseTransfer(o: JSONObject) = Transfer(
        id = o.getString("id"), direction = o.optString("direction"), peerId = o.optString("peer_id"),
        peerName = o.optString("peer_name"), name = o.optString("name"), size = o.optLong("size"),
        mime = o.optString("mime"), status = o.optString("status"), bytes = o.optLong("bytes"),
        speed = o.optDouble("speed", 0.0), note = o.optString("note"), error = o.optString("error"),
        route = o.optString("route"), path = o.optString("path"),
    )

    private fun parseLogin(o: JSONObject) = LoginInfo(o.optString("user_code"), o.optString("uri"), o.optLong("expires_at"), o.optString("error"))

    private fun parseStatus(o: JSONObject): NodeStatus {
        val s = o.optJSONObject("settings")
        val prot = o.optJSONArray("protected")
        return NodeStatus(
            state = o.optString("state"),
            detail = o.optString("detail"),
            email = o.optString("email"),
            hub = s?.optString("hub").orEmpty(),
            server = s?.optString("server").orEmpty(),
            device = s?.optString("device").orEmpty(),
            readOnly = s?.optBoolean("read_only") ?: false,
            readWrite = o.optBoolean("read_write"),
            machineId = o.optString("machine_id"),
            login = o.optJSONObject("login")?.let(::parseLogin),
            protectedFolders = prot?.let { a -> (0 until a.length()).map { ProtectedFolder(a.getJSONObject(it).getString("path"), a.getJSONObject(it).optBoolean("open")) } }.orEmpty(),
            seq = o.optLong("seq"),
            extraCas = o.optInt("extra_cas"),
        )
    }

    companion object {
        fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
        fun parentOf(path: String) = path.substringBeforeLast('/', "")
        fun nameOf(path: String) = path.substringAfterLast('/')
    }
}

private fun InputStream.skipFully(n: Long) {
    var left = n
    val sink = ByteArray(8192)
    while (left > 0) {
        val s = skip(left)
        if (s > 0) { left -= s; continue }
        val r = read(sink, 0, minOf(sink.size.toLong(), left).toInt())
        if (r < 0) return
        left -= r
    }
}

