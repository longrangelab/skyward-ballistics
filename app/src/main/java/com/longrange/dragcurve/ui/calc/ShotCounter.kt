package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.longrange.dragcurve.R
import com.longrange.dragcurve.analysis.TnfCompare
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ShotRecord
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import com.google.android.material.R as MR

object AmmoTnf {
    data class Value(val tnf: Double, val g7: Double, val equivalent: Boolean)

    private val calc = TnfCompare.Calc()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ammo-tnf").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val done = ConcurrentHashMap<Any, Value>()
    private val failed: MutableSet<Any> = ConcurrentHashMap.newKeySet()
    private val pending: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    private fun key(l: Load, b: Bullet): Any = Pair(
        b.copy(id = "", name = "", maker = "", notes = "", updatedAt = 0, provenance = null, externalIds = emptyMap(),
            collection = "", category = "", caliber = "", source = "", libraryOrder = null),
        Triple(l.muzzleVelocityFps, Triple(l.dragModelOverride, l.bcPointsOverride, l.bcSeason), l.dragScaleFactor),
    )

    fun get(l: Load, b: Bullet?, onReady: (() -> Unit)? = null): Value? {
        if (b == null || LoadFlags.mvNeeded(l)) return null
        val model = l.dragModelOverride ?: b.dragModel
        val rows = l.bcPointsOverride ?: b.bcPoints
        val mv = l.muzzleVelocityFps
        if (model == DragModel.G7 && rows.size == 1 && l.bcSeason == null && l.dragScaleFactor == 1.0)
            return rows[0].bc.takeIf { it > 0 }?.let { Value(mv * it, it, false) }
        if (model == DragModel.CUSTOM && b.dragCurve.size < 2) return null
        if (model != DragModel.CUSTOM && rows.none { it.bc > 0 }) return null
        val k = key(l, b)
        done[k]?.let { return it }
        if (k in failed) return null
        if (pending.add(k)) io.execute {
            runCatching { calc.g7(b, TnfCompare.load(b, mv).copy(dragModelOverride = l.dragModelOverride, bcPointsOverride = l.bcPointsOverride,
                bcSeason = l.bcSeason, dragScaleFactor = l.dragScaleFactor), mv) }
                .onSuccess { (bc, eq) -> done[k] = Value(mv * bc, bc, eq) }
                .onFailure { failed += k }
            pending.remove(k)
            onReady?.let { main.post(it) }
        }
        return null
    }

    fun short(v: Value): String = "TNF " + String.format(Locale.US, "%.0f", v.tnf)

    fun detail(v: Value): String = "G7 BC " + String.format(Locale.US, "%.3f", v.g7) +
        (if (v.equivalent) " (the single G7 BC that drifts the same at 1000 yd)" else "") + ". Higher TNF = less wind drift."
}

object ShotText {
    fun time(c: Context, at: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(at))

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    fun summary(c: Context, list: List<ShotRecord>): String? {
        if (list.isEmpty()) return null
        val last = list.maxByOrNull { it.recordedAt }!!
        return "${count(list.sumOf { it.count })} rounds · last ${signed(last.count)}, ${time(c, last.recordedAt)}"
    }

    fun signed(n: Int) = (if (n >= 0) "+" else "−") + count(kotlin.math.abs(n))
}

class ShotCounterActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var rifleId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rifleId = intent.getStringExtra(EXTRA_RIFLE) ?: run { finish(); return }
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_round_count))
        form = contentForm()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val rifle = store.rifles().firstOrNull { it.id == rifleId } ?: run { finish(); return }
        supportActionBar?.subtitle = rifle.name
        form.settings = settings
        form.clear()
        val list = store.shotRecords(rifleId)
        val total = list.sumOf { it.count }

        form.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        form.group {
            val col = LinearLayout(this@ShotCounterActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, dp(12)) }
            col.addView(TextView(this@ShotCounterActivity).apply {
                text = ShotText.count(total); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f); typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(MR.attr.colorOnSurface))
                contentDescription = "${ShotText.count(total)} rounds fired"
            })
            col.addView(TextView(this@ShotCounterActivity).apply {
                text = if (list.isEmpty()) "rounds fired. Nothing logged yet." else
                    "rounds fired · last logged ${ShotText.time(this@ShotCounterActivity, list.maxOf { it.recordedAt })}"
                gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            })
            val quick = LinearLayout(this@ShotCounterActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16), 0, 0) }
            for (n in QUICK) {
                quick.addView(MaterialButton(this@ShotCounterActivity).apply {
                    text = "+$n"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    minHeight = dp(64); minimumHeight = dp(64); insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0; maxLines = 1
                    setPaddingRelative(dp(4), paddingTop, dp(4), paddingBottom)
                    contentDescription = "Add $n shots"
                    setOnClickListener { add(n, "") }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
            }
            col.addView(quick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            col.addView(MaterialButton(this@ShotCounterActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = "Exact count…"; minHeight = dp(56); minimumHeight = dp(56)
                setOnClickListener { askExact() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4)
            })
            custom(col)
        }
        form.note("Each entry is saved with the date and time you logged it, and goes with your firearm into backups and Google Drive sync.")

        form.header("History")
        if (list.isEmpty()) form.note("Tap a button above after a range session. To start from a rifle that has been shot before, use Exact count with the count so far.")
        else form.group {
            var running = total
            list.sortedByDescending { it.recordedAt }.forEach { s ->
                val after = running
                running -= s.count
                nav(ShotText.signed(s.count), "", sub = listOfNotNull(ShotText.time(this@ShotCounterActivity, s.recordedAt),
                    s.note.takeIf { it.isNotBlank() }, "total ${ShotText.count(after)}").joinToString(" · ")) { entryMenu(s) }
            }
        }
    }

    private fun add(n: Int, note: String) {
        val rec = store.saveShotRecord(ShotRecord(id = "", rifleId = rifleId, count = n, recordedAt = System.currentTimeMillis(), note = note.trim()))
        render()
        val root = findViewById<View>(R.id.content) ?: return
        Snackbar.make(root, "Added ${ShotText.signed(n)} rounds at ${ShotText.time(this, rec.recordedAt)}", 6000)
            .setAction("Undo") { store.deleteShotRecord(rec.id); render() }.show()
    }

    private fun askExact(old: ShotRecord? = null) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val count = EditText(this).apply {
            hint = "Rounds"; inputType = InputType.TYPE_CLASS_NUMBER; maxLines = 1
            old?.let { setText(it.count.toString()) }
        }
        val note = EditText(this).apply {
            hint = "Note (optional), e.g. count when bought"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; maxLines = 1
            old?.let { setText(it.note) }
        }
        box.addView(count); box.addView(note)
        val dlg = MaterialAlertDialogBuilder(this).setTitle(if (old == null) "Add an exact count" else "Change this entry")
            .setMessage(if (old == null) "Logged with today's date and time." else "Logged ${ShotText.time(this, old.recordedAt)}. The time stays the same.")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = count.text.toString().trim().toIntOrNull()
                if (n == null || n <= 0 || n > 1_000_000) { alert("Rounds", "Type a whole number of rounds, 1 or more."); return@setPositiveButton }
                if (old == null) add(n, note.text.toString())
                else { store.saveShotRecord(old.copy(count = n, note = note.text.toString().trim())); render() }
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        dlg.setOnShowListener { count.requestFocus(); Form.showKeyboard(count) }
        dlg.show()
    }

    private fun entryMenu(s: ShotRecord) {
        MaterialAlertDialogBuilder(this).setTitle("${ShotText.signed(s.count)} rounds")
            .setMessage(listOfNotNull("Logged ${ShotText.time(this, s.recordedAt)}", s.note.takeIf { it.isNotBlank() }).joinToString("\n"))
            .setPositiveButton("Change") { _, _ -> askExact(s) }
            .setNeutralButton(getString(R.string.calc_delete)) { _, _ ->
                store.deleteShotRecord(s.id)
                render()
                UndoDelete.show(this, "Deleted ${ShotText.signed(s.count)} rounds", Libraries(shots = listOf(s)), null, null) { render() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object {
        const val EXTRA_RIFLE = "rifle"
        val QUICK = listOf(5, 10, 20, 50)
        fun open(c: Context, rifleId: String) = c.startActivity(Intent(c, ShotCounterActivity::class.java).putExtra(EXTRA_RIFLE, rifleId))
    }
}
