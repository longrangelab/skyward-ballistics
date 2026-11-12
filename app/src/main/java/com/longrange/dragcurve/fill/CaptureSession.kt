package com.longrange.dragcurve.fill

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

object CaptureSession {
    private const val TAG = "SkywardCapture"
    private const val SETTLE_MS = 350L

    @Volatile var source: CaptureSource? = null
        private set
    val isActive: Boolean get() = source != null

    private val store = CaptureStore()
    private var convert: ((CaptureSource, List<CapturedProfile>) -> AppImport)? = null
    private var appContext: Context? = null
    private var service: AccessibilityService? = null
    private val main = Handler(Looper.getMainLooper())
    private val readRunnable = Runnable { readNow() }

    fun profiles(): List<CapturedProfile> = store.profiles

    fun start(ctx: Context, s: CaptureSource, convert: (CaptureSource, List<CapturedProfile>) -> AppImport) {
        Log.d(TAG, "capture started: ${s.pkg}")
        source = s
        store.clear()
        this.convert = convert
        appContext = ctx.applicationContext
        ImportSession.set(ImportSession.State.Running(progressText()))
        ImportNotifier.progress(ctx, progressText())
    }

    fun onScreenChanged(svc: AccessibilityService, pkg: String) {
        val s = source ?: return
        if (s.pkg != pkg) return
        service = svc
        main.removeCallbacks(readRunnable)
        main.postDelayed(readRunnable, SETTLE_MS)
    }

    private fun readNow() {
        val s = source ?: return
        val svc = service ?: return
        val read = try {
            s.read(NodeSnapshot.take(svc, s.pkg).roots)
        } catch (e: Exception) {
            Log.w(TAG, "could not read the screen", e); return
        }
        Log.d(TAG, "screen read: ${read.map { it.key }}")
        if (store.add(read)) {
            val t = progressText()
            ImportSession.set(ImportSession.State.Running(t))
            ImportNotifier.progress(svc, t)
        }
    }

    fun progressText(): String {
        val names = store.profiles.map { it.name }
        if (names.isEmpty()) return "Open your profiles in the other app. Nothing has been read yet."
        return "${names.size} profile${if (names.size == 1) "" else "s"} read: " + names.take(6).joinToString(", ") +
            (if (names.size > 6) " and ${names.size - 6} more" else "") + ". Tap Done in Skyward when you have them all."
    }

    fun finish() {
        Log.d(TAG, "capture finished")
        val s = source ?: return
        source = null
        main.removeCallbacks(readRunnable)
        val result = convert?.invoke(s, profiles()) ?: AppImport(refPrefix = s.refPrefix)
        appContext?.let { ImportNotifier.cancel(it) }
        ImportSession.set(ImportSession.State.Done(result))
        service = null
    }

    fun abandon() {
        source = null
        main.removeCallbacks(readRunnable)
        store.clear()
        appContext?.let { ImportNotifier.cancel(it) }
        ImportSession.set(ImportSession.State.Idle)
        service = null
    }

    fun serviceGone() {
        if (source == null) return
        Log.d(TAG, "service unbound; capture continues")
        main.removeCallbacks(readRunnable)
        service = null
    }
}
