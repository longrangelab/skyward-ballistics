package com.longrange.dragcurve.fill

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.longrange.dragcurve.R
import com.longrange.dragcurve.match.CalcAppUi

object ImportSession {
    sealed class State {
        object Idle : State()
        data class Running(val progress: String) : State()
        data class Done(val result: AppImport) : State()
        data class Failed(val message: String, val partial: AppImport) : State()
    }

    @Volatile var state: State = State.Idle
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<(State) -> Unit>()

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    fun set(s: State) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun clear() { if (state !is State.Running) set(State.Idle) }

    val isRunning: Boolean get() = state is State.Running

    fun cancel() {
        if (CaptureSession.isActive) { CaptureSession.finish(); return }
        ProfileImportService.instance?.stopImport("Stopped from Skyward Ballistics.")
            ?: run { if (state is State.Running) set(State.Failed("Stopped.", AppImport())) }
    }
}

class ImportEngine(
    private val service: AccessibilityService,
    private val onFinished: (engine: ImportEngine) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val planner = ImportPlanner()
    private val tickRunnable = Runnable { tick() }
    private var finished = false
    private var lastProgress = ""

    fun start() {
        ImportSession.set(ImportSession.State.Running(planner.progress))
        ImportNotifier.progress(service, planner.progress)
        handler.postDelayed(tickRunnable, 600)
    }

    fun cancel(reason: String) {
        if (finished) return
        planner.requestCancel(reason)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    fun abort(reason: String) {
        if (finished) return
        finish(ImportAction.Failed(reason, planner.partial()))
    }

    private fun tick() {
        if (finished) return
        val action = try {
            val snap = NodeSnapshot.take(service)
            val a = planner.next(Observation(snap.roots, snap.appInForeground, SystemClock.uptimeMillis()))
            if (!perform(snap, a)) return
            a
        } catch (e: Exception) {
            Log.e(TAG, "import crashed", e)
            finish(ImportAction.Failed("Internal error: ${e.javaClass.simpleName}: ${e.message}", planner.partial()))
            return
        }
        if (planner.progress != lastProgress) {
            lastProgress = planner.progress
            ImportSession.set(ImportSession.State.Running(lastProgress))
            ImportNotifier.progress(service, lastProgress)
        }
        handler.postDelayed(tickRunnable, maxOf(100L, action.waitMs))
    }

    private fun perform(snap: NodeSnapshot, a: ImportAction): Boolean {
        when (a) {
            is ImportAction.Finished, is ImportAction.Failed -> { finish(a); return false }
            is ImportAction.Wait -> {}
            is ImportAction.Back -> {
                if (!snap.appInForeground) { finish(ImportAction.Failed("The calculator left the screen, so the import stopped.", planner.partial())); return false }
                Log.i(TAG, "back: ${a.why}")
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            is ImportAction.Scroll -> {
                val info = snap.info(a.node) ?: return true
                info.performAction(if (a.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            is ImportAction.Click, is ImportAction.LongClick -> {
                val node = if (a is ImportAction.Click) a.node else (a as ImportAction.LongClick).node
                val what = if (a is ImportAction.Click) a.what else (a as ImportAction.LongClick).what
                val allowCancel = a is ImportAction.Click && a.allowCancelIcon
                val allowDiscard = a is ImportAction.Click && a.allowDiscard
                ImportSafety.violation(node, allowCancelIcon = allowCancel, allowDiscard = allowDiscard)?.let {
                    finish(ImportAction.Failed("Refused to tap \"$what\" ($it). Stopped; nothing was changed.", planner.partial()))
                    return false
                }
                if (node.packageName != null && node.packageName != CalcAppUi.PACKAGE) {
                    finish(ImportAction.Failed("Refused to act outside the calculator.", planner.partial())); return false
                }
                val info = snap.info(node) ?: return true
                val op = if (a is ImportAction.Click) AccessibilityNodeInfo.ACTION_CLICK else AccessibilityNodeInfo.ACTION_LONG_CLICK
                Log.i(TAG, "${if (a is ImportAction.Click) "tap" else "long-press"}: $what")
                if (!info.performAction(op)) Log.w(TAG, "action on $what returned false")
            }
        }
        return true
    }

    private fun finish(a: ImportAction) {
        if (finished) return
        finished = true
        handler.removeCallbacks(tickRunnable)
        when (a) {
            is ImportAction.Finished -> {
                ImportSession.set(ImportSession.State.Done(a.result))
                ImportNotifier.done(service, true, "Profiles read — review them in Skyward",
                    "${a.result.firearms.size} firearms, ${a.result.ammo.size} ammo. Nothing is saved until you confirm.")
            }
            is ImportAction.Failed -> {
                ImportSession.set(ImportSession.State.Failed(a.message, a.partial))
                ImportNotifier.done(service, false, "Import stopped", a.message)
            }
            else -> {}
        }
        Log.i(TAG, "import finished: $a")
        onFinished(this)
        try {
            service.startActivity(
                Intent().setClassName(service.packageName, ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "could not bring Skyward back", e)
        }
    }

    companion object {
        private const val TAG = "SkywardImport"
        const val ACTIVITY = "com.longrange.dragcurve.ui.calc.ImportFromAppActivity"
    }
}

object ImportNotifier {
    private const val CHANNEL = "import_progress"
    private const val NOTIF_ID = 4002

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Profile import", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun openIntent(ctx: Context, cancel: Boolean): PendingIntent {
        val i = Intent().setClassName(ctx.packageName, ImportEngine.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (cancel) i.action = ACTION_CANCEL
        return PendingIntent.getActivity(ctx, if (cancel) 2 else 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(ctx: Context, text: String) {
        if (!allowed(ctx)) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fill)
            .setContentTitle("Importing profiles (read-only)")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(openIntent(ctx, false))
            .addAction(0, "Stop", openIntent(ctx, true))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    fun done(ctx: Context, ok: Boolean, title: String, message: String) {
        if (!allowed(ctx)) { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID); return }
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_fill)
            .setContentTitle(title)
            .setContentText(message.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setPriority(if (ok) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openIntent(ctx, false))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    fun cancel(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)

    const val ACTION_CANCEL = "com.longrange.dragcurve.action.CANCEL_IMPORT"
}
