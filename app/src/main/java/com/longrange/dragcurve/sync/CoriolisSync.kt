package com.longrange.dragcurve.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.longrange.dragcurve.ui.calc.Stores
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps this device in step with a Coriolis Wind Lab base station while the phone
 * is on the base's Wi-Fi. Together with Google Drive sync this carries gear between
 * Drive and the base: a change pulled from one is a local change that the other
 * then sends on.
 *
 * The base's Wi-Fi has no internet, so Android usually keeps mobile data as the
 * default network. Base requests are opened on the Wi-Fi network itself
 * (Network.openConnection), and Drive keeps using the default network.
 */
object CoriolisSync {
    const val PERIODIC_MS = 30_000L
    const val DEBOUNCE_MS = 3_000L
    const val RESUME_GAP_MS = 10_000L

    data class Status(
        val on: Boolean, val auto: Boolean, val running: Boolean, val inRange: Boolean,
        val lastSyncAt: Long, val lastResult: String?, val lastOk: Boolean, val waitingForYou: Boolean,
        val firmware: String?,
    )

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "coriolis-sync").also { it.isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    @Volatile private var syncThread: Thread? = null
    @Volatile private var inRange = false
    @Volatile private var baseNetwork: Network? = null
    private var visible = 0
    private val listeners = LinkedHashSet<() -> Unit>()
    private val debounced = Runnable { auto() }
    private val periodic: Runnable = Runnable { auto(); schedulePeriodic() }

    @Synchronized fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        if (prefs().getString(K_DEVICE, null) == null) prefs().edit().putString(K_DEVICE, UUID.randomUUID().toString()).apply()
        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { if (++visible == 1) schedulePeriodic() }
            override fun onActivityPaused(a: Activity) { if (--visible <= 0) { visible = 0; main.removeCallbacks(periodic) } }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // Joining a Wi-Fi network (maybe the base's): try at once instead of waiting for the next round.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { main.postDelayed({ auto() }, 1_500L) }
                    override fun onLost(network: Network) { if (network == baseNetwork) { baseNetwork = null; inRange = false; notifyListeners() } }
                })
        }
    }

    private fun prefs(): SharedPreferences = app.getSharedPreferences("coriolis_sync", Context.MODE_PRIVATE)

    fun isOn(): Boolean = ::app.isInitialized && prefs().getBoolean(K_ON, false)

    fun status(c: Context): Status {
        init(c)
        val p = prefs()
        return Status(p.getBoolean(K_ON, false), p.getBoolean(K_AUTO, true), running.get(), inRange,
            p.getLong(K_LAST_OK, 0L), p.getString(K_RESULT, null), p.getBoolean(K_RESULT_OK, true), p.getBoolean(K_WAITING, false),
            p.getString(K_FW, null))
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } } } }

    fun turnOn(c: Context) {
        init(c)
        prefs().edit().putBoolean(K_ON, true).apply()
        notifyListeners()
        schedulePeriodic()
        auto()
    }

    fun turnOff(c: Context) {
        init(c)
        main.removeCallbacks(debounced); main.removeCallbacks(periodic)
        prefs().edit().putBoolean(K_ON, false).remove(K_LAST_OK).remove(K_RESULT).remove(K_RESULT_OK).remove(K_WAITING).remove(K_FW).apply()
        notifyListeners()
    }

    fun setAuto(on: Boolean) { prefs().edit().putBoolean(K_AUTO, on).apply(); notifyListeners(); if (on) auto() }

    fun onLocalChange() {
        if (!isOn() || !prefs().getBoolean(K_AUTO, true) || Thread.currentThread() === syncThread) return
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    fun onAppResume(c: Context) {
        init(c)
        if (visible == 0 && Looper.myLooper() == Looper.getMainLooper()) { visible = 1; schedulePeriodic() }
        if (!isOn() || !prefs().getBoolean(K_AUTO, true)) return
        if (System.currentTimeMillis() - prefs().getLong(K_LAST_ATTEMPT, 0L) < RESUME_GAP_MS) return
        auto()
    }

    private fun schedulePeriodic() {
        main.removeCallbacks(periodic)
        if (visible > 0 && isOn()) main.postDelayed(periodic, PERIODIC_MS)
    }

    private fun auto() {
        if (!isOn() || !prefs().getBoolean(K_AUTO, true)) return
        start(SharedFileSync.Options(deviceId(), lastSyncAt(), automatic = true), quietWhenAway = true, done = null)
    }

    /** Tap "Sync now". `done` runs on the main thread. */
    fun syncNow(c: Context, allowDeletions: Int = 0, done: (SharedFileSync.Outcome) -> Unit) {
        init(c)
        main.removeCallbacks(debounced)
        start(SharedFileSync.Options(deviceId(), lastSyncAt(), allowDeletions = allowDeletions), quietWhenAway = false, done = done)
    }

    private fun start(opts: SharedFileSync.Options, quietWhenAway: Boolean, done: ((SharedFileSync.Outcome) -> Unit)?) {
        if (!running.compareAndSet(false, true)) { if (done == null) again.set(true); return }
        notifyListeners()
        io.execute {
            var o: SharedFileSync.Outcome? = null
            try {
                do {
                    again.set(false)
                    o = runOnce(opts, quietWhenAway)
                } while (again.get() && done == null && isOn())
            } finally {
                running.set(false)
                notifyListeners()
            }
            val out = o
            if (done != null && out != null) main.post { done(out) }
        }
    }

    private fun deviceId() = prefs().getString(K_DEVICE, null) ?: "unknown"
    private fun lastSyncAt() = prefs().getLong(K_LAST_OK, 0L)

    /** The Wi-Fi network whose 192.168.4.1 answers /gear/info, or null. */
    private fun findBase(): Pair<Network, BaseHttp>? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val candidates = (listOfNotNull(baseNetwork) + runCatching { @Suppress("DEPRECATION") cm.allNetworks.toList() }.getOrDefault(emptyList())).distinct()
        for (n in candidates) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val probe = UrlConnectionBaseHttp(CoriolisBase.DEFAULT_URL, connectTimeoutMs = 1_500, readTimeoutMs = 3_000) { u: URL -> n.openConnection(u) }
            val ok = runCatching { CoriolisBase.info(probe) }.isSuccess
            if (ok) return n to UrlConnectionBaseHttp(CoriolisBase.DEFAULT_URL) { u: URL -> n.openConnection(u) }
        }
        return null
    }

    private fun runOnce(opts: SharedFileSync.Options, quietWhenAway: Boolean): SharedFileSync.Outcome {
        syncThread = Thread.currentThread()
        prefs().edit().putLong(K_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        try {
            val found = findBase()
            if (found == null) {
                val was = inRange
                inRange = false; baseNetwork = null
                val o = SharedFileSync.Outcome.Failed("No Coriolis base station in range. Join its Wi-Fi (Wind Lab) and try again.")
                // Out of range is normal: don't turn the status line into a warning for it.
                if (!quietWhenAway) record(o) else if (was) notifyListeners()
                return o
            }
            baseNetwork = found.first; inRange = true
            val store = Stores.get(app)
            val fw = runCatching { CoriolisBase.info(found.second).fw }.getOrNull()
            if (fw != null) prefs().edit().putString(K_FW, fw).apply()
            val o = try {
                CoriolisBaseSync.run(store, found.second, opts)
            } catch (e: Exception) {
                SharedFileSync.Outcome.Failed("The sync didn't finish: ${e.message ?: e.javaClass.simpleName}")
            }
            // Whatever this pulls from the base is saved through the store, whose
            // onDataChanged hands it on to Google Drive sync and the shared file.
            record(o)
            return o
        } finally {
            syncThread = null
        }
    }

    private fun record(o: SharedFileSync.Outcome) {
        val e = prefs().edit()
        when (o) {
            is SharedFileSync.Outcome.Done -> e.putLong(K_LAST_OK, System.currentTimeMillis()).putString(K_RESULT, o.text.substringBefore('\n'))
                .putBoolean(K_RESULT_OK, true).putBoolean(K_WAITING, false)
            is SharedFileSync.Outcome.NeedsConfirmation -> e.putString(K_RESULT, "Waiting for you: the base station would remove ${o.count} record(s) here. Tap Sync now to see what.")
                .putBoolean(K_RESULT_OK, false).putBoolean(K_WAITING, true)
            is SharedFileSync.Outcome.FileEmptied -> e.putString(K_RESULT, o.text).putBoolean(K_RESULT_OK, false)
            is SharedFileSync.Outcome.Failed -> e.putString(K_RESULT, o.text).putBoolean(K_RESULT_OK, false)
        }
        e.apply()
        notifyListeners()
    }

    private const val K_ON = "on"
    private const val K_AUTO = "auto"
    private const val K_DEVICE = "deviceId"
    private const val K_LAST_OK = "lastSyncAt"
    private const val K_LAST_ATTEMPT = "lastAttemptAt"
    private const val K_RESULT = "lastResult"
    private const val K_RESULT_OK = "lastOk"
    private const val K_WAITING = "waiting"
    private const val K_FW = "firmware"
}
