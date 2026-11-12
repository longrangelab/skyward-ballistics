package com.longrange.dragcurve.sync

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.ui.calc.Stores
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SafFileAccess(private val c: Context, private val uri: Uri) : SharedFileAccess {
    override val name: String by lazy { displayName(c, uri) }

    override fun read(): String {
        val cr = c.contentResolver
        runCatching { cr.refresh(uri, null, null) }
        try {
            val bytes = (cr.openInputStream(uri) ?: throw FileNotFoundException("no stream"))
                .use { LibraryIngest.readLimited(it, LibraryIngest.MAX_BYTES) }
                ?: throw SharedFileUnreachableException("The shared file is larger than ${LibraryIngest.MAX_BYTES / (1024 * 1024)} MB.")
            return LibraryIngest.decode(bytes)
        } catch (e: SecurityException) {
            throw lost()
        } catch (e: FileNotFoundException) {
            throw lost()
        } catch (e: IllegalArgumentException) {
            throw lost()
        }
    }

    override fun write(text: String) {
        val cr = c.contentResolver
        val data = text.toByteArray(Charsets.UTF_8)
        try {
            val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull() ?: cr.openOutputStream(uri, "w")
                ?: throw SharedFileUnreachableException("The shared file can't be written.")
            out.use { it.write(data); it.flush() }
        } catch (e: SecurityException) {
            throw SharedFileUnreachableException("Skyward Ballistics isn't allowed to change the shared file. Choose it again (Backup & sync → Change shared file…).")
        } catch (e: FileNotFoundException) {
            throw lost()
        }
        if (read() != text) throw SharedFileUnreachableException("The shared file was not saved completely. Tap Sync again.")
    }

    private fun lost() = SharedFileUnreachableException(
        "Skyward Ballistics can't open the shared file any more: it may have been moved, renamed or deleted, or access to it was lost. Choose it again (Backup & sync → Change shared file…).")

    companion object {
        fun displayName(c: Context, uri: Uri): String = runCatching {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared file"
    }
}

object SharedSync {
    const val DEBOUNCE_MS = 20_000L
    const val RESUME_GAP_MS = 60_000L

    data class Status(
        val on: Boolean, val fileName: String, val auto: Boolean, val running: Boolean,
        val lastSyncAt: Long, val lastResult: String?, val lastOk: Boolean, val waitingForYou: Boolean,
    )

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "shared-file-sync").also { it.isDaemon = true } }
    private val running = AtomicBoolean(false)
    @Volatile private var syncThread: Thread? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private val debounced = Runnable { auto() }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        if (prefs().getString(K_DEVICE, null) == null) prefs().edit().putString(K_DEVICE, UUID.randomUUID().toString()).apply()
    }

    private fun prefs(): SharedPreferences = app.getSharedPreferences("shared_file_sync", Context.MODE_PRIVATE)

    fun isOn(): Boolean = ::app.isInitialized && prefs().getString(K_URI, null) != null

    fun status(c: Context): Status {
        init(c)
        val p = prefs()
        return Status(p.getString(K_URI, null) != null, p.getString(K_NAME, "") ?: "", p.getBoolean(K_AUTO, true), running.get(),
            p.getLong(K_LAST_OK, 0L), p.getString(K_RESULT, null), p.getBoolean(K_RESULT_OK, true), p.getBoolean(K_WAITING, false))
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } } } }

    fun choose(c: Context, uri: Uri) {
        init(c)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(uri, flags)
        val old = prefs().getString(K_URI, null)
        if (old != null && old != uri.toString()) runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        val e = prefs().edit().putString(K_URI, uri.toString()).putString(K_NAME, SafFileAccess.displayName(app, uri)).remove(K_WAITING)
        if (old != uri.toString()) e.remove(K_LAST_OK).remove(K_RESULT).remove(K_RESULT_OK)
        e.apply()
        notifyListeners()
    }

    fun turnOff(c: Context) {
        init(c)
        main.removeCallbacks(debounced)
        prefs().getString(K_URI, null)?.let { u ->
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs().edit().remove(K_URI).remove(K_NAME).remove(K_LAST_OK).remove(K_RESULT).remove(K_RESULT_OK).remove(K_WAITING).apply()
        notifyListeners()
    }

    fun setAuto(on: Boolean) { prefs().edit().putBoolean(K_AUTO, on).apply(); notifyListeners() }

    fun onLocalChange() {
        if (!isOn() || !prefs().getBoolean(K_AUTO, true) || Thread.currentThread() === syncThread) return
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    fun onAppResume(c: Context) {
        init(c)
        if (!isOn() || !prefs().getBoolean(K_AUTO, true)) return
        if (System.currentTimeMillis() - prefs().getLong(K_LAST_ATTEMPT, 0L) < RESUME_GAP_MS) return
        auto()
    }

    private fun auto() {
        if (!isOn() || !running.compareAndSet(false, true)) return
        notifyListeners()
        io.execute {
            try { runOnce(SharedFileSync.Options(deviceId(), lastSyncAt(), automatic = true)) } finally { running.set(false); notifyListeners() }
        }
    }

    fun syncNow(c: Context, allowDeletions: Int = 0, allowEmptyFile: Boolean = false, done: (SharedFileSync.Outcome) -> Unit) {
        init(c)
        if (!isOn()) { done(SharedFileSync.Outcome.Failed("Choose the shared file first.")); return }
        main.removeCallbacks(debounced)
        io.execute {
            running.set(true)
            notifyListeners()
            val o = try {
                runOnce(SharedFileSync.Options(deviceId(), lastSyncAt(), allowDeletions = allowDeletions, allowEmptyFile = allowEmptyFile))
            } finally { running.set(false) }
            main.post { done(o) }
            notifyListeners()
        }
    }

    private fun deviceId() = prefs().getString(K_DEVICE, null) ?: "unknown"
    private fun lastSyncAt() = prefs().getLong(K_LAST_OK, 0L)

    private fun runOnce(opts: SharedFileSync.Options): SharedFileSync.Outcome {
        syncThread = Thread.currentThread()
        prefs().edit().putLong(K_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        val uri = prefs().getString(K_URI, null)?.let { Uri.parse(it) } ?: return SharedFileSync.Outcome.Failed("Choose the shared file first.")
        val o = try {
            SharedFileSync.run(Stores.get(app), SafFileAccess(app, uri), opts)
        } catch (e: Exception) {
            SharedFileSync.Outcome.Failed("The sync didn't finish: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            syncThread = null
        }
        val e = prefs().edit()
        when (o) {
            is SharedFileSync.Outcome.Done -> e.putLong(K_LAST_OK, System.currentTimeMillis()).putString(K_RESULT, o.text.substringBefore('\n'))
                .putBoolean(K_RESULT_OK, true).putBoolean(K_WAITING, false)
            is SharedFileSync.Outcome.NeedsConfirmation -> e.putString(K_RESULT, "Waiting for you: the shared file would remove ${o.count} record(s) here. Tap Sync to see what.")
                .putBoolean(K_RESULT_OK, false).putBoolean(K_WAITING, true)
            is SharedFileSync.Outcome.FileEmptied -> e.putString(K_RESULT, "Waiting for you: the shared file is ${if (o.missing) "missing" else "empty"}. Tap Sync to decide.")
                .putBoolean(K_RESULT_OK, false).putBoolean(K_WAITING, true)
            is SharedFileSync.Outcome.Failed -> e.putString(K_RESULT, o.text).putBoolean(K_RESULT_OK, false)
        }
        e.apply()
        return o
    }

    private const val K_URI = "uri"
    private const val K_NAME = "name"
    private const val K_DEVICE = "deviceId"
    private const val K_AUTO = "auto"
    private const val K_LAST_OK = "lastSyncAt"
    private const val K_LAST_ATTEMPT = "lastAttemptAt"
    private const val K_RESULT = "lastResult"
    private const val K_RESULT_OK = "lastOk"
    private const val K_WAITING = "waiting"
}
