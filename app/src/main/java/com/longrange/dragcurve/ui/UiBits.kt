package com.longrange.dragcurve.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.longrange.dragcurve.R
import com.longrange.dragcurve.fill.ProfileImportService

object ServiceBanner {
    fun bind(activity: Activity) {
        val banner = activity.findViewById<View>(R.id.serviceBanner) ?: return
        val enabled = ProfileImportService.isEnabled(activity)
        banner.visibility = if (enabled) View.GONE else View.VISIBLE
        activity.findViewById<View>(R.id.bannerRestricted).visibility =
            if (Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
        activity.findViewById<View>(R.id.btnOpenAccessibility).setOnClickListener {
            activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        activity.findViewById<View>(R.id.btnOpenAppInfo).setOnClickListener {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
            )
        }
    }
}

object StatusBadge {
    fun bind(tv: TextView, status: String) {
        val color = when (status.lowercase()) {
            "matched" -> R.color.status_matched
            "seasonal" -> R.color.status_seasonal
            "partial" -> R.color.status_partial
            else -> R.color.status_pending
        }
        tv.text = status.lowercase()
        ViewCompat.setBackgroundTintList(tv, ColorStateList.valueOf(ContextCompat.getColor(tv.context, color)))
    }
}

fun fmtNum(v: Double?, decimals: Int = 3): String {
    if (v == null) return "—"
    if (v == Math.rint(v)) return v.toLong().toString()
    if (decimals <= 0) return Math.round(v).toString()
    return "%.${decimals}f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
}
