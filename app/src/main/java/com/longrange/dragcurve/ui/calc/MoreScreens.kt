package com.longrange.dragcurve.ui.calc

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.BrowseKind
import com.longrange.dragcurve.exchange.SharedIntake
import com.longrange.dragcurve.sync.SyncScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

private object Cards {
    fun card(a: CalcActivity, form: Form, title: String, body: String, buttons: List<Pair<String, () -> Unit>>, badge: String? = null, done: Boolean = false) {
        val card = MaterialCardView(a).apply { radius = a.dp(16).toFloat(); cardElevation = 0f; strokeWidth = a.dp(1) }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSurfaceContainerHigh))
        card.strokeColor = card.themeColor(MR.attr.colorOutlineVariant)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(16), a.dp(14), a.dp(16), a.dp(14)) }
        card.addView(col)
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (badge != null) {
            head.addView(TextView(a).apply {
                text = if (done) "✓" else badge
                gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setBackgroundResource(R.drawable.badge_bg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(a, R.color.calc_accent))
                setTextColor(androidx.core.content.ContextCompat.getColor(a, R.color.calc_on_accent))
                contentDescription = if (done) "Done" else "Step $badge"
            }, LinearLayout.LayoutParams(a.dp(36), a.dp(36)).apply { marginEnd = a.dp(12) })
        }
        head.addView(TextView(a).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColor(MR.attr.colorOnSurface))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(head)
        col.addView(TextView(a).apply {
            text = body; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setLineSpacing(0f, 1.12f)
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            setPadding(0, a.dp(8), 0, a.dp(4))
        })
        buttons.forEachIndexed { i, (label, go) ->
            val b = if (i == 0) MaterialButton(a) else MaterialButton(a, null, MR.attr.materialButtonOutlinedStyle)
            b.text = label
            b.setOnClickListener { go() }
            col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = a.dp(6) })
        }
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(a.dp(12), a.dp(6), a.dp(12), a.dp(6))
        }
        form.addView(card)
    }
}

class MoreActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var tabBar: View
    override val snackAnchor: View? get() = if (::tabBar.isInitialized) tabBar else null
    private val syncListener: () -> Unit = { if (!isFinishing && !isDestroyed) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabBar = TabNav.install(this, R.layout.calc_activity_scroll, Tab.MORE)
        setupToolbar(getString(R.string.calc_tab_more), up = false)
        form = contentForm()
    }

    override fun onResume() { super.onResume(); SyncScheduler.addListener(syncListener); render() }
    override fun onPause() { SyncScheduler.removeListener(syncListener); super.onPause() }

    private fun render() {
        form.settings = settings
        form.clear()
        val lib = store.libraryBullets().count { it.collection.isEmpty() }
        val comp = store.libraryBullets().count { it.collection == com.longrange.dragcurve.exchange.ComponentLibrary.COLLECTION && it.replacedBy.isEmpty() }
        val sync = SyncScheduler.status()
        val folder = BackupFolder.status(this)
        form.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)) })
        form.group {
            nav(getString(R.string.calc_libraries), "", sub = (if (comp > 0) "Component library ($comp bullets) · " else "") +
                "bullet library ${if (lib == 0) "not imported" else "($lib bullets)"}, profile databases, importing from other apps") {
                startActivity(Intent(this@MoreActivity, LibrariesActivity::class.java))
            }
            val shared = com.longrange.dragcurve.sync.SharedSync.status(this@MoreActivity)
            if (shared.on) {
                nav("Sync", "", sub = "With ${shared.fileName} · " + (if (shared.lastSyncAt == 0L) "not synced yet" else
                    "last synced " + android.text.format.DateUtils.getRelativeTimeSpanString(shared.lastSyncAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)) +
                    (if (!shared.lastOk) "\n⚠ needs a look" else "")) {
                    startActivity(Intent(this@MoreActivity, BackupSyncActivity::class.java).putExtra(BackupSyncActivity.EXTRA_ACTION, BackupSyncActivity.ACTION_SYNC))
                }
            }
            val backup = listOf(
                "shared sync file " + if (shared.on) "on" else "off",
                "Google Drive sync " + if (sync.enabled) "on" else "off",
                "backup folder " + if (folder.on) "on" else "off",
            ).joinToString(" · ")
            nav(getString(R.string.calc_backup_sync), "", sub = backup.replaceFirstChar { it.uppercase() } +
                (if (folder.error.isNotEmpty() || (sync.enabled && !sync.lastOk)) "\n⚠ needs a look" else "")) {
                startActivity(Intent(this@MoreActivity, BackupSyncActivity::class.java))
            }
            nav(getString(R.string.calc_settings), "", sub = "Text size, colours, units, how the answer is shown") {
                startActivity(Intent(this@MoreActivity, SettingsActivity::class.java))
            }
            nav(getString(R.string.calc_help), "", sub = "How do I… — short answers with a button to the right screen") {
                startActivity(Intent(this@MoreActivity, HelpActivity::class.java))
            }
        }
        form.note(getString(R.string.app_name) + ". Your data stays on this phone and in your own Google account; nothing goes to any other server.")
    }
}

