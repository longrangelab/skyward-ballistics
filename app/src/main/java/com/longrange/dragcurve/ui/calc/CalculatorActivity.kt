package com.longrange.dragcurve.ui.calc

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.longrange.dragcurve.R
import com.longrange.dragcurve.engine.Engine
import com.longrange.dragcurve.engine.Solution
import com.longrange.dragcurve.engine.clicks
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.model.SpinDriftModel
import java.util.concurrent.Executors
import kotlin.math.abs
import com.google.android.material.R as MR

data class Selected(val rifle: Rifle?, val load: Load?, val bullet: Bullet?) {
    val complete get() = rifle != null && load != null && bullet != null

    companion object {
        fun resolve(store: ProfileStore): Selected {
            val (rid, lid) = store.selection()
            val rifles = store.rifles()
            val rifle = rifles.firstOrNull { it.id == rid } ?: rifles.firstOrNull()
            val loads = rifle?.let { store.loads(it.id) }.orEmpty()
            val load = loads.firstOrNull { it.id == lid } ?: loads.firstOrNull()
            if (rifle?.id != rid || load?.id != lid) store.saveSelection(rifle?.id, load?.id)
            return Selected(rifle, load, load?.let { store.bullet(it.bulletId) })
        }
    }
}

class CalculatorActivity : CalcActivity() {
    private lateinit var form: Form
    private var shot = ShotInputs()
    private var sel = Selected(null, null, null)
    private var moveRight = true
    private var stabilityView: TextView? = null
    private var daView: TextView? = null
    private var windHourView: TextView? = null
    private var windSideView: TextView? = null
    private var gravityView: TextView? = null
    private lateinit var logoSpin: SpinLogoDrawable
    private var pendingScroll: Float? = null
    private lateinit var tabBar: View
    override val snackAnchor: View? get() = if (::tabBar.isInitialized) tabBar else null

    private val ui = Handler(Looper.getMainLooper())
    private var generation = 0
    private val solveLater = Runnable { solveNow() }
    private lateinit var elevCell: ResultCell
    private lateinit var windCell: ResultCell

    private fun gravityText(sh: ShotInputs): String {
        val g = Engine.gravityFor(sh)
        if (!sh.localGravity) return "Off: uses standard gravity, ${Num.fixed(g, 3)} m/s²"
        val st = settings
        return "${Num.fixed(g, 3)} m/s² at ${Num.fixed(sh.latitudeDeg, 1)}°, " +
            Num.trim(Units.toDisplay(Quantity.ALTITUDE, sh.atmosphere.altitudeFt, st), 0) + " " + Units.label(Quantity.ALTITUDE, st)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        tabBar = TabNav.install(this, R.layout.calc_activity_main, Tab.CALCULATOR)
        val tb = setupToolbar("SKYWARD", up = false)
        logoSpin = SpinLogoDrawable.create(this, 34, onNavy = true, turnMs = MotionRules.LOGO_TURN_MS)
        logoSpin.playTime = logoPlayTime
        tb.logo = logoSpin
        tb.logoDescription = getString(R.string.app_name)
        tb.titleMarginStart = dp(12)
        form = contentForm()
        if (isLandscape) form.contentWidthPx = (resources.displayMetrics.widthPixels * 0.6f).toInt()
        pendingScroll = savedInstanceState?.getFloat("scrollFraction", -1f)?.takeIf { it >= 0f }
        buildResultBar()
        if (savedInstanceState == null && !resetHandled) {
            resetHandled = true
            if (!settings.saveLastInputs) store.saveLastShot(ShotInputs())
        }
        val speed = store.lastShot().targetSpeedMph
        moveRight = if (speed != 0.0) speed > 0 else uiPrefs().getBoolean("moveRight", true)
        showLaunchArtwork(savedInstanceState)
    }

