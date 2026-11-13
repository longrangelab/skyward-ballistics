package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.model.AppInfo

fun showDisclaimer(activity: Activity, onOk: (() -> Unit)? = null) {
    MaterialAlertDialogBuilder(activity)
        .setTitle(AppInfo.DISCLAIMER_TITLE)
        .setMessage(AppInfo.DISCLAIMER)
        .setCancelable(onOk == null)
        .setPositiveButton(if (onOk != null) "I understand" else "OK") { _, _ -> onOk?.invoke() }
        .show()
}

fun openLink(activity: Activity, url: String) {
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, "No browser found. The address is $url", Toast.LENGTH_LONG).show()
    }
}

fun versionName(activity: Activity): String =
    runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0).versionName }.getOrNull() ?: "?"