class HelpActivity : CalcActivity() {
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_help))
        form = contentForm()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        form.settings = settings
        form.clear()
        val sel = Selected.resolve(store)
        fun editAmmo(): Pair<String, () -> Unit>? = sel.load?.let { l ->
            "Edit \"${l.name}\"" to { startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_ID, l.id).putExtra(LoadEditActivity.EXTRA_RIFLE, l.rifleId)) }
        }
        val gear: Pair<String, () -> Unit> = getString(R.string.calc_tab_gear) to { TabNav.open(this, Tab.GEAR) }
        val libraries: Pair<String, () -> Unit> = getString(R.string.calc_libraries) to { startActivity(Intent(this, LibrariesActivity::class.java)) }
        val backup: Pair<String, () -> Unit> = getString(R.string.calc_backup_sync) to { startActivity(Intent(this, BackupSyncActivity::class.java)) }

        form.header("How do I…")
        Cards.card(this, form, "Add a firearm or ammo",
            "Open My gear and tap Add Firearm. Each firearm card has its own Add Ammo. The editor opens straight away: fill in what you know and tap the tick at the top to save.",
            listOf(getString(R.string.calc_add_firearm) to { startActivity(Intent(this, RifleEditActivity::class.java)) }, gear))
        Cards.card(this, form, "Pick a bullet or enter a BC",
            "In the ammo editor, tap Bullet. One search covers your bullet library, any other imported collections and your own bullets, each labelled with where it comes from. " +
                "No match, or no library imported? Tap \"Enter my own BC\" at the bottom and type the BC from the box.",
            listOfNotNull(editAmmo(), gear))
        Cards.card(this, form, "Confirm the zero",
            "Open the ammo and look at Zero (sight-in): the distance you zeroed at and, if the group sat off the aim point, how far. Tap the tick to save: that confirms the zero and the ⚠ note on the ammo goes away.",
            listOfNotNull(editAmmo(), gear))
        Cards.card(this, form, "Calibrate (true) the velocity",
            "Shoot at a long distance and note the dial that really hit. On the Calculator tap \"Full solution & table\", then Calibrate. Enter the distance and what you dialled: Skyward works out the muzzle velocity that matches and can save it to the ammo.",
            listOfNotNull(if (sel.complete && sel.load?.let { !LoadFlags.mvNeeded(it) } == true) "Open Calibrate" to {
                startActivity(Intent(this, SolutionActivity::class.java).putExtra(SolutionActivity.EXTRA_TAB, SolutionActivity.TAB_CALIBRATE))
            } else null, getString(R.string.calc_tab_calculator) to { TabNav.open(this, Tab.CALCULATOR) }))
        Cards.card(this, form, "Check my hit chance, or compare ammo",
            "Open the Analysis tab. Hit probability simulates thousands of shots at a target, using how well you know the range, the wind and your velocity, and shows what causes the misses. " +
                "TNF compare puts saved ammo, bullets at a velocity, or a velocity and BC you type in side by side, to see which drifts least in the wind.",
            listOf(getString(R.string.calc_tab_analysis) to { TabNav.open(this, Tab.ANALYSIS) }))
        Cards.card(this, form, "Bring in profiles from Empirical Precision",
            SharedIntake.HELP + " Skyward keeps its own copy and opens Browse database: tick what you want and tap Import. " +
                "Or link the backup file once under Libraries, and Skyward keeps those firearms and ammo up to date. This is optional.",
            listOf(libraries, ProfileBrowserActivity.MENU_TITLE to { ProfileBrowserActivity.open(this, BrowseKind.FIREARM, null) }))
        Cards.card(this, form, "Import a bullet library",
            "Libraries → \"Import a bullet library…\", then pick the Skyward bullet library file. It is optional: without one you can always enter the BC from the box.",
            listOf(libraries))
        Cards.card(this, form, "Back up, or move to a new phone",
            "Android backs Skyward up to your Google account by itself: on a new phone, sign in with the same Google account and install Skyward, and your gear comes back. " +
                "For a file of your own, use Backup & sync: turn on the backup folder, or Export everything and copy the file across. On the other phone use Backup & sync → Import files…",
            listOf(backup))

        form.header("How your data is kept safe")
        form.note(DataSafetyText.POINTS.joinToString("\n\n") { "•  $it" })
        form.header("Getting started")
        form.group {
            nav("Show the welcome guide again", "") { startActivity(Intent(this@HelpActivity, GuideActivity::class.java)) }
        }
    }
}

