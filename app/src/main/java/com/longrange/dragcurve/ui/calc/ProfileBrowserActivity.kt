package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.BrowseEntry
import com.longrange.dragcurve.exchange.BrowseKind
import com.longrange.dragcurve.exchange.BrowsePick
import com.longrange.dragcurve.exchange.BrowseStatus
import com.longrange.dragcurve.exchange.EmpiricalLink
import com.longrange.dragcurve.exchange.ProfileBrowser
import com.longrange.dragcurve.exchange.SharedIntake
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.ProfileSource
import com.longrange.dragcurve.model.Rifle

object ProfileSourcesUi {
    fun link(c: Context, uri: Uri): ProfileSource {
        val app = c.applicationContext
        val text = LibraryImport.readText(app, uri)
        val reader = ProfileBrowser.readerFor(text)
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            throw IllegalArgumentException("Android didn't allow Skyward Ballistics to keep reading this file. Pick it from local storage (Files) rather than a cloud shortcut.")
        }
        val name = LibraryImport.displayName(app, uri).substringBeforeLast('.').ifBlank { reader.formatName }
        return ProfileBrowser.add(Stores.get(app), uri.toString(), name)
    }

    fun unlink(c: Context, source: ProfileSource) {
        val store = Stores.get(c)
        if (source.id == SharedIntake.SHARED_EP_ID) { SharedIntake.remove(store, c.applicationContext.filesDir); return }
        ProfileBrowser.remove(store, source.id)
        if (store.empiricalLink() != source.uri) {
            runCatching { c.applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(source.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun explain(e: Throwable): String = when (e) {
        is SecurityException, is java.io.FileNotFoundException -> "can't open it: the file was moved, deleted or its access was lost. Re-link it"
        is IllegalArgumentException -> e.message ?: "not a database file"
        else -> "can't open it (${e.message ?: e.javaClass.simpleName}). Re-link it"
    }
}

class ProfileBrowserActivity : CalcActivity() {
    private var kind = BrowseKind.AMMO
    private var rifle: Rifle? = null
    private var sharedSource: String? = null

    private var all: List<BrowseEntry> = emptyList()
    private var shown: List<BrowseEntry> = emptyList()
    private var sources: List<ProfileSource> = emptyList()
    private val texts = HashMap<String, String>()
    private val errors = LinkedHashMap<String, String>()
    @Volatile private var driveUpdated = false
    private var loading = true

    private var query = ""
    private var sourceFilter: String? = null
    private var cartridgeFilter: String? = null
    private var fitsOnly: Boolean? = null
    private var putOn: String = ""
    private val ticks = LinkedHashSet<String>()
    private val bulletPicks = LinkedHashMap<String, String>()
    private var pendingBullet: String? = null

    private lateinit var search: EditText
    private lateinit var chips: LinearLayout
    private lateinit var status: TextView
    private lateinit var adapter: Adapter
    private lateinit var selectAll: MaterialButton
    private lateinit var importButton: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private val applySearch = Runnable { refresh() }

    private val addFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LibraryImport.background(this, { ProfileSourcesUi.link(applicationContext, uri) }) { r ->
            r.onSuccess { load() }.onFailure { e -> alert("Can't link that file", e.message ?: e.toString()) }
        }
    }

    private val pickBullet = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = res.data?.getStringExtra(BulletLibraryActivity.RESULT_ID)
        val ep = pendingBullet
        pendingBullet = null
        if (res.resultCode == Activity.RESULT_OK && id != null && ep != null) { bulletPicks[ep] = id; refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_browser)
        kind = if (intent.getIntExtra(EXTRA_KIND, 0) == 1) BrowseKind.FIREARM else BrowseKind.AMMO
        rifle = intent.getStringExtra(EXTRA_RIFLE)?.let { id -> store.rifles().firstOrNull { it.id == id } }
        sharedSource = intent.getStringExtra(EXTRA_SHARED)
        setupToolbar("")

        putOn = rifle?.id ?: ""
        if (sharedSource != null) {
            putOn = ""; fitsOnly = false; sourceFilter = sharedSource
        }
        savedInstanceState?.let { s ->
            kind = if (s.getInt("kind", 0) == 1) BrowseKind.FIREARM else if (s.containsKey("kind")) BrowseKind.AMMO else kind
            putOn = s.getString("putOn", putOn)
            query = s.getString("query", "")
            sourceFilter = s.getString("source")
            cartridgeFilter = s.getString("cartridge")
            if (s.containsKey("fits")) fitsOnly = s.getBoolean("fits")
            s.getStringArrayList("ticks")?.let { ticks += it }
            s.getStringArrayList("pickKeys")?.zip(s.getStringArrayList("pickValues").orEmpty())?.forEach { (k, v) -> bulletPicks[k] = v }
            pendingBullet = s.getString("pendingBullet")
        }

        search = findViewById(R.id.search)
        chips = findViewById(R.id.chips)
        status = findViewById(R.id.status)
        selectAll = findViewById(R.id.selectAll)
        importButton = findViewById(R.id.importButton)
        adapter = Adapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@ProfileBrowserActivity)
            addItemDecoration(DividerItemDecoration(this@ProfileBrowserActivity, DividerItemDecoration.VERTICAL))
            adapter = this@ProfileBrowserActivity.adapter
        }
        search.setText(query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                handler.removeCallbacks(applySearch); handler.postDelayed(applySearch, 200)
            }
        })
        selectAll.setOnClickListener { toggleAllShown() }
        importButton.setOnClickListener { import() }
        load(checkDrive = savedInstanceState == null)
    }

    override fun onDestroy() { handler.removeCallbacks(applySearch); super.onDestroy() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("query", query)
        outState.putInt("kind", if (kind == BrowseKind.FIREARM) 1 else 0)
        outState.putString("putOn", putOn)
        outState.putString("source", sourceFilter)
        outState.putString("cartridge", cartridgeFilter)
        fitsOnly?.let { outState.putBoolean("fits", it) }
        outState.putStringArrayList("ticks", ArrayList(ticks))
        outState.putStringArrayList("pickKeys", ArrayList(bulletPicks.keys))
        outState.putStringArrayList("pickValues", ArrayList(bulletPicks.values))
        pendingBullet?.let { outState.putString("pendingBullet", it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, 1, Menu.NONE, "Add a database…").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, 2, Menu.NONE, "Manage databases").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(Menu.NONE, 3, Menu.NONE, "Read the files again").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        1 -> { addFile.launch(LibraryImport.MIME); true }
        2 -> { startActivity(Intent(this, LibrariesActivity::class.java)); true }
        3 -> { load(checkDrive = true); true }
        else -> super.onOptionsItemSelected(item)
    }

    private class Loaded(val sources: List<ProfileSource>, val entries: List<BrowseEntry>, val texts: Map<String, String>, val errors: Map<String, String>)

    private fun load(checkDrive: Boolean = false) {
        loading = true
        refresh()
        val app = applicationContext
        val s = store
        val target = putOn.ifEmpty { null }
        LibraryImport.background(this, {
            if (checkDrive && DriveBackupHook.state(app).enabled) {
                val r = DriveBackupHook.refresh(app)
                if (r is com.longrange.dragcurve.sync.DriveBackups.Result.Updated) driveUpdated = true
            }
            val srcs = ProfileBrowser.sources(s)
            val entries = ArrayList<BrowseEntry>()
            val txt = HashMap<String, String>()
            val errs = LinkedHashMap<String, String>()
            val now = System.currentTimeMillis()
            for (src in srcs) {
                try {
                    val text = LibraryImport.readText(app, Uri.parse(src.uri))
                    entries += ProfileBrowser.readerFor(text).entries(src, text, s, target)
                    txt[src.id] = text
                    ProfileBrowser.markRead(s, src.id, now)
                } catch (e: Exception) {
                    val why = ProfileSourcesUi.explain(e)
                    errs[src.name] = why
                    ProfileBrowser.markRead(s, src.id, now, why)
                }
            }
            Loaded(srcs, entries, txt, errs)
        }) { r ->
            loading = false
            r.onSuccess { l ->
                sources = l.sources; all = l.entries
                texts.clear(); texts.putAll(l.texts)
                errors.clear(); errors.putAll(l.errors)
                ticks.retainAll(all.map { it.key }.toSet())
                if (sourceFilter != null && sources.none { it.id == sourceFilter }) sourceFilter = null
                val r0 = rifle
                if (fitsOnly == null) fitsOnly = kind == BrowseKind.AMMO && r0 != null && all.any { it.kind == BrowseKind.AMMO && ProfileBrowser.fits(it, r0) }
            }.onFailure { e -> errors["Databases"] = e.message ?: e.toString() }
            refresh()
        }
    }

    private fun refresh() {
        val r0 = rifle
        shown = ProfileBrowser.filter(all, ProfileBrowser.Filter(kind, query, sourceFilter, cartridgeFilter, if (fitsOnly == true) r0 else null))
        adapter.notifyDataSetChanged()
        renderChips()

        supportActionBar?.title = if (kind == BrowseKind.FIREARM) "Browse database: firearms" else "Browse database: ammo"
        val what = if (kind == BrowseKind.FIREARM) "firearms" else "ammo"
        val listed = if (sourceFilter == null) ProfileBrowser.newestCopies(all) else all.filter { it.sourceId == sourceFilter }
        val total = listed.count { it.kind == kind }
        val hidden = if (sourceFilter == null) ProfileBrowser.hiddenCopies(all, kind) else 0
        val received = sharedSource?.let { id -> sources.firstOrNull { it.id == id } }?.takeIf { !loading }?.let { src ->
            val at = if (src.receivedAt == 0L) "" else java.text.SimpleDateFormat(
                if (android.text.format.DateFormat.is24HourFormat(this)) "d MMM HH:mm" else "d MMM h:mm a", java.util.Locale.getDefault()).format(java.util.Date(src.receivedAt))
            SharedIntake.banner(SharedIntake.counts(all, src.id), at) + " Nothing changes until you tick and tap Import."
        }
        fun count(n: Int) = if (kind == BrowseKind.FIREARM) (if (n == 1) "1 firearm" else "$n firearms") else "$n ammo"
        val problems = errors.map { (name, why) -> "⚠ \"$name\": $why." }
        val drive = DriveBackupHook.state(this).takeIf { it.enabled && !loading }?.let { st ->
            val counts = if (all.any { it.sourceId == com.longrange.dragcurve.sync.DriveBackups.SOURCE_ID })
                ": " + SharedIntake.countsText(SharedIntake.counts(all, com.longrange.dragcurve.sync.DriveBackups.SOURCE_ID)) + "." else ""
            (if (driveUpdated) "New from Drive. " else "") + DriveBackupHook.statusLine(this).let { line -> line.lines().first() + counts + line.lines().drop(1).joinToString("") { "\n$it" } }
        }
        status.text = (listOfNotNull(received, drive) + problems + listOfNotNull(
            if (hidden > 0) "$hidden shown once: they are in more than one database, and the newest copy is listed." else null,
            when {
                loading -> "Reading the database…"
                sources.isEmpty() -> null
                shown.size == total -> count(total)
                else -> "${shown.size} of ${count(total)} shown"
            }
        )).joinToString("\n")
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        val emptyBox = findViewById<View>(R.id.emptyBox)
        val empty = findViewById<TextView>(R.id.empty)
        val action = findViewById<MaterialButton>(R.id.emptyAction)
        emptyBox.visibility = if (shown.isEmpty() && !loading) View.VISIBLE else View.GONE
        action.visibility = View.GONE
        when {
            sources.isEmpty() -> {
                empty.text = "No database linked yet.\n\nLink your Empirical Precision backup file, or a Skyward Ballistics library or backup file, once. After that its $what are listed here every time.\n\nOr send the backup straight from Empirical Precision. " + SharedIntake.HELP
                action.visibility = View.VISIBLE; action.text = "Add a database…"
                action.setOnClickListener { addFile.launch(LibraryImport.MIME) }
            }
            total == 0 && errors.isNotEmpty() -> {
                empty.text = "The linked database can't be opened. Link it again."
                action.visibility = View.VISIBLE; action.text = "Add a database…"
                action.setOnClickListener { addFile.launch(LibraryImport.MIME) }
            }
            total == 0 -> empty.text = "The linked database has no $what."
            fitsOnly == true && query.isBlank() && cartridgeFilter == null -> {
                empty.text = "Nothing in the database is for ${r0?.name ?: "this firearm"}."
                action.visibility = View.VISIBLE; action.text = "Show all ammo"
                action.setOnClickListener { fitsOnly = false; refresh() }
            }
            else -> empty.text = "Nothing matches. Try fewer words, or clear the filters."
        }

        val tickable = shown.filter { it.status != BrowseStatus.SAME }
        val allTicked = tickable.isNotEmpty() && tickable.all { it.key in ticks }
        selectAll.text = if (allTicked) "Untick all shown" else "Select all shown"
        selectAll.isEnabled = tickable.isNotEmpty()
        val n = ticks.size
        importButton.text = if (n == 0) "Import" else "Import $n"
        importButton.isEnabled = n > 0
        findViewById<View>(R.id.bar).visibility = if (sources.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun chip(text: String, checked: Boolean, onClick: () -> Unit): Chip = Chip(this).apply {
        this.text = text; isCheckable = true; isChecked = checked; textSize = 16f
        chipMinHeight = dp(44).toFloat()
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    private fun renderChips() {
        chips.removeAllViews()
        val r0 = rifle
        chips.addView(chip("Ammo", kind == BrowseKind.AMMO) { switchKind(BrowseKind.AMMO) })
        chips.addView(chip("Firearms", kind == BrowseKind.FIREARM) { switchKind(BrowseKind.FIREARM) })
        if (DriveBackupHook.state(this).enabled) chips.addView(chip("↻ Check Drive", false) { load(checkDrive = true) })
        if (kind == BrowseKind.AMMO && r0 != null) {
            chips.addView(chip("Fits ${r0.name}", fitsOnly == true) { fitsOnly = true; refresh() })
            chips.addView(chip("All ammo", fitsOnly != true) { fitsOnly = false; refresh() })
        }
        if (kind == BrowseKind.AMMO) {
            val rifles = store.rifles()
            val name = rifles.firstOrNull { it.id == putOn }?.name
            chips.addView(chip("Put on: " + (name ?: "the database's own firearm"), true) {
                choose("Put the ammo you import on", rifles.map { it.name } + listOf("The firearm the database has each one with")) { i ->
                    putOn = rifles.getOrNull(i)?.id ?: ""
                    ticks.clear()
                    load()
                }
            })
        }
        val carts = ProfileBrowser.cartridges(all, kind)
        if (carts.size > 1 || cartridgeFilter != null) {
            chips.addView(chip("Cartridge: " + (cartridgeFilter ?: "all"), cartridgeFilter != null) {
                choose("Cartridge", listOf("All cartridges") + carts) { i -> cartridgeFilter = if (i == 0) null else carts[i - 1]; refresh() }
            })
        }
        if (sources.size > 1) {
            chips.addView(chip("Database: " + (sources.firstOrNull { it.id == sourceFilter }?.name ?: "all"), sourceFilter != null) {
                choose("Database", listOf("All databases") + sources.map { it.name }) { i -> sourceFilter = if (i == 0) null else sources[i - 1].id; refresh() }
            })
        }
        (chips.parent as View).visibility = if (chips.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun switchKind(k: BrowseKind) {
        if (kind != k) { kind = k; cartridgeFilter = null }
        refresh()
    }

    private fun choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }
            .setNegativeButton(android.R.string.cancel, null).show()
        refresh()
    }

    private fun toggleAllShown() {
        val tickable = shown.filter { it.status != BrowseStatus.SAME }.map { it.key }
        if (tickable.all { it in ticks }) ticks.removeAll(tickable.toSet()) else ticks += tickable
        refresh()
    }

    private fun bulletLine(e: BrowseEntry): Pair<String, Boolean>? {
        val bm = e.bullet ?: return null
        val picked = bulletPicks[bm.epBulletId]
        return when {
            picked == EmpiricalLink.CREATE -> "Bullet: " + bm.fallbackLabel.replaceFirstChar { it.lowercase() } to bm.noBc
            picked != null -> "Bullet: " + (store.bullet(picked)?.let { BulletSearch.title(it) } ?: "your choice") + " (your choice)" to false
            bm.matched != null -> null
            bm.noBc -> "⚠ " + bm.fallbackLabel to true
            else -> "Bullet: no sure match. " + bm.fallbackLabel + " unless you choose one" to true
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tick: CheckBox = v.findViewById(R.id.tick)
            val text: View = v.findViewById(R.id.text)
            val title: TextView = v.findViewById(R.id.title)
            val tag: TextView = v.findViewById(R.id.tag)
            val subtitle: TextView = v.findViewById(R.id.subtitle)
            val warn: TextView = v.findViewById(R.id.warn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.calc_item_browse, parent, false))

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = shown[position]
            h.title.text = e.title
            h.subtitle.text = if (sources.size > 1) listOf(e.summary, e.sourceName).filter { it.isNotBlank() }.joinToString("\n") else e.summary
            h.subtitle.visibility = if (h.subtitle.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            h.tag.text = e.status.label
            val color = when (e.status) {
                BrowseStatus.NEW -> ContextCompat.getColor(this@ProfileBrowserActivity, R.color.tag_empirical)
                BrowseStatus.UPDATE -> ContextCompat.getColor(this@ProfileBrowserActivity, R.color.status_pending)
                BrowseStatus.SAME -> Color.GRAY
            }
            ViewCompat.setBackgroundTintList(h.tag, ColorStateList.valueOf(color))
            val line = if (e.status == BrowseStatus.SAME) null else bulletLine(e)
            h.warn.visibility = if (line == null) View.GONE else View.VISIBLE
            if (line != null) {
                h.warn.text = line.first
                h.warn.setTextColor(if (line.second) ContextCompat.getColor(this@ProfileBrowserActivity, R.color.sol_warn) else h.subtitle.currentTextColor)
            }
            h.tick.setOnCheckedChangeListener(null)
            h.tick.isEnabled = e.status != BrowseStatus.SAME
            h.tick.isChecked = e.key in ticks
            h.tick.setOnCheckedChangeListener { _, on -> if (on) ticks += e.key else ticks -= e.key; refreshBar() }
            h.text.setOnClickListener { details(e) }
        }
    }

    private fun refreshBar() {
        val tickable = shown.filter { it.status != BrowseStatus.SAME }
        selectAll.text = if (tickable.isNotEmpty() && tickable.all { it.key in ticks }) "Untick all shown" else "Select all shown"
        importButton.text = if (ticks.isEmpty()) "Import" else "Import ${ticks.size}"
        importButton.isEnabled = ticks.isNotEmpty()
    }

    private fun details(e: BrowseEntry) {
        val lines = ArrayList<String>()
        lines += when (e.status) {
            BrowseStatus.NEW -> "New: not in Skyward Ballistics yet."
            BrowseStatus.UPDATE -> "Already here. Importing updates it from the database; your zero and other settings here are kept."
            BrowseStatus.SAME -> "Already here and the same. Nothing to import."
        }
        if (sources.size > 1) lines += "Database: ${e.sourceName}"
        bulletLine(e)?.let { lines += it.first }
        lines += ""
        e.details.forEach { (k, v) -> lines += "$k: $v" }
        val d = MaterialAlertDialogBuilder(this).setTitle(e.title).setMessage(lines.joinToString("\n"))
            .setNegativeButton("Close", null)
        if (e.status != BrowseStatus.SAME) {
            val on = e.key in ticks
            d.setPositiveButton(if (on) "Untick" else "Tick to import") { _, _ -> if (on) ticks -= e.key else ticks += e.key; refresh() }
            e.bullet?.let { bm -> d.setNeutralButton("Choose bullet…") { _, _ -> chooseBullet(bm) } }
        }
        d.show()
    }

    private fun chooseBullet(bm: EmpiricalLink.BulletMatch) {
        val cands = bm.candidates
        val labels = cands.map { BulletSearch.title(it) + "\n" + BulletSearch.subtitle(it) + if (it.source == "library") "" else " · mine" } +
            listOf("Pick another bullet…") + listOfNotNull(bm.createOption)
        MaterialAlertDialogBuilder(this).setTitle("Bullet for ${bm.epName}")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < cands.size -> { bulletPicks[bm.epBulletId] = cands[i].id; refresh() }
                    i == cands.size -> {
                        pendingBullet = bm.epBulletId
                        pickBullet.launch(Intent(this, BulletLibraryActivity::class.java).putExtra(BulletLibraryActivity.EXTRA_PICK, true))
                    }
                    else -> { bulletPicks[bm.epBulletId] = EmpiricalLink.CREATE; refresh() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun import() {
        val picked = all.filter { it.key in ticks }
        if (picked.isEmpty()) return
        val unclear = picked.filter { it.firearmAmbiguous }
        val rifles = store.rifles()
        if (putOn.isEmpty() && unclear.isNotEmpty() && rifles.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(if (unclear.size == 1) "Which firearm is \"${unclear[0].title}\" for?" else "${unclear.size} of these fit several firearms in the database. Put them on:")
                .setItems(rifles.map { it.name }.toTypedArray()) { _, i -> import(picked, rifles[i].id) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        import(picked, null)
    }

    private fun import(picked: List<BrowseEntry>, ambiguousRifleId: String?) {
        val s = store
        val bySource = picked.groupBy { it.sourceId }
        val textOf = HashMap(texts)
        val picks = bulletPicks.toMap()
        val rifleId = rifle?.id
        importButton.isEnabled = false
        LibraryImport.background(this, {
            val reports = ArrayList<ImportReport>()
            for ((sid, entries) in bySource) {
                val text = textOf[sid] ?: continue
                reports += ProfileBrowser.readerFor(text).import(text, s, BrowsePick(entries, picks, rifleId, putOn.ifEmpty { null }, ambiguousRifleId))
            }
            reports
        }) { r ->
            r.onSuccess { reports ->
                val ids = picked.filter { it.kind == BrowseKind.AMMO }.map { it.recordId }.toSet()
                val toCheck = s.loads(null).filter { l -> (l.id in ids || l.externalRef?.removePrefix(EmpiricalLink.PREFIX) in ids) && LoadFlags.list(l).isNotEmpty() }
                val next = if (toCheck.isEmpty()) "" else
                    "\n\nNext: open the ammo marked ⚠ (${toCheck.size}), check the zero and the muzzle velocity where marked, and tap Save."
                val text = reports.joinToString("\n\n") { ReportText.long(it).trim() }.ifBlank { "Nothing to change." }
                ticks.clear()
                MaterialAlertDialogBuilder(this).setTitle("Imported").setMessage(text + next)
                    .setPositiveButton(android.R.string.ok, null).show()
                load()
            }.onFailure { e -> alert("Import failed", e.message ?: e.toString()); refresh() }
        }
    }

    companion object {
        const val MENU_TITLE = "Browse database"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_RIFLE = "rifle"
        private const val EXTRA_SHARED = "shared"

        fun openShared(a: Activity, sourceId: String) {
            val current = Stores.get(a).let { s -> s.selection().first?.takeIf { id -> s.rifles().any { it.id == id } } }
            a.startActivity(Intent(a, ProfileBrowserActivity::class.java)
                .putExtra(EXTRA_KIND, if (current == null) 1 else 0).putExtra(EXTRA_RIFLE, current).putExtra(EXTRA_SHARED, sourceId))
        }

        fun open(a: Activity, kind: BrowseKind, rifleId: String?) =
            a.startActivity(Intent(a, ProfileBrowserActivity::class.java).putExtra(EXTRA_KIND, if (kind == BrowseKind.FIREARM) 1 else 0).putExtra(EXTRA_RIFLE, rifleId))
    }
}
