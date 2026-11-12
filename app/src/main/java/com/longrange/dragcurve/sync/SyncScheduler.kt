package com.longrange.dragcurve.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.longrange.dragcurve.ui.calc.Stores
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SyncScheduler {
    const val DEBOUNCE_MS = 5_000L
    const val RESUME_GAP_MS = 30_000L
    const val PERIODIC_MS = 120_000L

    data class Status(
        val enabled: Boolean,
        val auto: Boolean,
        val email: String?,
        val running: Boolean,
        val lastSyncAt: Long,
        val lastResult: String?,
        val lastOk: Boolean,
        val needsSignIn: Boolean,
    )

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "drive-sync").also { it.isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val again = AtomicBoolean(false)
    @Volatile private var syncThread: Thread? = null
    @Volatile private var waitingForNetwork = false
    private val listeners = LinkedHashSet<() -> Unit>()
    private val debounced = Runnable { request(manual = false) }
    private var visible = 0
    private val periodic: Runnable = Runnable {
        if (visible > 0 && isEnabled() && prefs().getBoolean(K_AUTO, true) && online()) request(manual = false)
        schedulePeriodic()
    }

    private fun schedulePeriodic() {
        main.removeCallbacks(periodic)
        if (visible > 0) main.postDelayed(periodic, PERIODIC_MS)
    }

    @Synchronized fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
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
            val cm = app.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (waitingForNetwork && isEnabled()) main.postDelayed({ request(manual = false) }, 2_000)
                }
            })
        }
    }

    private fun prefs(): SharedPreferences = app.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = ::app.isInitialized && prefs().getBoolean(K_ENABLED, false)

    fun status(): Status {
        val p = prefs()
        return Status(
            enabled = p.getBoolean(K_ENABLED, false), auto = p.getBoolean(K_AUTO, true), email = p.getString(K_EMAIL, null),
            running = running.get(), lastSyncAt = p.getLong(K_LAST_OK, 0L), lastResult = p.getString(K_RESULT, null),
            lastOk = p.getBoolean(K_RESULT_OK, true), needsSignIn = p.getBoolean(K_NEEDS_SIGN_IN, false),
        )
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() {
        main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } } }
    }

    fun onLocalChange() {
        if (!isEnabled() || Thread.currentThread() === syncThread) return
        if (!prefs().getBoolean(K_AUTO, true)) return
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    fun onAppResume() {
        if (visible == 0 && Looper.myLooper() == Looper.getMainLooper()) { visible = 1; schedulePeriodic() }
        if (!isEnabled() || !prefs().getBoolean(K_AUTO, true)) return
        if (System.currentTimeMillis() - prefs().getLong(K_LAST_ATTEMPT, 0L) < RESUME_GAP_MS) return
        request(manual = false)
    }

    fun syncNow() = request(manual = true)

    fun signedIn(email: String?) {
        prefs().edit().putBoolean(K_ENABLED, true).putBoolean(K_NEEDS_SIGN_IN, false)
            .also { e -> if (!email.isNullOrBlank()) e.putString(K_EMAIL, email) }.apply()
        notifyListeners()
        request(manual = true)
    }

    fun setAuto(on: Boolean) {
        prefs().edit().putBoolean(K_AUTO, on).apply()
        if (on) request(manual = false)
        notifyListeners()
    }

    fun turnOff(revoke: Boolean) {
        val email = prefs().getString(K_EMAIL, null)
        main.removeCallbacks(debounced)
        waitingForNetwork = false
        prefs().edit().putBoolean(K_ENABLED, false).remove(K_EMAIL).remove(K_RESULT).remove(K_RESULT_OK)
            .remove(K_LAST_OK).remove(K_LAST_ATTEMPT).remove(K_NEEDS_SIGN_IN).apply()
        if (revoke) io.execute { DriveAuth.revoke(app, email) }
        notifyListeners()
    }

    private fun request(manual: Boolean) {
        if (!isEnabled()) return
        if (!online()) {
            waitingForNetwork = true
            record(ok = false, text = "Offline — will sync when online", success = false)
            return
        }
        if (!running.compareAndSet(false, true)) { again.set(true); return }
        notifyListeners()
        io.execute {
            try {
                do { again.set(false); runOnce() } while (again.get() && isEnabled())
            } finally {
                running.set(false)
                notifyListeners()
            }
        }
    }

    private fun runOnce() {
        syncThread = Thread.currentThread()
        prefs().edit().putLong(K_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        try {
            val p = prefs()
            val email = p.getString(K_EMAIL, null)
            var token = DriveAuth.tokenSilently(app, email)
            val engine = SyncEngine(p.getString(K_DEVICE, null) ?: "unknown")
            val store = Stores.get(app)
            fun remote(t: String) = DriveAppDataFile({ t })
            val result = try {
                engine.sync(store, remote(token), p.getLong(K_LAST_OK, 0L))
            } catch (e: RemoteAuthException) {
                DriveAuth.clearToken(app, token)
                token = DriveAuth.tokenSilently(app, email)
                engine.sync(store, remote(token), p.getLong(K_LAST_OK, 0L))
            }
            if (email.isNullOrBlank()) remote(token).accountEmail()?.let { prefs().edit().putString(K_EMAIL, it).apply() }
            if (result.ok) {
                waitingForNetwork = false
                record(ok = true, text = summary(result), success = true)
            } else if (result.networkError) {
                waitingForNetwork = true
                record(ok = false, text = "Offline — will sync when online", success = false)
            } else record(ok = false, text = "Failed: ${result.error}", success = false)
        } catch (e: DriveAuth.SignInRequiredException) {
            prefs().edit().putBoolean(K_NEEDS_SIGN_IN, true).apply()
            record(ok = false, text = "Sign-in needed — tap \"Sign in to Google Drive\"", success = false)
        } catch (e: RemoteAuthException) {
            prefs().edit().putBoolean(K_NEEDS_SIGN_IN, true).apply()
            record(ok = false, text = "Sign-in needed — ${e.message}", success = false)
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            if (cause is java.io.IOException || !online()) {
                waitingForNetwork = true
                record(ok = false, text = "Offline — will sync when online", success = false)
            } else record(ok = false, text = "Failed: " + DriveAuth.explain(cause), success = false)
        } finally {
            syncThread = null
        }
    }

    private fun summary(r: SyncResult): String {
        val parts = mutableListOf("${r.pulled} pulled", "${r.pushed} pushed")
        if (r.conflicts > 0) parts += "${r.conflicts} conflict${if (r.conflicts == 1) "" else "s"} (newest kept)"
        if (r.messages.isNotEmpty()) parts += r.messages.first()
        return parts.joinToString(", ")
    }

    private fun record(ok: Boolean, text: String, success: Boolean) {
        if (!isEnabled()) return
        val e = prefs().edit().putString(K_RESULT, text).putBoolean(K_RESULT_OK, ok)
        if (success) e.putLong(K_LAST_OK, System.currentTimeMillis()).putBoolean(K_NEEDS_SIGN_IN, false)
        e.apply()
        notifyListeners()
    }

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private const val K_ENABLED = "enabled"
    private const val K_AUTO = "auto"
    private const val K_EMAIL = "email"
    private const val K_DEVICE = "deviceId"
    private const val K_LAST_OK = "lastSyncAt"
    private const val K_LAST_ATTEMPT = "lastAttemptAt"
    private const val K_RESULT = "lastResult"
    private const val K_RESULT_OK = "lastOk"
    private const val K_NEEDS_SIGN_IN = "needsSignIn"
}