class GuideActivity : CalcActivity() {
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar("Welcome to Skyward", up = false)
        form = contentForm()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })
    }

    override fun onResume() { super.onResume(); render() }

    private fun close() { InstallState.guideDone(this); finish() }

    private fun render() {
        form.settings = settings
        form.clear()
        val rifles = store.rifles()
        val sel = Selected.resolve(store)
        form.note("Three short steps to your first solution. You can skip this and come back to it from More → Help.")
        Cards.card(this, form, "Add your firearm",
            "Its name, barrel twist, sight height and the value of one scope click.",
            listOf(getString(R.string.calc_add_firearm) to { startActivity(Intent(this, RifleEditActivity::class.java)) }),
            badge = "1", done = rifles.isNotEmpty())
        val rifleId = sel.rifle?.id ?: rifles.firstOrNull()?.id
        Cards.card(this, form, "Add your ammo",
            "Search for the bullet, or simply type the BC from the box. Then the muzzle velocity and the distance you zeroed at.",
            if (rifleId == null) emptyList() else listOf(getString(R.string.calc_add_ammo) to {
                startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId))
            }),
            badge = "2", done = store.loads(null).isNotEmpty())
        Cards.card(this, form, "Optional extras",
            "Bring in firearms and ammo from Empirical Precision, import a bullet library, and turn on an automatic backup. None of this is needed for a solution.",
            listOf(
                getString(R.string.calc_libraries) to { startActivity(Intent(this, LibrariesActivity::class.java)) },
                getString(R.string.calc_backup_sync) to { startActivity(Intent(this, BackupSyncActivity::class.java)) },
            ),
            badge = "3")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        row.addView(MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply { text = "Skip"; setOnClickListener { close() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(MaterialButton(this).apply { text = "Done"; setOnClickListener { close() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(row)
    }
}

class SnapshotsActivity : CalcActivity() {
    private lateinit var form: Form

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar("Earlier copies")
        form = contentForm()
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        val app = applicationContext
        LibraryImport.background(this, { SafetyCopies.snapshots(app).list() }) { r -> render(r.getOrDefault(emptyList())) }
    }

    private fun time(t: Long): String =
        SimpleDateFormat(if (DateFormat.is24HourFormat(this)) "EEE d MMM yyyy, HH:mm" else "EEE d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(t))

    private fun counts(c: com.longrange.dragcurve.store.SafetySnapshots.Counts): String = listOfNotNull(
        "${c.firearms} firearm${if (c.firearms == 1) "" else "s"}", "${c.ammo} ammo",
        "${c.bullets} own bullet${if (c.bullets == 1) "" else "s"}",
        if (c.cards > 0) "${c.cards} target card${if (c.cards == 1) "" else "s"}" else null,
        if (c.library > 0) "${c.library} library bullets" else "no bullet library",
    ).joinToString(" · ")

    private fun render(list: List<com.longrange.dragcurve.store.SafetySnapshots.Info>) {
        form.settings = settings
        form.clear()
        form.note("Skyward keeps the last ${SafetyCopies.snapshots(this).keep} copies of your gear on this phone: one a day, and one before every import, " +
            "Empirical refresh, bullet-library removal, and Google Drive sync that deletes something. Restoring one first saves what you have now as a copy, so it can be undone.")
        form.header("Copies, newest first")
        form.group {
            if (list.isEmpty()) info("No copies yet", "", sub = "The first one is taken today")
            list.forEach { s ->
                nav(time(s.takenAt), "", sub = s.reason + "\n" + counts(s.counts)) { confirm(s) }
            }
            nav("Take a copy now", "") {
                val app = applicationContext
                LibraryImport.background(this@SnapshotsActivity, { Stores.get(app).safetySnapshot("Copy you took") }) { load() }
            }
        }
        form.note("These copies stay on this phone (they are not in Android's backup). For a copy that survives losing the phone, use the backup folder or Google Drive sync.")
    }

    private fun confirm(s: com.longrange.dragcurve.store.SafetySnapshots.Info) {
        MaterialAlertDialogBuilder(this).setTitle("Restore this copy?")
            .setMessage("${time(s.takenAt)}\n${s.reason}\n${counts(s.counts)}\n\nYour firearms, ammo, bullets, target cards, bullet library and settings go back to how they were then. " +
                "What you have now is saved as a copy first, so you can come back to it.")
            .setPositiveButton("Restore") { _, _ ->
                val app = applicationContext
                LibraryImport.background(this, { SafetyCopies.restore(app, s.id) }) { r ->
                    r.onSuccess { alert("Restored", "Your gear is back to the copy from ${time(s.takenAt)}. The copy taken just before restoring is at the top of the list.") }
                        .onFailure { e -> alert("Couldn't restore", e.message ?: e.toString()) }
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }
}
