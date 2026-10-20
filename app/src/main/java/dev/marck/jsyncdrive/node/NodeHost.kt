package dev.marck.jsyncdrive.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the jsync node (a Go program shipped as libjsyncnode.so) as a child
 * process and keeps a connection to its local API. Everything in the app that
 * needs the Drive — the documents provider, the screens, the share target —
 * goes through here, so there is one node per app process.
 */
class NodeHost private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(NodeStatus())
    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 512)

    /** The node's state, refreshed whenever it reports a change of state. */
    val status: StateFlow<NodeStatus> = _status.asStateFlow()

    /** Every event the node reports (remote changes, state, encrypted folders opening/locking). */
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    @Volatile private var proc: Process? = null
    @Volatile private var current: NodeApi? = null
    private var eventThread: Thread? = null
    private var netCallbackSet = false

    /** Folders of this phone kept in step with folders of the Drive. */
    val folderSync = dev.marck.jsyncdrive.sync.FolderSync(app, this).also { it.start() }

    /** Device-to-device transfers (screens, notifications). */
    val p2p = P2PHub(app, this).also { it.start() }

    /** The last thing that went wrong in the background, for a screen to show once. */
    val lastError = MutableStateFlow<String?>(null)

    /** The API of the running node, or null if it is not running. */
    val api: NodeApi? get() = current

    /** Starts the node if it is not running and returns its API. Blocks until it answers. */
    @Synchronized
    fun ensureStarted(): NodeApi {
        current?.let { api -> if (proc?.isAlive == true) return api }
        stop()
        val bin = File(app.applicationInfo.nativeLibraryDir, "libjsyncnode.so")
        check(bin.exists()) { "the node is not part of this build (${bin.path})" }
        val data = File(app.filesDir, "node").apply { mkdirs() }
        val cache = File(app.cacheDir, "node").apply { mkdirs() }
        val tmp = File(app.cacheDir, "tmp").apply { mkdirs() }
        val token = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        val cmd = mutableListOf(bin.path, "--data", data.path, "--cache", cache.path, "--exit-on-stdin-eof")
        val dns = currentDns()
        if (dns.isNotEmpty()) cmd += listOf("--dns", dns.joinToString(","))
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            put("JSYNC_NODE_TOKEN", token)
            put("HOME", app.filesDir.path)
            put("TMPDIR", tmp.path)
            put("GOMEMLIMIT", "384MiB")
            // Android has no D-Bus: without this the credential-store lookup tries to launch one.
            put("DBUS_SESSION_BUS_ADDRESS", "unix:path=/nonexistent")
        }
        val p = pb.start()
        val reader = p.inputStream.bufferedReader()

        // The first line the node prints is "READY <port>"; anything before it is a failure message.
        var port = 0
        val failure = StringBuilder()
        val waiter = thread(name = "node-ready", isDaemon = true) {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("READY ")) { port = line.removePrefix("READY ").trim().toIntOrNull() ?: 0; break }
                failure.appendLine(line)
                Log.w(TAG, line)
            }
        }
        waiter.join(15_000)
        if (port == 0) {
            p.destroy()
            error("the node did not start: ${failure.toString().trim().ifEmpty { "no answer" }}")
        }
        thread(name = "node-log", isDaemon = true) {
            runCatching { reader.forEachLine { Log.i(TAG, it) } }
            Log.w(TAG, "node output closed")
        }
        proc = p
        val api = NodeApi(port, token)
        current = api
        thread(name = "node-wait", isDaemon = true) {
            p.waitFor()
            Log.w(TAG, "node exited with ${p.exitValue()}")
            synchronized(this) { if (proc === p) { proc = null; current = null } }
            _status.value = NodeStatus(state = "stopped", detail = "the node stopped")
        }
        startEventLoop(api, p)
        watchNetwork()
        scope.launch { pushNetwork(cm.activeNetwork?.let { cm.getLinkProperties(it) }) } // every new node needs telling
        scope.launch { pushUserCertificates() }
        return api
    }

    /** Stops the node. */
    @Synchronized
    fun stop() {
        val p = proc
        proc = null
        current = null
        eventThread?.interrupt()
        eventThread = null
        if (p != null && p.isAlive) {
            p.destroy()
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Waits until the Drive is mounted; returns the last status either way. */
    fun awaitOnline(timeoutMs: Long): NodeStatus {
        val api = ensureStarted()
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val s = runCatching { api.status() }.getOrNull()
            if (s != null) _status.value = s
            if (s != null && (s.online || s.state == "logged_out" || s.state == "needs_setup" || s.state == "pending")) return s
            if (System.currentTimeMillis() >= end) return _status.value
            Thread.sleep(150)
        }
    }

    /** Refreshes [status] now. */
    fun refresh(): NodeStatus? = runCatching { current?.status() }.getOrNull()?.also { _status.value = it }

    private fun startEventLoop(api: NodeApi, p: Process) {
        eventThread?.interrupt()
        eventThread = thread(name = "node-events", isDaemon = true) {
            var since = 0L
            refresh()
            while (!Thread.currentThread().isInterrupted && p.isAlive) {
                try {
                    api.events(since) { ev ->
                        since = ev.seq
                        _events.tryEmit(ev)
                        if (ev.kind != "change") refresh()
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                    if (!p.isAlive) return@thread
                    Log.w(TAG, "event stream: ${e.message}")
                }
                try { Thread.sleep(1000) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    // --- DNS: Android has no resolv.conf; the node needs the servers of the network that is up ---

    private val cm get() = app.getSystemService(ConnectivityManager::class.java)

    private fun currentDns(): List<String> {
        val net = cm.activeNetwork ?: return emptyList()
        return dnsOf(cm.getLinkProperties(net))
    }

    private fun dnsOf(lp: LinkProperties?): List<String> =
        lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }.orEmpty()

    /**
     * The certificates a user installed on the phone (to trust a home server's own authority) live in
     * Android's store, which the Go node cannot read: hand them over.
     */
    private fun pushUserCertificates() {
        val api = current ?: return
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val pem = StringBuilder()
            for (alias in ks.aliases()) {
                if (!alias.startsWith("user:")) continue
                val cert = ks.getCertificate(alias) ?: continue
                pem.append(certToPem(cert.encoded))
            }
            api.ca(pem.toString(), true)
            refresh() // the status carries how many authorities are trusted
        }.onFailure { Log.w(TAG, "user certificates: ${it.message}") }
    }

    private fun addrsOf(lp: LinkProperties?): List<String> =
        lp?.linkAddresses?.mapNotNull { a -> a.address.hostAddress?.substringBefore('%')?.let { "$it/${a.prefixLength}" } }.orEmpty()

    /** Gives the node what it cannot find out itself on Android: DNS servers and interface addresses. */
    private fun pushNetwork(lp: LinkProperties?) {
        val api = current ?: return
        runCatching { api.dns(dnsOf(lp)) }
        runCatching { api.net(lp?.interfaceName ?: "default", addrsOf(lp)) }
    }

    private fun watchNetwork() {
        if (netCallbackSet) return
        netCallbackSet = true
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    scope.launch { pushNetwork(lp) }
                }
            })
        }.onFailure { Log.w(TAG, "network callback: ${it.message}") }
    }

    companion object {
        private const val TAG = "jsyncnode"

        fun certToPem(der: ByteArray): String =
            "-----BEGIN CERTIFICATE-----\n" + android.util.Base64.encodeToString(der, android.util.Base64.DEFAULT) + "-----END CERTIFICATE-----\n"

        /** A certificate file as PEM, whether the user picked a PEM or a DER (.cer/.crt) file. */
        fun toPem(bytes: ByteArray): String {
            val text = String(bytes, Charsets.ISO_8859_1)
            if (text.contains("-----BEGIN CERTIFICATE-----")) return text
            val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream())
            return certToPem(cert.encoded)
        }

        @Volatile private var instance: NodeHost? = null

        fun get(context: Context): NodeHost =
            instance ?: synchronized(this) { instance ?: NodeHost(context.applicationContext).also { instance = it } }
    }
}
