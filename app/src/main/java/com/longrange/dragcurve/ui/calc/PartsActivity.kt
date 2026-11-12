package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.ImportResult
import com.longrange.dragcurve.exchange.Part
import com.longrange.dragcurve.exchange.PartKind
import com.longrange.dragcurve.exchange.PartStatus
import com.longrange.dragcurve.exchange.Parts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

class PartsActivity : CalcActivity() {
    private lateinit var form: Form
    private var importing = false
    private var file: ImportResult? = null
    private var fileName = ""
    private var parts: List<Part> = emptyList()
    private val picked = LinkedHashSet<String>()
    private val open = HashSet<PartKind>()
    private val switches = HashMap<String, MaterialSwitch>()
    private val groupSwitches = HashMap<PartKind, MaterialSwitch>()
    private var countView: TextView? = null
    private var actionButtons = ArrayList<MaterialButton>()
    private var syncing = false

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { writeExport(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importing = intent.getBooleanExtra(EXTRA_IMPORT, false)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(if (importing) "Import chosen parts" else "Export chosen parts")
        form = contentForm()
        if (importing) {
            val p = pending ?: run { finish(); return }
            file = p.first; fileName = p.second
            parts = Parts.list(p.first.libs, store, p.first.kind)
        } else {
            parts = Parts.list(Parts.everything(store))
        }
        val saved = savedInstanceState?.getStringArrayList(K_PICKED)
        if (saved != null) picked += saved
        else if (importing) parts.filter { it.status != PartStatus.SAME }.forEach { picked += it.key }
        savedInstanceState?.getStringArrayList(K_OPEN)?.forEach { n -> PartKind.entries.firstOrNull { it.name == n }?.let { open += it } }
            ?: PartKind.entries.filter { k -> parts.count { it.kind == k } <= 30 }.forEach { open += it }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(K_PICKED, ArrayList(picked))
        outState.putStringArrayList(K_OPEN, ArrayList(open.map { it.name }))
    }

    override fun onDestroy() {
        if (isFinishing && importing) pending = null
        super.onDestroy()
    }

    private fun render() {
        form.settings = settings
        form.clear()
        switches.clear(); groupSwitches.clear(); actionButtons.clear()
        if (parts.isEmpty()) {
            form.note(if (importing) "This file holds nothing that can be imported." else "There is nothing in Skyward to export yet.")
            return
        }
        form.note(
            if (importing) "From $fileName. Pick what to bring into Skyward. Only adds and updates: nothing here is deleted, " +
                "and where both have a record the most recently edited copy is kept. Ammo brings its firearm and bullet along when they aren't here yet."
            else "Pick what to put in the file. Ammo takes its firearm and bullet along, a target card its firearm and ammo, " +
                "and round counts their firearm, so the file always imports cleanly. The file holds no deletions: importing it only adds and updates."
        )
        countView = form.note("")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        row.addView(outlined("Select all") { setAll(true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(outlined("Select none") { setAll(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(row)

        for (kind in PartKind.entries) {
            val list = parts.filter { it.kind == kind }
            if (list.isEmpty()) continue
            form.foldable(kind.label, { groupSummary(kind) }, kind in open, { o -> if (o) open += kind else open -= kind }) {
                groupSwitches[kind] = switch("All ${kind.label.lowercase()}", list.all { it.key in picked }, sub = "${list.size}") { on ->
                    if (syncing) return@switch
                    syncing = true
                    list.forEach { p -> if (on) picked += p.key else picked -= p.key; switches[p.key]?.isChecked = on }
                    syncing = false
                    updateCount()
                }
                for (p in list) {
                    val sub = listOfNotNull(p.summary.ifBlank { null }, p.status?.label).joinToString(" · ")
                    switches[p.key] = switch(p.title, p.key in picked, sub = sub.ifBlank { null }) { on ->
                        if (syncing) return@switch
                        if (on) picked += p.key else picked -= p.key
                        syncing = true
                        groupSwitches[kind]?.isChecked = list.all { it.key in picked }
                        syncing = false
                        updateCount()
                    }
                }
            }
        }
        val go = MaterialButton(this).apply {
            setOnClickListener { if (importing) confirmImport() else confirmExport() }
        }
        actionButtons += go
        form.addView(go.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(dp(16), dp(16), dp(16), dp(24)) }
        })
        updateCount()
    }

    private fun outlined(text: String, onClick: () -> Unit) = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
        this.text = text; setOnClickListener { onClick() }
    }

    private fun groupSummary(kind: PartKind): String {
        val list = parts.filter { it.kind == kind }
        return "${list.count { it.key in picked }} of ${list.size} chosen"
    }

    private fun setAll(on: Boolean) {
        if (on) parts.forEach { picked += it.key } else picked.clear()
        syncing = true
        switches.forEach { (k, s) -> s.isChecked = k in picked }
        groupSwitches.forEach { (kind, s) -> s.isChecked = parts.filter { it.kind == kind }.all { it.key in picked } }
        syncing = false
        updateCount()
    }

    private fun updateCount() {
        val n = picked.size
        val byKind = PartKind.entries.mapNotNull { k -> parts.count { it.kind == k && it.key in picked }.takeIf { it > 0 }?.let { "$it ${k.label.lowercase()}" } }
        countView?.text = if (n == 0) "Nothing chosen yet." else "Chosen: " + byKind.joinToString(", ") + "."
        for (b in actionButtons) {
            b.text = if (importing) "Import $n chosen" else "Export $n chosen…"
            b.isEnabled = n > 0
        }
    }

    private fun alongText(extra: List<Part>): String {
        if (extra.isEmpty()) return ""
        val names = extra.take(8).joinToString(", ") { "${it.title} (${singular(it.kind)})" } + if (extra.size > 8) " and ${extra.size - 8} more" else ""
        return "\n\nAlso included because chosen items need them: $names."
    }

    private fun singular(k: PartKind) = when (k) {
        PartKind.FIREARM -> "firearm"; PartKind.AMMO -> "ammo"; PartKind.BULLET -> "bullet"; PartKind.LIBRARY -> "library bullet"
        PartKind.CARD -> "target card"; PartKind.ROUNDS -> "round counts"
    }

    private fun confirmExport() {
        val sel = Parts.select(Parts.everything(store), picked)
        val extra = alongText(sel.broughtAlong)
        if (extra.isEmpty()) { startExport(); return }
        MaterialAlertDialogBuilder(this).setTitle("Export ${picked.size} chosen?")
            .setMessage(extra.trim())
            .setPositiveButton("Export…") { _, _ -> startExport() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun startExport() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        createDoc.launch("skyward-parts-$day.json")
    }

    private fun writeExport(uri: Uri) {
        val app = applicationContext
        val s = store
        val keys = picked.toSet()
        LibraryImport.background(this, {
            val text = Parts.exportText(s, keys)
            (runCatching { app.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: app.contentResolver.openOutputStream(uri))
                ?.bufferedWriter()?.use { it.write(text) }
                ?: throw IllegalStateException("Can't write the file.")
        }) { r ->
            r.onSuccess { Snackbar.make(findViewById<View>(R.id.content), "Exported to ${LibraryImport.displayName(this, uri)}", Snackbar.LENGTH_LONG).show() }
                .onFailure { e -> alert("Export failed", e.message ?: e.toString()) }
        }
    }

    private fun confirmImport() {
        val f = file ?: return
        val sel = Parts.select(f.libs, picked, store, f.rifleNameHints)
        val newer = parts.count { it.key in picked && it.status == PartStatus.NEWER_HERE }
        MaterialAlertDialogBuilder(this).setTitle("Import ${picked.size} chosen?")
            .setMessage("Nothing in Skyward is deleted. A copy of your data is kept first, so this can be undone from Earlier copies." +
                (if (newer > 0) "\n\n$newer chosen item(s) are newer here and will be kept as they are." else "") + alongText(sel.broughtAlong))
            .setPositiveButton("Import") { _, _ ->
                val s = store
                val keys = picked.toSet()
                LibraryImport.background(this, { Parts.import(s, f, keys) }) { r ->
                    r.onSuccess { rep ->
                        MaterialAlertDialogBuilder(this).setTitle("Import finished").setMessage(ReportText.long(rep))
                            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
                    }.onFailure { e -> alert("Import failed", e.message ?: e.toString()) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    companion object {
        private const val EXTRA_IMPORT = "import"
        private const val K_PICKED = "picked"
        private const val K_OPEN = "open"
        @Volatile private var pending: Pair<ImportResult, String>? = null

        fun exportIntent(c: Context) = Intent(c, PartsActivity::class.java)
        fun importIntent(c: Context, file: ImportResult, name: String): Intent {
            pending = file to name
            return Intent(c, PartsActivity::class.java).putExtra(EXTRA_IMPORT, true)
        }
    }
}
