package com.longrange.dragcurve.ui.calc

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateFormat as AndroidDateFormat
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
import com.longrange.dragcurve.model.BarrelCare
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.ShotRecord
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

object CleanText {
    fun day(at: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(at))

    fun times(n: Int) = if (n == 1) "once" else if (n == 2) "twice" else "${ShotText.count(n)} times"

    fun line(s: BarrelCare.Status): String? {
        if (s.lastCleaned == null && s.every == null) return null
        val since = if (s.lastCleaned == null) "${ShotText.count(s.sinceCleaning)} rounds, no cleaning logged"
            else "${ShotText.count(s.sinceCleaning)} rounds since cleaning"
        return since + when {
            s.due -> " · due for a cleaning"
            s.left != null -> " · ${ShotText.count(s.left!!)} to go"
            else -> ""
        }
    }

    fun status(s: BarrelCare.Status): String? = when {
        s.every == null -> null
        s.due -> "Due for a cleaning. You clean it every ${ShotText.count(s.every)} rounds."
        else -> "${ShotText.count(s.left!!)} rounds until the next cleaning (every ${ShotText.count(s.every)})."
    }
}

class BarrelCleaningActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var rifleId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rifleId = intent.getStringExtra(EXTRA_RIFLE) ?: run { finish(); return }
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(R.string.calc_barrel_cleaning))
        form = contentForm()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        val rifle = store.rifles().firstOrNull { it.id == rifleId } ?: run { finish(); return }
        supportActionBar?.subtitle = rifle.name
        form.settings = settings
        form.clear()
        val list = store.shotRecords(rifleId)
        val st = BarrelCare.status(list, rifle.cleanEveryRounds)

        form.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        form.group {
            val col = LinearLayout(this@BarrelCleaningActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(8), 0, dp(12)) }
            col.addView(TextView(this@BarrelCleaningActivity).apply {
                text = ShotText.count(st.sinceCleaning); gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f); typeface = Typeface.DEFAULT_BOLD
                setTextColor(themeColor(if (st.due) MR.attr.colorError else MR.attr.colorOnSurface))
                contentDescription = "${ShotText.count(st.sinceCleaning)} rounds since the last cleaning"
            })
            col.addView(TextView(this@BarrelCleaningActivity).apply {
                text = st.lastCleaned?.let { "rounds since the last cleaning, ${ShotText.time(this@BarrelCleaningActivity, it.recordedAt)}" }
                    ?: "rounds fired. No cleaning logged yet."
                gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
            })
            CleanText.status(st)?.let { s ->
                col.addView(TextView(this@BarrelCleaningActivity).apply {
                    text = s; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, dp(6), 0, 0)
                    if (st.due) typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColor(if (st.due) MR.attr.colorError else MR.attr.colorOnSurfaceVariant))
                })
            }
            col.addView(MaterialButton(this@BarrelCleaningActivity).apply {
                text = "Cleaned now"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                minHeight = dp(64); minimumHeight = dp(64); insetTop = 0; insetBottom = 0
                contentDescription = "Log a cleaning now"
                setOnClickListener { log(System.currentTimeMillis(), "") }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16); marginStart = dp(4); marginEnd = dp(4)
            })
            col.addView(MaterialButton(this@BarrelCleaningActivity, null, MR.attr.materialButtonOutlinedStyle).apply {
                text = "Cleaned earlier…"; minHeight = dp(56); minimumHeight = dp(56)
                setOnClickListener { askCleaning(null) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8); marginStart = dp(4); marginEnd = dp(4)
            })
            custom(col)
        }
        form.note("Rounds since cleaning counts the rounds in this firearm's round count logged after the latest cleaning. " +
            "Each cleaning is saved with its date and time, and goes with your firearm into backups and Google Drive sync.")

        form.header("Reminder")
        form.group {
            nav("Clean every", rifle.cleanEveryRounds?.let { "${ShotText.count(it)} rounds" } ?: "Off",
                sub = "Shows when the barrel is due for a cleaning") { askInterval() }
        }

        val cleans = BarrelCare.cleanings(list).sortedByDescending { it.recordedAt }
        form.header(if (cleans.isEmpty()) "History" else "History · cleaned ${CleanText.times(cleans.size)}")
        if (cleans.isEmpty()) form.note("Tap Cleaned now after you clean the barrel. If you cleaned it before today, use Cleaned earlier with the date and time.")
        else form.group {
            cleans.forEach { c ->
                val before = BarrelCare.roundsBefore(list, c)
                nav("Cleaned", "", sub = listOfNotNull(ShotText.time(this@BarrelCleaningActivity, c.recordedAt),
                    c.note.takeIf { it.isNotBlank() }, "${ShotText.count(before)} rounds before").joinToString(" · ")) { entryMenu(c) }
            }
        }
    }

    private fun log(at: Long, note: String) {
        val rec = store.saveShotRecord(BarrelCare.cleaning(rifleId, at, note))
        render()
        val root = findViewById<View>(R.id.content) ?: return
        Snackbar.make(root, "Cleaning logged ${ShotText.time(this, rec.recordedAt)}", 6000)
            .setAction("Undo") { store.deleteShotRecord(rec.id); render() }.show()
    }

    private fun askCleaning(old: ShotRecord?) {
        val cal = Calendar.getInstance().apply { old?.let { timeInMillis = it.recordedAt }; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dateBtn = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle)
        val timeBtn = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle)
        fun show() {
            dateBtn.text = CleanText.day(cal.timeInMillis)
            timeBtn.text = AndroidDateFormat.getTimeFormat(this).format(cal.time)
        }
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> cal.set(y, m, d); show() }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, min -> cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, min); show() },
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), AndroidDateFormat.is24HourFormat(this)).show()
        }
        row.addView(dateBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginEnd = dp(8) })
        row.addView(timeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        show()
        val note = EditText(this).apply {
            hint = "Note (optional), e.g. copper solvent, 20 patches"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; maxLines = 1
            old?.let { setText(it.note) }
        }
        box.addView(row); box.addView(note)
        MaterialAlertDialogBuilder(this).setTitle(if (old == null) "When did you clean it?" else "Change this cleaning")
            .setMessage("Rounds logged after this date and time count as rounds since cleaning.")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val at = cal.timeInMillis
                if (at > System.currentTimeMillis() + 60_000) { alert("Date and time", "Pick a date and time that has already passed."); return@setPositiveButton }
                if (old == null) log(at, note.text.toString())
                else { store.saveShotRecord(old.copy(recordedAt = at, note = note.text.toString().trim())); render() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askInterval() {
        val rifle = store.rifles().firstOrNull { it.id == rifleId } ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val n = EditText(this).apply {
            hint = "Rounds, e.g. 200"; inputType = InputType.TYPE_CLASS_NUMBER; maxLines = 1
            rifle.cleanEveryRounds?.let { setText(it.toString()) }
        }
        box.addView(n)
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Clean every")
            .setMessage("After this many rounds since the last cleaning, the firearm shows as due for a cleaning.")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = BarrelCare.interval(n.text.toString())
                if (v == null && n.text.isNotBlank()) { alert("Clean every", "Type a whole number of rounds from 1 to 100,000, or leave it empty for no reminder."); return@setPositiveButton }
                saveInterval(v)
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (rifle.cleanEveryRounds != null) dlg.setNeutralButton("Turn off") { _, _ -> saveInterval(null) }
        dlg.create().apply { setOnShowListener { n.requestFocus(); Form.showKeyboard(n) } }.show()
    }

    private fun saveInterval(v: Int?) {
        val rifle = store.rifles().firstOrNull { it.id == rifleId } ?: return
        if (rifle.cleanEveryRounds != v) store.saveRifle(rifle.copy(cleanEveryRounds = v))
        render()
    }

    private fun entryMenu(c: ShotRecord) {
        MaterialAlertDialogBuilder(this).setTitle("Cleaned")
            .setMessage(listOfNotNull("Logged for ${ShotText.time(this, c.recordedAt)}", c.note.takeIf { it.isNotBlank() }).joinToString("\n"))
            .setPositiveButton("Change") { _, _ -> askCleaning(c) }
            .setNeutralButton(getString(R.string.calc_delete)) { _, _ ->
                store.deleteShotRecord(c.id)
                render()
                UndoDelete.show(this, "Deleted the cleaning of ${CleanText.day(c.recordedAt)}", Libraries(shots = listOf(c)), null, null) { render() }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object {
        const val EXTRA_RIFLE = "rifle"
        fun open(c: Context, rifleId: String) = c.startActivity(Intent(c, BarrelCleaningActivity::class.java).putExtra(EXTRA_RIFLE, rifleId))
    }
}