    private fun showLaunchArtwork(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || launchShown) return
        launchShown = true
        val overlay = findViewById<View>(R.id.launchOverlay)
        overlay.visibility = View.VISIBLE
        val motion = Motion.on(this)
        val turn = if (!motion) null else {
            val art = findViewById<LaunchArtView>(R.id.launchArt)
            art.turn = -MotionRules.LAUNCH_TURN_DEG
            ValueAnimator.ofFloat(-MotionRules.LAUNCH_TURN_DEG, 0f).apply {
                duration = MotionRules.LAUNCH_TURN_MS
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { art.turn = it.animatedValue as Float }
                overlay.doOnPreDraw { start() }
            }
        }
        var gone = false
        val hide = Runnable {
            if (gone) return@Runnable
            gone = true
            val end = Runnable { turn?.cancel(); overlay.visibility = View.GONE }
            if (motion) overlay.animate().alpha(0f).setDuration(MotionRules.LAUNCH_FADE_MS).withEndAction(end).start() else end.run()
        }
        overlay.setOnClickListener { ui.removeCallbacks(hide); hide.run() }
        ui.postDelayed(hide, 1000)
    }

    private fun launchShowing() = findViewById<View>(R.id.launchOverlay)?.visibility == View.VISIBLE

    private fun startupNotices() {
        if (noticesDone) return
        if (launchShowing()) { ui.postDelayed({ if (!isFinishing && !isDestroyed) startupNotices() }, 400); return }
        noticesDone = true
        when {
            InstallState.guidePending(this) -> startActivity(Intent(this, GuideActivity::class.java))
            InstallState.restoreNoticePending(this) -> {
                InstallState.restoreNoticeShown(this)
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Welcome back")
                    .setMessage("Your firearms, ammo, bullets, bullet library and settings were restored from your Google account's Android backup.\n\n" +
                        "A few things belong to the old phone and need setting up again here: Google Drive sync (sign in again), the backup folder, " +
                        "and any linked files such as an Empirical backup file or a database.")
                    .setPositiveButton("Backup & sync") { _, _ -> startActivity(Intent(this, BackupSyncActivity::class.java)) }
                    .setNegativeButton(android.R.string.ok, null).show()
            }
            else -> backupReminder()
        }
    }

    private fun backupReminder() {
        val p = uiPrefs(); val now = System.currentTimeMillis()
        val first = p.getLong("reminderFirstSeen", 0L)
        if (first == 0L) { p.edit().putLong("reminderFirstSeen", now).apply(); return }
        val due = BackupReminder.due(now, first, p.getLong("reminderShownAt", 0L), hasGear = store.rifles().isNotEmpty(),
            driveSyncOn = com.longrange.dragcurve.sync.SyncScheduler.isEnabled(), folderBackupOn = BackupFolder.isOn(this))
        if (!due) return
        p.edit().putLong("reminderShownAt", now).apply()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Back up your gear?")
            .setMessage("Your firearms and ammo are on this phone and in Android's own backup. For a copy you can always reach, " +
                "let Skyward keep a backup file in a folder you choose, or save one now.")
            .setPositiveButton("Set up auto backup") { _, _ -> startActivity(Intent(this, BackupSyncActivity::class.java).putExtra(BackupSyncActivity.EXTRA_ACTION, BackupSyncActivity.ACTION_FOLDER)) }
            .setNeutralButton("Export now") { _, _ -> startActivity(Intent(this, BackupSyncActivity::class.java).putExtra(BackupSyncActivity.EXTRA_ACTION, BackupSyncActivity.ACTION_EXPORT)) }
            .setNegativeButton("Not now", null).show()
    }

    private fun uiPrefs() = getSharedPreferences("calc_ui", MODE_PRIVATE)

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) fetchLatitude()
        else Snackbar.make(findViewById(R.id.content), "Location permission is needed to read the latitude. You can also type it in.", Snackbar.LENGTH_LONG).show()
    }

    private fun getLatitude() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) fetchLatitude()
        else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private fun fetchLatitude() {
        val root = findViewById<View>(R.id.content)
        val bar = Snackbar.make(root, "Getting location…", Snackbar.LENGTH_INDEFINITE).also { it.show() }
        LatitudeTool.fetch(this) { lat ->
            bar.dismiss()
            if (isFinishing || isDestroyed) return@fetch
            if (lat == null) Snackbar.make(root, "No location available. Turn on Location and try again, or type the latitude.", Snackbar.LENGTH_LONG).show()
            else { update { copy(latitudeDeg = Math.rint(lat * 10) / 10) }; render() }
        }
    }

    override fun onResume() {
        super.onResume()
        shot = store.lastShot()
        sel = Selected.resolve(store)
        RecentGear.used(this, sel.rifle?.id, sel.load?.id)
        render()
        checkEmpiricalLink()
        startupNotices()

        if (Motion.on(this)) logoSpin.spin() else logoSpin.rest()
    }

    override fun onPause() {
        logoSpin.pause()
        logoPlayTime = logoSpin.playTime
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val range = (scroll.getChildAt(0)?.height ?: 0) - scroll.height
        outState.putFloat("scrollFraction", if (range > 0) (scroll.scrollY.toFloat() / range).coerceIn(0f, 1f) else 0f)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.calc_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun update(f: ShotInputs.() -> ShotInputs) {
        shot = shot.f()
        store.saveLastShot(shot)
        refreshLive()
    }

    private fun picked() { sel = Selected.resolve(store); RecentGear.used(this, sel.rifle?.id, sel.load?.id); render() }

    private fun pickRifle() = QuickPicker.firearm(this, store) { picked() }

    private fun pickAmmo() {
        val r = sel.rifle ?: return pickRifle()
        QuickPicker.ammo(this, store, r.id) { picked() }
    }

    private fun calculate() {
        currentFocus?.let { Form.hideKeyboard(it); it.clearFocus() }
        if (!sel.complete) {
            val root = findViewById<View>(R.id.content)
            when {
                sel.rifle == null -> pickRifle()
                sel.load == null -> pickAmmo()
                else -> Snackbar.make(root, "This ammo's bullet isn't available — import the bullet library or edit the ammo.", Snackbar.LENGTH_LONG)
                    .setAnchorView(tabBar)
                    .setAction(R.string.calc_libraries) { startActivity(Intent(this, LibrariesActivity::class.java)) }.show()
            }
            return
        }
        CalcGuard.problem(sel.rifle!!, sel.load!!, sel.bullet!!)?.let { msg ->
            Snackbar.make(findViewById<View>(R.id.content), msg, Snackbar.LENGTH_LONG).show()
            return
        }
        store.saveLastShot(shot)
        startActivity(Intent(this, SolutionActivity::class.java))
    }

    private fun isOpen(key: String) = uiPrefs().getBoolean("open_$key", false)

    private fun fold(key: String, title: String, summary: () -> String, block: Form.Group.() -> Unit) {
        form.foldable(title, summary, isOpen(key), { open ->
            uiPrefs().edit().putBoolean("open_$key", open).apply()
            currentFocus?.let { Form.hideKeyboard(it); it.clearFocus() }
        }, block)
    }

    private fun speedText(mph: Double): String =
        Num.trim(Units.toDisplay(Quantity.SPEED, mph, settings), 1) + " " + Units.label(Quantity.SPEED, settings)

    private fun angleSummary(sh: ShotInputs): String {
        val parts = ArrayList<String>()
        parts += when {
            sh.lookAngleDeg > 0 -> "Uphill ${Num.trim(sh.lookAngleDeg, 1)}°"
            sh.lookAngleDeg < 0 -> "Downhill ${Num.trim(-sh.lookAngleDeg, 1)}°"
            else -> "Level shot"
        }
        if (sh.cantDeg != 0.0) parts += "cant ${Num.trim(sh.cantDeg, 1)}°"
        parts += if (sh.targetSpeedMph == 0.0) "target not moving"
        else "target moving ${if (sh.targetSpeedMph > 0) "right" else "left"} at ${speedText(abs(sh.targetSpeedMph))}"
        return parts.joinToString(" · ")
    }

    private fun weatherSummary(sh: ShotInputs): String {
        val st = settings; val a = sh.atmosphere
        val temp = Num.trim(Units.toDisplay(Quantity.TEMPERATURE, a.temperatureF, st), 0) + " " + Units.label(Quantity.TEMPERATURE, st)
        val da = Num.trim(Units.toDisplay(Quantity.ALTITUDE, DensityAltitude.of(a), st), 0) + " " + Units.label(Quantity.ALTITUDE, st)
        return if (st.useDensityAltitude) "$temp · density altitude $da"
        else temp + " · " + Num.trim(Units.toDisplay(Quantity.PRESSURE, a.pressureInHg, st), 2) + " " + Units.label(Quantity.PRESSURE, st) +
            " · " + Num.trim(a.humidityPct, 0) + "% humidity"
    }

    private fun fineSummary(sh: ShotInputs): String {
        fun onOff(b: Boolean) = if (b) "on" else "off"
        return "Spin drift ${onOff(sh.spinDrift)} · aerodynamic jump ${onOff(sh.aeroJump)} · Coriolis ${onOff(sh.coriolis)}" +
            if (sh.localGravity) " · local gravity on" else ""
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        val s = settings
        form.settings = s
        form.clear()
        stabilityView = null; daView = null; windHourView = null; windSideView = null; gravityView = null
        val sh = shot

        form.header("Firearm and ammo")
        form.group {
            nav("Firearm", sel.rifle?.name ?: "Tap to add a firearm") { pickRifle() }
            val loadSub = sel.load?.let { l -> listOfNotNull(LoadFlags.text(l), BcSelection.summary(l, sel.bullet)).joinToString(" · ") }
            nav("Ammo", sel.load?.name ?: if (sel.rifle == null) "Add a firearm first" else "Tap to add ammo", sub = loadSub) { pickAmmo() }
        }

        form.header("Distance to target")
        form.group {
            var row: Form.NumberRow? = null
            row = stepper("Distance", Quantity.DISTANCE, sh.distanceYd,
                { v -> if (v != null && v > 0 && v <= 5000) update { copy(distanceYd = v) } },
                onStep = { dir ->
                    val stepDisp = Units.toDisplay(Quantity.DISTANCE, settings.solutionStepYd.coerceAtLeast(0.1), settings)
                    val cur = Units.toDisplay(Quantity.DISTANCE, shot.distanceYd, settings)
                    val yd = Units.fromDisplay(Quantity.DISTANCE, (Math.rint(cur / stepDisp) + dir) * stepDisp, settings)
                    if (yd > 0 && yd <= 5000) { update { copy(distanceYd = yd) }; row?.set(yd) }
                })
        }

        form.header("Wind")
        form.group {
            var speedRow: Form.NumberRow? = null
            speedRow = stepper("Wind speed", Quantity.SPEED, sh.windSpeed1Mph,
                { v -> if (v != null && v >= 0 && v < 100) update { copy(windSpeed1Mph = v) } },
                onStep = { dir ->
                    val next = (Math.rint(Units.toDisplay(Quantity.SPEED, shot.windSpeed1Mph, settings)) + dir).coerceIn(0.0, 99.0)
                    val mph = Units.fromDisplay(Quantity.SPEED, next, settings)
                    update { copy(windSpeed1Mph = mph) }; speedRow?.set(mph)
                })
            var angleRow: Form.NumberRow? = null
            val dial = WindDialView(this@CalculatorActivity, onPick = { deg ->
                currentFocus?.clearFocus()
                shot = shot.copy(windAngleDeg = deg)
                angleRow?.set(deg)
                refreshLive()
            }, onRelease = { store.saveLastShot(shot) })
            dial.angleDeg = sh.windAngleDeg
            custom(windDirectionView(dial))
            angleRow = number("Exact angle", Quantity.ANGLE, sh.windAngleDeg, { v ->
                if (v != null && v >= 0 && v <= 360) { update { copy(windAngleDeg = v) }; dial.angleDeg = v }
            }, decimals = 0, sub = "The dial snaps to the half hour. Type any angle here", widestText = "360")
            angleRow.edit.contentDescription = "Wind angle in degrees"
            number("Second wind speed", Quantity.SPEED, sh.windSpeed2Mph, { v -> if (v != null && v >= 0 && v < 100) update { copy(windSpeed2Mph = v) } },
                sub = "Optional. For gusts: shows a second windage. 0 = not used")
        }

        form.header("More conditions")
        form.note("Set these once for the day. Tap a card to open it.")

        fold("angle", "Slope and moving target", { angleSummary(shot) }) {
            number("Uphill / downhill angle", Quantity.ANGLE, sh.lookAngleDeg, { v -> if (v != null && abs(v) < 90) update { copy(lookAngleDeg = v) } },
                signed = true, sub = "Also called look angle. + uphill, − downhill",
                action = "Measure with phone" to { TiltTool.show(this@CalculatorActivity) { deg -> update { copy(lookAngleDeg = deg) }; render() } })
            number("Cant", Quantity.ANGLE, sh.cantDeg, { v -> if (v != null && abs(v) <= 90) update { copy(cantDeg = v) } },
                signed = true, sub = "Firearm leaning sideways. + is clockwise")
            number("Target speed", Quantity.SPEED, abs(sh.targetSpeedMph), { v ->
                if (v != null && v >= 0) update { copy(targetSpeedMph = if (moveRight) v else -v) }
            }, sub = "0 when the target is not moving")
            segmented("Target moves", listOf("← Left", "Right →"), if (moveRight) 1 else 0) { i ->
                moveRight = i == 1
                uiPrefs().edit().putBoolean("moveRight", moveRight).apply()
                update { copy(targetSpeedMph = abs(targetSpeedMph) * if (moveRight) 1 else -1) }
            }
        }

        fold("weather", "Weather", { weatherSummary(shot) }) {
            if (s.useDensityAltitude) {
                number("Density altitude", Quantity.ALTITUDE, DensityAltitude.of(sh.atmosphere), { v ->
                    if (v != null && v > -5000 && v < 30000) update { copy(atmosphere = DensityAltitude.atmosphere(v, atmosphere.temperatureF)) }
                }, signed = true, sub = "One number for how thin the air is")
                number("Temperature", Quantity.TEMPERATURE, sh.atmosphere.temperatureF, { v ->
                    if (v != null && v > -80 && v < 160) update {
                        val da = DensityAltitude.of(atmosphere)
                        copy(atmosphere = DensityAltitude.atmosphere(da, v))
                    }
                }, signed = true)
            } else {
                number("Temperature", Quantity.TEMPERATURE, sh.atmosphere.temperatureF, { v ->
                    if (v != null && v > -80 && v < 160) update { copy(atmosphere = atmosphere.copy(temperatureF = v)) }
                }, signed = true)
                number("Pressure", Quantity.PRESSURE, sh.atmosphere.pressureInHg, { v ->
                    if (v != null && v > 10 && v < 35) update { copy(atmosphere = atmosphere.copy(pressureInHg = v)) }
                })
                switch("Pressure was measured here", sh.atmosphere.pressureIsAbsolute,
                    sub = "On: station (absolute) pressure from a weather meter at your position. Off: sea-level pressure from a forecast") { c ->
                    update { copy(atmosphere = atmosphere.copy(pressureIsAbsolute = c)) }
                }
                number("Altitude", Quantity.ALTITUDE, sh.atmosphere.altitudeFt, { v ->
                    if (v != null && v > -2000 && v < 30000) update { copy(atmosphere = atmosphere.copy(altitudeFt = v)) }
                }, signed = true, sub = "Height above sea level")
                number("Humidity", Quantity.HUMIDITY, sh.atmosphere.humidityPct, { v ->
                    if (v != null && v >= 0 && v <= 100) update { copy(atmosphere = atmosphere.copy(humidityPct = v)) }
                })
                daView = info("Density altitude", "", sub = "Worked out from the values above")
            }
        }

        fold("fine", "Fine corrections", { fineSummary(shot) }) {
            switch("Spin drift", sh.spinDrift, sub = "Sideways drift caused by the bullet's spin") { c -> update { copy(spinDrift = c) } }
            val models = listOf(SpinDriftModel.MEASURED, SpinDriftModel.LITZ)
            choice("Spin drift method", listOf("Measured (default)", "Classic Litz"), models.indexOf(sh.spinDriftModel).coerceAtLeast(0)) { i ->
                update { copy(spinDriftModel = models[i]) }
            }
            stabilityView = info("Bullet stability (Sg)", "—", sub = "1.4 or more is stable")
            switch("Aerodynamic jump", sh.aeroJump, sub = "Small up / down shift caused by a crosswind") { c -> update { copy(aeroJump = c) } }
            switch("Coriolis", sh.coriolis, sub = "The Earth's rotation. Matters on very long shots") { c -> update { copy(coriolis = c) }; render() }
            val g = switch("Local gravity", sh.localGravity, sub = gravityText(sh)) { c -> update { copy(localGravity = c) }; render() }
            gravityView = (g.parent as? ViewGroup)?.findViewWithTag("sub")
            if (sh.coriolis || sh.localGravity) {
                number("Latitude", Quantity.ANGLE, sh.latitudeDeg, { v -> if (v != null && abs(v) <= 90) update { copy(latitudeDeg = v) } },
                    signed = true, sub = "+ north, − south. Used by Coriolis and local gravity",
                    action = "Use my location" to { getLatitude() })
            }
            if (sh.coriolis) {
                number("Direction of fire", Quantity.ANGLE, sh.azimuthDeg, { v -> if (v != null && v >= 0 && v <= 360) update { copy(azimuthDeg = v) } },
                    decimals = 0, sub = "Compass bearing (azimuth). 0 = north, 90 = east",
                    action = "Measure with compass" to { CompassTool.show(this@CalculatorActivity) { az -> update { copy(azimuthDeg = az) }; render() } })
            }
        }
        refreshLive()
        val restore = pendingScroll
        pendingScroll = null
        scroll.post {
            if (restore == null) scroll.scrollTo(0, y)
            else scroll.scrollTo(0, (restore * ((scroll.getChildAt(0)?.height ?: 0) - scroll.height).coerceAtLeast(0)).toInt())
        }
    }

    private fun windDirectionView(dial: WindDialView): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun line(sizeSp: Float, bold: Boolean, colorAttr: Int) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(colorAttr))
        }
        val wide = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(line(Type.SUB, false, MR.attr.colorOnSurfaceVariant).apply { text = "Wind is coming from" }, LinearLayout.LayoutParams(wide))
        col.addView(dial, LinearLayout.LayoutParams(dp(176), dp(176)).apply { topMargin = dp(6); bottomMargin = dp(4) })
        val hour = line(22f, true, MR.attr.colorOnSurface)
        val side = line(16f, false, MR.attr.colorOnSurface)
        col.addView(hour, LinearLayout.LayoutParams(wide))
        col.addView(side, LinearLayout.LayoutParams(wide))
        col.addView(line(14f, false, MR.attr.colorOnSurfaceVariant).apply {
            text = "Tap or drag the dial. 12 = from the target"; setPadding(0, dp(4), 0, 0)
        }, LinearLayout.LayoutParams(wide))
        windHourView = hour; windSideView = side

        fun reserve(widthPx: Int) {
            FixedText.fit(hour, WindWords.HOURS, widthPx)
            FixedText.fit(side, WindWords.SIDES, widthPx)
        }
        var fitted = form.contentWidthPx - dp(56)
        reserve(fitted)
        col.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w > 0 && w != fitted) { fitted = w; col.post { reserve(w) } }
        }
        return col
    }

    private fun refreshLive() {
        val r = sel.rifle; val l = sel.load; val b = sel.bullet
        stabilityView?.let { tv ->
            if (r == null || l == null || b == null) { tv.text = "—"; return@let }
            val mv = Engine.muzzleVelocity(l, shot.atmosphere.temperatureF)
            val sg = runCatching { Engine.stability(b, r, mv, shot.atmosphere) }.getOrNull()
            if (sg == null || sg.isNaN()) { tv.text = "—"; return@let }
            val note = when { sg < 1.0 -> "  not stable"; sg < 1.4 -> "  borderline"; else -> "" }
            Motion.setTextIfChanged(tv, Num.fixed(sg, 2) + note)
            val normal = (tv.getTag(R.id.content) as? Int) ?: tv.currentTextColor.also { tv.setTag(R.id.content, it) }
            tv.setTextColor(if (note.isNotEmpty()) ContextCompat.getColor(this, R.color.sol_warn) else normal)
        }
        daView?.let { tv ->
            val da = DensityAltitude.of(shot.atmosphere)
            Motion.setTextIfChanged(tv, Num.trim(Units.toDisplay(Quantity.ALTITUDE, da, settings), 0) + " " + Units.label(Quantity.ALTITUDE, settings))
        }
        windHourView?.let { Motion.setTextIfChanged(it, WindWords.hour(shot.windAngleDeg)) }
        windSideView?.let { Motion.setTextIfChanged(it, WindWords.side(shot.windAngleDeg)) }
        gravityView?.let { Motion.setTextIfChanged(it, gravityText(shot)) }
        ui.removeCallbacks(solveLater)
        ui.postDelayed(solveLater, 160)
    }

    private class ResultCell(val box: LinearLayout, val caption: TextView, val value: TextView, val unit: TextView, val sub: TextView)

    private fun resultCell(caption: String, colorRes: Int): ResultCell {
        val color = ContextCompat.getColor(this, colorRes)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(4), 0, dp(4), 0) }
        val cap = TextView(this).apply {
            text = caption; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); letterSpacing = 0.08f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
        }
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        val value = TextView(this).apply {
            text = "—"; setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isLandscape) 30f else 36f); typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            includeFontPadding = false
            setTextColor(color)
        }
        val unit = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(6), 0, 0, 0)
            setTextColor(themeColor(MR.attr.colorOnSurface))
        }
        line.addView(value); line.addView(unit)
        line.isBaselineAligned = true
        val sub = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER
            setTextColor(themeColor(MR.attr.colorOnSurface))
        }
        box.addView(cap); box.addView(line); box.addView(sub)
        return ResultCell(box, cap, value, unit, sub)
    }

    private fun buildResultBar() {
        val row = findViewById<LinearLayout>(R.id.resultRow)
        val stacked = isLandscape || resources.configuration.fontScale >= 1.5f
        row.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        elevCell = resultCell("ELEVATION", R.color.sol_elevation)
        windCell = resultCell("WINDAGE", R.color.sol_windage)
        for (c in listOf(elevCell, windCell)) {
            row.addView(c.box, if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (isLandscape) { topMargin = dp(4); bottomMargin = dp(8) }
            }
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        findViewById<MaterialButton>(R.id.calcButton).apply {
            setOnClickListener { calculate() }
            Motion.pressFeedback(this, pressedScale = 0.98f)
        }
    }

    private fun showHint(message: String, button: String) {
        findViewById<View>(R.id.resultRow).visibility = View.GONE
        findViewById<TextView>(R.id.resultHint).apply { text = message; visibility = View.VISIBLE }
        findViewById<MaterialButton>(R.id.calcButton).text = button
    }

    private fun solveNow() {
        if (isFinishing || isDestroyed) return
        val gen = ++generation
        val r = sel.rifle; val l = sel.load; val b = sel.bullet
        if (r == null) return showHint("Welcome. Start by adding your firearm.", "Add a firearm")
        if (l == null) return showHint("Next, add the ammo you shoot in ${r.name}.", "Add ammo")
        if (b == null) return showHint("This ammo's bullet isn't available. Import the bullet library or edit the ammo.", getString(R.string.calc_open_solution))
        CalcGuard.problem(r, l, b)?.let { return showHint(it, getString(R.string.calc_open_solution)) }
        val s = shot
        SolutionActivity.worker.execute {
            val result = runCatching { Engine.solve(r, l, b, s, maxRangeYd = s.distanceYd, stepYd = s.distanceYd.coerceAtLeast(1.0)) }
            runOnUiThread {
                if (gen != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { showResult(it, r, s) }
                    .onFailure { e -> showHint("Can't work out this shot: ${e.message ?: "check the inputs"}", getString(R.string.calc_open_solution)) }
            }
        }
    }

    private fun showResult(sol: Solution, r: Rifle, s: ShotInputs) {
        val st = settings
        val row = sol.at(s.distanceYd)
        val pm = st.usePlusMinus; val prec = st.outputPrecision
        val elev = Adjust.text(Adjust.dial(row.comeUpMil, r.elevationUnit, r.elevationCorrection), Adjust.Axis.ELEVATION, prec, r.elevationClick, pm)
        val elevClicks = Adjust.clicksText(clicks(row.comeUpMil, r.elevationUnit, r.elevationClick, r.elevationCorrection), Adjust.Axis.ELEVATION, pm)
        val wind = Adjust.text(Adjust.dial(row.windageMil, r.windageUnit, r.windageCorrection), Adjust.Axis.WINDAGE, prec, r.windageClick, pm)
        val windClicks = Adjust.clicksText(clicks(row.windageMil, r.windageUnit, r.windageClick, r.windageCorrection), Adjust.Axis.WINDAGE, pm)
        findViewById<View>(R.id.resultRow).visibility = View.VISIBLE
        Motion.setValue(elevCell.value, elev); elevCell.unit.text = Adjust.unitLabel(r.elevationUnit)
        elevCell.sub.text = WindWords.direction(elev) + "$elevClicks clicks"
        windCell.caption.text = if (s.windSpeed2Mph > 0) "WINDAGE (WIND 1)" else "WINDAGE"
        Motion.setValue(windCell.value, wind); windCell.unit.text = Adjust.unitLabel(r.windageUnit)
        windCell.sub.text = WindWords.direction(wind) + "$windClicks clicks"
        elevCell.box.contentDescription = "Elevation ${WindWords.direction(elev)}$elev ${elevCell.unit.text}, $elevClicks clicks"
        windCell.box.contentDescription = "Windage ${WindWords.direction(wind)}$wind ${windCell.unit.text}, $windClicks clicks"
        val extra = when {
            s.targetSpeedMph != 0.0 && s.windSpeed2Mph > 0 -> "Lead and the second wind are on the full solution."
            s.targetSpeedMph != 0.0 -> "The lead for the moving target is on the full solution."
            s.windSpeed2Mph > 0 -> "The second wind is on the full solution."
            else -> null
        }
        findViewById<TextView>(R.id.resultHint).apply {
            text = extra; visibility = if (extra == null) View.GONE else View.VISIBLE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (extra == null) 18f else 15f)
        }
        findViewById<MaterialButton>(R.id.calcButton).text = getString(R.string.calc_open_solution)
    }

    private fun checkEmpiricalLink() {
        if (empiricalChecked) return
        empiricalChecked = true
        val uriStr = EmpiricalHook.linkedUri(this) ?: return
        val app = applicationContext
        val root: View = findViewById(R.id.content)
        io.execute {
            val msg = try {
                val text = app.contentResolver.openInputStream(Uri.parse(uriStr))?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("file not readable")
                if (!EmpiricalHook.changed(app, text)) null
                else "Empirical: " + ReportText.short(EmpiricalHook.refresh(app, text))
            } catch (e: Exception) {
                EmpiricalHook.recordFailure(app, e.message ?: e.javaClass.simpleName)
                "Empirical refresh failed: ${e.message ?: e.javaClass.simpleName}"
            }
            if (msg != null) root.post {
                if (isFinishing || isDestroyed) return@post
                sel = Selected.resolve(store); render()
                Snackbar.make(root, msg, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private var resetHandled = false
        private var empiricalChecked = false
        private var launchShown = false
        private var noticesDone = false
        private var logoPlayTime = 0L
        private val io = Executors.newSingleThreadExecutor()
    }
}

object WindWords {
    val HOURS: List<String> = (1..12).map { "$it o'clock" }

    val SIDES: List<String> = listOf("head wind, in your face", "tail wind, from behind", "from your right", "from your left")

    fun hour(deg: Double): String = WindText.clock(deg).substringBefore(" · ")

    fun side(deg: Double): String = when (WindText.clock(deg).substringAfter(" · ")) {
        "head" -> SIDES[0]
        "tail" -> SIDES[1]
        "from right" -> SIDES[2]
        else -> SIDES[3]
    }

    fun direction(adjustText: String): String = when (adjustText.firstOrNull()) {
        'U' -> "Up · "
        'D' -> "Down · "
        'R' -> "Right · "
        'L' -> "Left · "
        else -> ""
    }
}
