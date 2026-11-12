package com.longrange.dragcurve.ui.calc

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.longrange.dragcurve.R
import com.longrange.dragcurve.fill.CaptureConvert
import com.longrange.dragcurve.fill.CaptureSession
import com.longrange.dragcurve.fill.CaptureSource
import com.longrange.dragcurve.fill.ProfileImportService
import com.longrange.dragcurve.fill.ImportMerge
import com.longrange.dragcurve.fill.ImportNotifier
import com.longrange.dragcurve.fill.ImportSession
import com.longrange.dragcurve.fill.AppAmmo
import com.longrange.dragcurve.fill.AppFirearm
import com.longrange.dragcurve.fill.AppImport
import com.longrange.dragcurve.match.CalcAppUi
import com.longrange.dragcurve.match.Values
import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.TwistDirection
import com.google.android.material.R as MR

class ImportFromAppActivity : CalcActivity() {
    private lateinit var content: LinearLayout
    private val listener: (ImportSession.State) -> Unit = { render() }

    private var review: AppImport? = null
    private var reviewIsPartial = false
    private val pickedFirearms = HashSet<String>()
    private val pickedAmmo = HashSet<String>()
    private var report: String? = null

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchImport() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(TITLE)
        content = findViewById(R.id.content)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == ImportNotifier.ACTION_CANCEL) ImportSession.cancel()
    }

    override fun onStart() {
        super.onStart()
        ImportSession.addListener(listener)
        render()
    }

    override fun onStop() {
        ImportSession.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        content.removeAllViews()
        val form = contentForm()
        report?.let { renderReport(form, it); return }
        when (val s = ImportSession.state) {
            is ImportSession.State.Idle -> renderIntro(form)
            is ImportSession.State.Running -> renderRunning(form, s.progress)
            is ImportSession.State.Done -> { adopt(s.result, partial = false); renderReview(form) }
            is ImportSession.State.Failed ->
                if (review != null && reviewIsPartial && review === s.partial) renderReview(form) else renderFailed(form, s)
        }
    }

    private fun renderIntro(form: Form) {
        form.header("Import profiles from another calculator app")
        form.note(
            "Copies your firearm and ammo profiles from a ballistic calculator app on this phone into Skyward. " +
                "It only reads: nothing is saved, changed or deleted in the other app, and nothing is added to Skyward " +
                "until you have reviewed what was read and tapped Import. Importing again later updates the profiles " +
                "imported before instead of adding copies."
        )
        val enabled = ProfileImportService.isEnabled(this) && ProfileImportService.instance != null
        val auto = packageManager.getLaunchIntentForPackage(CalcAppUi.PACKAGE) != null
        val sources = CaptureSource.entries.filter { packageManager.getLaunchIntentForPackage(it.pkg) != null }
        form.group { info("Accessibility service", if (enabled) "On" else "Off — needed") }
        if (!auto && sources.isEmpty()) {
            form.note("No calculator app that Skyward can read is installed on this phone.")
            return
        }
        form.header("Pick the app")
        form.group {
            if (auto) nav("Calculator app (automatic)", "",
                sub = "Skyward opens it and reads every profile's edit screen by itself, then leaves each with Back. Don't touch the phone while it runs.") { start() }
            for (src in sources) nav(appLabel(src.pkg), "",
                sub = "You open each profile there; Skyward reads the screen as you go. It never taps, types or saves anything in that app.") { startCapture(src) }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { "the other app" }

    private fun renderCapturing(form: Form, src: CaptureSource, progress: String) {
        val app = appLabel(src.pkg)
        form.header("Reading as you go")
        form.note("In $app: ${src.howTo}")
        form.note("While this runs, Skyward reads that app's screen whenever it changes. It never taps, types, scrolls or saves anything there.")
        form.note(progress)
        content.addView(button("Open $app", filled = false) { openApp(src.pkg) })
        content.addView(button("Done — review what was read", filled = true) { CaptureSession.finish() })
        content.addView(button("Cancel", filled = false) { CaptureSession.abandon() })
    }

    private fun renderRunning(form: Form, progress: String) {
        CaptureSession.source?.let { renderCapturing(form, it, progress); return }
        form.header("Reading profiles…")
        form.note("Skyward is reading the other app's screens (read-only). Please don't touch the phone.")
        content.addView(LinearProgressIndicator(this).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), dp(12), dp(20), dp(4))
            }
        })
        form.note(progress)
        content.addView(button("Stop", filled = false) { ImportSession.cancel() })
    }

    private fun renderFailed(form: Form, s: ImportSession.State.Failed) {
        form.header("Import stopped")
        form.note(s.message)
        val p = s.partial
        if (p.firearms.isNotEmpty() || p.ammo.isNotEmpty()) {
            form.note("${plural(p.firearms.size, "firearm")} and ${plural(p.ammo.size, "ammo profile")} were read completely before it stopped.")
            content.addView(button("Review what was read", filled = false) {
                adopt(p, partial = true)
                render()
            })
        }
        content.addView(button("Try again", filled = true) { ImportSession.clear(); start() })
    }

    private fun adopt(r: AppImport, partial: Boolean) {
        if (review === r) return
        review = r
        reviewIsPartial = partial
        pickedFirearms.clear(); pickedAmmo.clear()
        r.firearms.forEach { pickedFirearms += it.name }
        r.ammo.filter { it.problem == null }.forEach { pickedAmmo += ImportMerge.ammoKey(it) }
    }

    private fun renderReview(form: Form) {
        val r = review ?: return
        val existing = store.snapshot()
        form.header("Review")
        form.note(summaryLine())
        if (reviewIsPartial) form.note("Only part of the profiles were read (the import stopped). You can still import these.")
        r.warnings.forEach { form.note("⚠ $it") }
        if (r.firearms.isEmpty()) form.note("No firearms were found in the other app.")
        for (f in r.firearms) {
            val card = card()
            card.addView(check(
                title = f.name,
                sub = firearmLine(f) + if (ImportMerge.existsFirearm(existing, f, r.refPrefix)) "\nAlready imported — will be updated" else "",
                checked = f.name in pickedFirearms,
                enabled = true,
                indent = 0,
            ) { on -> if (on) pickedFirearms += f.name else pickedFirearms -= f.name; refreshSummary() })
            val ammo = r.ammoOf(f.name)
            if (ammo.isEmpty()) card.addView(small("No ammo profiles", indent = 1))
            for (a in ammo) {
                val key = ImportMerge.ammoKey(a)
                val sub = (a.problem?.let { "Can't import: $it" } ?: ammoLine(a)) +
                    if (a.problem == null && ImportMerge.existsAmmo(existing, a, r.refPrefix)) "\nAlready imported — will be updated" else ""
                card.addView(check(a.name, sub, key in pickedAmmo, enabled = a.problem == null, indent = 1) { on ->
                    if (on) pickedAmmo += key else pickedAmmo -= key
                    refreshSummary()
                })
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), 0)
        }
        row.addView(button("Import", filled = true, margins = false) { doImport() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(button("Discard", filled = false, margins = false) { discard() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(row)
    }

    private fun summaryLine(): String {
        val nf = pickedFirearms.size
        val na = pickedAmmo.size
        return "${plural(nf, "firearm")}, $na ammo selected — import?"
    }

    private fun refreshSummary() {
        (content.getChildAt(1) as? TextView)?.text = summaryLine()
    }

    private fun renderReport(form: Form, text: String) {
        form.header("Imported")
        form.note(text)
        content.addView(button("Done", filled = true) { report = null; finish() })
    }

    private fun start() {
        val svc = ProfileImportService.instance
        if (svc == null || !ProfileImportService.isEnabled(this)) { askForService(); return }
        if (packageManager.getLaunchIntentForPackage(CalcAppUi.PACKAGE) == null) {
            alert("Calculator app not found", "No supported ballistic calculator app is installed on this phone, so there is nothing to import.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchImport()
    }

    private fun startCapture(src: CaptureSource) {
        val svc = ProfileImportService.instance
        if (svc == null || !ProfileImportService.isEnabled(this)) { askForService(); return }
        if (svc.isImporting) {
            alert("Busy", "Another import is running. Wait for it to finish (or stop it), then try again.")
            return
        }
        review = null; report = null
        val st = store
        CaptureSession.start(this, src) { s, list -> CaptureConvert.toImport(s, list, st.libraryBullets() + st.userBullets()) }
        openApp(src.pkg)
    }

    private fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: ActivityNotFoundException) {}
    }

    private fun launchImport() {
        val svc = ProfileImportService.instance ?: run { askForService(); return }
        val launch = packageManager.getLaunchIntentForPackage(CalcAppUi.PACKAGE) ?: return
        review = null; report = null
        if (!svc.startImport()) {
            alert("Busy", "A fill into the other app is running. Wait for it to finish, then try again.")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (e: ActivityNotFoundException) {
            ImportSession.cancel()
            alert("Couldn't open the calculator app", e.message ?: "")
        }
    }

    private fun askForService() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on the accessibility service")
            .setMessage(
                "Skyward reads the other app's profiles through its accessibility service (\"Skyward profile import\"). " +
                    "It acts only inside that one app and only while you run a fill or an import from here.\n\n" +
                    "Open Accessibility settings, turn on \"Skyward profile import\", come back and tap Start.\n\n" +
                    getString(R.string.banner_restricted)
            )
            .setPositiveButton(R.string.open_accessibility) { _, _ -> openAccessibilitySettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(packageName, ProfileImportService::class.java.name).flattenToString()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", cn)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", cn) })
        try { startActivity(i) } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Open Settings › Accessibility on this phone.", Toast.LENGTH_LONG).show()
        }
    }

    private fun doImport() {
        val r = review ?: return
        if (pickedFirearms.isEmpty() && pickedAmmo.isEmpty()) {
            Toast.makeText(this, "Nothing selected.", Toast.LENGTH_SHORT).show(); return
        }
        val plan = ImportMerge.build(r, pickedFirearms.toSet(), pickedAmmo.toSet(), store.snapshot(), System.currentTimeMillis()) { CalcActivity.newId(it) }
        val rep = store.merge(plan.libs)
        report = buildString {
            append("Firearms: ${plan.newRifles} new, ${plan.updatedRifles} updated.\n")
            append("Ammo: ${plan.newLoads} new, ${plan.updatedLoads} updated.")
            if (plan.skipped.isNotEmpty()) { append("\n\nNot imported:\n"); plan.skipped.forEach { append("• ").append(it).append('\n') } }
            append("\n\n").append(ReportText.long(rep))
        }.trim()
        review = null
        ImportSession.clear()
        render()
    }

    private fun discard() {
        review = null
        ImportSession.clear()
        finish()
    }

    private fun fmt(v: Double, dec: Int = 3) = Values.fmt(Math.round(v * Math.pow(10.0, dec.toDouble())) / Math.pow(10.0, dec.toDouble()))

    private fun unitName(u: AngleUnit) = when (u) { AngleUnit.MIL -> "mil"; AngleUnit.MOA -> "MOA"; AngleUnit.IPHY -> "IPHY" }

    private fun firearmLine(f: AppFirearm) = buildList {
        f.twistIn?.let { add("1:${fmt(it, 2)}\" ${if (f.twistDirection == TwistDirection.RIGHT) "RH" else "LH"}") }
        f.sightHeightIn?.let { add("sight ${fmt(it)}\"") }
        if (f.clicksRead) add("${fmt(f.elevationClick)} ${unitName(f.elevationUnit)} / ${fmt(f.windageClick)} ${unitName(f.windageUnit)} clicks")
        else if (f.scopeRead) add("${unitName(f.elevationUnit)} scope")
        if (f.scopeRead && (f.elevationCorrection != 1.0 || f.windageCorrection != 1.0)) add("correction ${fmt(f.elevationCorrection)} / ${fmt(f.windageCorrection)}")
    }.joinToString(" · ")

    private fun ammoLine(a: AppAmmo) = buildList {
        if (a.libraryBulletName != null) add("${fmt(a.weightGr, 1)} gr · drag from your library bullet ${a.libraryBulletName}")
        else {
            add("${fmt(a.weightGr, 1)} gr ${a.dragModelText}")
            if (a.bcPoints.isNotEmpty()) add(if (a.bcPoints.size == 1) "BC ${fmt(a.bcPoints[0].bc)}" else "${a.bcPoints.size} BCs (${fmt(a.bcPoints.first().bc)}…)")
        }
        add("${fmt(a.muzzleVelocityFps, 0)} fps")
        add("zero ${fmt(a.zeroRangeYd, 0)} yd")
        if (a.zeroAtmosphere != null) add("zero atmosphere")
        if (a.velocityPerDegFps != 0.0) add("${fmt(a.velocityPerDegFps, 2)} fps/°F")
    }.joinToString(" · ")

    private fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"

    private fun button(text: String, filled: Boolean, margins: Boolean = true, onClick: () -> Unit): MaterialButton {
        val b = if (filled) MaterialButton(this) else MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle)
        b.text = text
        b.setOnClickListener { onClick() }
        if (margins) b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(16), dp(16), 0)
        }
        return b
    }

    private fun card(): LinearLayout {
        val card = MaterialCardView(this).apply { radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0 }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSurfaceContainerHigh))
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        card.addView(inner)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(10), dp(12), 0)
        })
        return inner
    }

    private fun check(title: String, sub: String, checked: Boolean, enabled: Boolean, indent: Int, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8 + indent * 28), dp(2), dp(16), dp(2))
        }
        val cb = MaterialCheckBox(this).apply {
            isChecked = checked && enabled
            isEnabled = enabled
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(cb)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (indent == 0) 19f else 18f)
            if (indent == 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            alpha = if (enabled) 1f else 0.5f
        })
        col.addView(TextView(this).apply { text = sub; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); alpha = 0.9f })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.setOnClickListener { if (cb.isEnabled) cb.toggle() }
        return row
    }

    private fun small(text: String, indent: Int) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        alpha = 0.6f
        setPadding(dp(20 + indent * 28), dp(4), dp(16), dp(8))
    }

    companion object {
        const val TITLE = "Import from another app"
        const val MENU_TITLE = "Import from another calculator app"

        fun open(c: Context) {
            try {
                c.startActivity(Intent(c, ImportFromAppActivity::class.java))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(c, "Import isn't available in this build.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
