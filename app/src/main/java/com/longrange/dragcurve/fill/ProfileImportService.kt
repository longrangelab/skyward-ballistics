package com.longrange.dragcurve.fill

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.longrange.dragcurve.match.CalcAppUi

class ProfileImportService : AccessibilityService() {
    private var importEngine: ImportEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == CalcAppUi.PACKAGE) return
        CaptureSession.onScreenChanged(this, pkg)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        importEngine?.abort("The accessibility service was turned off, so the import stopped.")
        CaptureSession.serviceGone()
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        importEngine?.abort("The accessibility service stopped, so the import stopped.")
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun startImport(): Boolean {
        importEngine?.abort("Replaced by a new import.")
        val e = ImportEngine(this) { eng -> if (importEngine === eng) importEngine = null }
        importEngine = e
        e.start()
        return true
    }

    fun stopImport(reason: String) {
        importEngine?.cancel(reason)
    }

    val isImporting: Boolean get() = importEngine != null

    companion object {
        @Volatile
        var instance: ProfileImportService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                val si = it.resolveInfo?.serviceInfo ?: return@any false
                si.packageName == context.packageName && si.name == ProfileImportService::class.java.name
            }
        }
    }
}
