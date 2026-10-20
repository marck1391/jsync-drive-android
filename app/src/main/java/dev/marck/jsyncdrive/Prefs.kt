package dev.marck.jsyncdrive

import android.content.Context

/** Settings that only concern the app (the node keeps its own). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Keep the node running in the background (foreground service with a notification). */
    var keepConnected: Boolean
        get() = sp.getBoolean("keep_connected", true)
        set(v) = sp.edit().putBoolean("keep_connected", v).apply()

    /** The last Drive and hub addresses typed in, to prefill the sign-in form. */
    var lastServer: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    /** Devices whose transfer requests are accepted without asking. */
    var trustedDevices: Set<String>
        get() = sp.getStringSet("trusted", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("trusted", v).apply()

    /** Files shown as a grid of previews (true) or as a list. */
    var gridView: Boolean
        get() = sp.getBoolean("grid_view", true)
        set(v) = sp.edit().putBoolean("grid_view", v).apply()

    /** When the Drive was last asked for a newer build (epoch ms). */
    var lastUpdateCheck: Long
        get() = sp.getLong("update_check", 0L)
        set(v) = sp.edit().putLong("update_check", v).apply()

    /** The files received from other devices (JSON), newest first: what the "received" window and list show. */
    var receivedJson: String
        get() = sp.getString("received", "[]") ?: "[]"
        set(v) = sp.edit().putString("received", v).apply()

    var lastHub: String
        get() = sp.getString("hub", "") ?: ""
        set(v) = sp.edit().putString("hub", v).apply()
}
