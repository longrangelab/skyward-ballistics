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

object CoriolisSync {
    const val PERIODIC_MS = 30_000L
    const val DEBOUNCE_MS = 3_000L
    const val RESUME_GAP_MS = 10_000L

    data class Status(
        val on: Boolean, val auto: Boolean, val running: Boolean, val inRange: Boolean,
        val lastSentAt: Long, val lastResult: String?, val lastOk: Boolean,
        val firmware: String?,
        val sendUsedPrivate: Boolean = false,
    )

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "coriolis-send").also { it.isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    private val loud = AtomicBoolean(false)
    private val waiting = java.util.concurrent.CopyOnWriteArrayList<(CoriolisPush.Outcome) -> Unit>()
    @Volatile private var inRange = false
    @Volatile private var baseNetwork: Network? = null
    @Volatile private var changedHere = true
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
            p.getLong(K_LAST_OK, 0L), p.getString(K_RESULT, null), p.getBoolean(K_RESULT_OK, true),
            p.getString(K_FW, null), p.getBoolean(K_SEND_PRIVATE, false))
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } } } }

    fun turnOn(c: Context) {
        init(c)
        prefs().edit().putBoolean(K_ON, true).apply()
        changedHere = true
        notifyListeners()
        schedulePeriodic()
        auto()
    }

    fun turnOff(c: Context) {
        init(c)
        main.removeCallbacks(debounced); main.removeCallbacks(periodic)
        prefs().edit().putBoolean(K_ON, false).remove(K_LAST_OK).remove(K_RESULT).remove(K_RESULT_OK).remove(K_FW)
            .remove(K_LAST_VER).remove(K_LAST_HASH).apply()
        notifyListeners()
    }

    fun setAuto(on: Boolean) { prefs().edit().putBoolean(K_AUTO, on).apply(); notifyListeners(); if (on) auto() }

    fun setSendUsedPrivate(on: Boolean) {
        prefs().edit().putBoolean(K_SEND_PRIVATE, on).apply()
        changedHere = true
        notifyListeners(); auto()
    }

    fun onLocalChange() {
        changedHere = true
        if (!isOn() || !prefs().getBoolean(K_AUTO, true)) return
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
        start(quietWhenAway = true, done = null)
    }

    fun sendNow(c: Context, done: (CoriolisPush.Outcome) -> Unit) {
        init(c)
        main.removeCallbacks(debounced)
        start(quietWhenAway = false, done = done)
    }

    private fun start(quietWhenAway: Boolean, done: ((CoriolisPush.Outcome) -> Unit)?) {
        if (done != null) waiting += done
        if (!quietWhenAway) loud.set(true)
        if (!running.compareAndSet(false, true)) { again.set(true); return }
        notifyListeners()
        io.execute {
            var o: CoriolisPush.Outcome? = null
            try {
                do {
                    again.set(false)
                    o = runOnce(quietWhenAway = !loud.getAndSet(false))
                } while (again.get() && (isOn() || waiting.isNotEmpty()))
            } finally {
                running.set(false)
                notifyListeners()
            }
            val out = o
            val callbacks = waiting.toList().also { waiting.removeAll(it) }
            if (out != null && callbacks.isNotEmpty()) main.post { callbacks.forEach { it(out) } }
        }
    }

    private fun deviceId() = prefs().getString(K_DEVICE, null) ?: "unknown"

    private fun findBase(): Pair<Network, BaseHttp>? {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val candidates = (listOfNotNull(baseNetwork) + runCatching { @Suppress("DEPRECATION") cm.allNetworks.toList() }.getOrDefault(emptyList())).distinct()
        for (n in candidates) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue
            val probe = UrlConnectionBaseHttp(CoriolisBase.DEFAULT_URL, connectTimeoutMs = 1_500, readTimeoutMs = 3_000) { u: URL -> n.openConnection(u) }
            val ok = runCatching { CoriolisBase.info(probe) }.isSuccess
            if (ok) return n to UrlConnectionBaseHttp(CoriolisBase.DEFAULT_URL) { u: URL -> n.openConnection(u) }
        }
        return null
    }

    private fun last(): CoriolisPush.Last? {
        val p = prefs()
        val h = p.getString(K_LAST_HASH, null) ?: return null
        return CoriolisPush.Last(p.getLong(K_LAST_VER, -1L), h)
    }

    private fun runOnce(quietWhenAway: Boolean): CoriolisPush.Outcome {
        prefs().edit().putLong(K_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        val found = findBase()
        if (found == null) {
            val was = inRange
            inRange = false; baseNetwork = null
            val o = CoriolisPush.Outcome.Failed("No Coriolis base station in range. Join its Wi-Fi (WindStation) and try again.")
            if (!quietWhenAway) record(o) else if (was) notifyListeners()
            return o
        }
        baseNetwork = found.first; inRange = true
        val unchanged = !changedHere
        changedHere = false
        val o = try {
            CoriolisPush.run(Stores.get(app), found.second, deviceId(), prefs().getBoolean(K_SEND_PRIVATE, false), last(), unchanged)
        } catch (e: Exception) {
            CoriolisPush.Outcome.Failed("Sending didn't finish: ${e.message ?: e.javaClass.simpleName}")
        }
        if (!o.ok) changedHere = true
        record(o)
        return o
    }

    private fun record(o: CoriolisPush.Outcome) {
        val e = prefs().edit()
        val last = when (o) { is CoriolisPush.Outcome.Sent -> o.last; is CoriolisPush.Outcome.UpToDate -> o.last; else -> null }
        if (last != null) {
            e.putLong(K_LAST_OK, System.currentTimeMillis()).putBoolean(K_RESULT_OK, true)
                .putLong(K_LAST_VER, last.ver).putString(K_LAST_HASH, last.hash)
            o.text.substringAfter("\nBase station firmware ", "").removeSuffix(".").ifEmpty { null }?.let { e.putString(K_FW, it) }
        } else e.putBoolean(K_RESULT_OK, false)
        e.putString(K_RESULT, o.text.substringBefore('\n'))
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
    private const val K_FW = "firmware"
    private const val K_SEND_PRIVATE = "sendUsedPrivate"
    private const val K_LAST_VER = "lastVer"
    private const val K_LAST_HASH = "lastHash"
}
