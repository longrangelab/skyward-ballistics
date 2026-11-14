package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import android.view.Menu
import android.view.MenuItem
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.BrowseKind
import com.longrange.dragcurve.exchange.BulletGroups
import com.longrange.dragcurve.model.Bullet

abstract class CalcListActivity : CalcActivity() {
    protected lateinit var adapter: ProfileAdapter
    protected lateinit var empty: TextView
    protected lateinit var fab: ExtendedFloatingActionButton
    protected lateinit var recycler: RecyclerView

    protected fun setupList(title: String, menu: List<String>, onClick: (ProfileItem) -> Unit, onMenu: (ProfileItem, Int) -> Unit) {
        setContentView(R.layout.calc_activity_list)
        setupToolbar(title)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fab)
        recycler = findViewById(R.id.list)
        adapter = ProfileAdapter(onClick, menu, onMenu)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        recycler.adapter = adapter
    }

    protected fun show(items: List<ProfileItem>, emptyText: CharSequence, actionLabel: String? = null, action: (() -> Unit)? = null,
                       linkLabel: String? = null, link: (() -> Unit)? = null) {
        adapter.items = items
        empty.text = emptyText
        findViewById<View>(R.id.emptyBox).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.emptyAction)
        if (actionLabel != null && action != null) {
            btn.visibility = View.VISIBLE; btn.text = actionLabel; btn.setOnClickListener { action() }
        } else btn.visibility = View.GONE
        val small = findViewById<TextView>(R.id.emptyLink)
        if (linkLabel != null && link != null) {
            small.visibility = View.VISIBLE
            small.text = android.text.SpannableString(linkLabel).apply { setSpan(android.text.style.UnderlineSpan(), 0, linkLabel.length, 0) }
            small.setOnClickListener { link() }
        } else small.visibility = View.GONE
    }

    override val snackAnchor: View? get() = if (::fab.isInitialized && fab.visibility == View.VISIBLE) fab else null
}

class RiflesActivity : CalcListActivity() {
    private var pick = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        setupList(getString(R.string.calc_firearms),
            listOf(getString(R.string.calc_edit), getString(R.string.calc_ammo), getString(R.string.calc_duplicate), getString(R.string.calc_delete)),
            onClick = { item ->
                if (pick) {
                    val (curR, curL) = store.selection()
                    val loadId = if (curR == item.id) curL else store.loads(item.id).firstOrNull()?.id
                    store.saveSelection(item.id, loadId)
                    finish()
                } else openLoads(item.id)
            },
            onMenu = { item, i ->
                when (i) {
                    0 -> startActivity(Intent(this, RifleEditActivity::class.java).putExtra(RifleEditActivity.EXTRA_ID, item.id))
                    1 -> openLoads(item.id)
                    2 -> duplicate(item.id)
                    3 -> UndoDelete.deleteFirearm(this, store, item.id, snackAnchor) { refresh() }
                }
            })
        fab.text = getString(R.string.calc_add_firearm)
        fab.visibility = View.VISIBLE
        fab.setOnClickListener { startActivity(Intent(this, RifleEditActivity::class.java)) }
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        SideLink.browseAction(menu)
        menu.add(Menu.NONE, MENU_IMPORT_APP, Menu.NONE, ImportFromAppActivity.MENU_TITLE).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == SideLink.MENU_BROWSE) { ProfileBrowserActivity.open(this, BrowseKind.FIREARM, null); return true }
        if (item.itemId == MENU_IMPORT_APP) { ImportFromAppActivity.open(this); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun openLoads(rifleId: String) =
        startActivity(Intent(this, LoadsActivity::class.java).putExtra(LoadsActivity.EXTRA_RIFLE, rifleId).putExtra(LoadsActivity.EXTRA_PICK, pick))

    private fun duplicate(id: String) {
        val r = store.rifles().firstOrNull { it.id == id } ?: return
        val copy = r.copy(id = newId("rifle"), name = r.name + " copy", externalRef = null)
        store.saveRifle(copy)
        store.loads(r.id).forEach { store.saveLoad(it.copy(id = newId("load"), rifleId = copy.id, externalRef = null)) }
        refresh()
    }

    private fun refresh() {
        val selId = store.selection().first
        val items = store.rifles().map { r ->
            val n = store.loads(r.id).size
            ProfileItem(r.id, r.name, RifleText.subtitle(r) + " · $n ammo",
                tag = if (EmpiricalHook.isEmpirical(r.externalRef)) getString(R.string.calc_empirical) else null,
                selected = r.id == selId)
        }
        show(items, getString(R.string.calc_no_firearms), getString(R.string.calc_add_firearm), { startActivity(Intent(this, RifleEditActivity::class.java)) },
            "or browse a database") { ProfileBrowserActivity.open(this, BrowseKind.FIREARM, null) }
    }

    companion object {
        const val EXTRA_PICK = "pick"
        private const val MENU_IMPORT_APP = 0x1A01
    }
}

class LoadsActivity : CalcListActivity() {
    private var pick = false
    private lateinit var rifleId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)
        rifleId = intent.getStringExtra(EXTRA_RIFLE) ?: run { finish(); return }
        val rifleName = store.rifles().firstOrNull { it.id == rifleId }?.name ?: ""
        setupList("${getString(R.string.calc_ammo)} — $rifleName",
            listOf(getString(R.string.calc_edit), getString(R.string.calc_duplicate), getString(R.string.calc_delete)),
            onClick = { item ->
                if (pick) {
                    store.saveSelection(rifleId, item.id)
                    startActivity(Intent(this, CalculatorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                } else edit(item.id)
            },
            onMenu = { item, i ->
                when (i) {
                    0 -> edit(item.id)
                    1 -> store.loads(rifleId).firstOrNull { it.id == item.id }?.let { l ->
                        val copy = l.copy(id = newId("load"), name = l.name + " copy", externalRef = null)
                        store.saveLoad(copy)
                        refresh()
                    }
                    2 -> UndoDelete.deleteAmmo(this, store, item.id, snackAnchor) { refresh() }
                }
            })
        fab.text = getString(R.string.calc_add_ammo)
        fab.visibility = View.VISIBLE
        fab.setOnClickListener { addAmmo() }
    }

    override fun onResume() {
        super.onResume()
        if (store.rifles().none { it.id == rifleId }) { finish(); return }
        refresh()
    }

    private fun edit(id: String) =
        startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_ID, id).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId))

    private fun addAmmo() = startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId))

    private fun importFromEmpirical() = ProfileBrowserActivity.open(this, BrowseKind.AMMO, rifleId)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        SideLink.browseAction(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == SideLink.MENU_BROWSE) { importFromEmpirical(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val selId = store.selection().second
        val items = store.loads(rifleId).map { l ->
            val b = store.bullet(l.bulletId)
            val mv = Num.trim(Units.toDisplay(Quantity.VELOCITY, l.muzzleVelocityFps, settings), 0) + " " + Units.label(Quantity.VELOCITY, settings)
            ProfileItem(l.id, l.name, listOfNotNull(LoadFlags.text(l, b), BcSelection.summary(l, b), mv.takeIf { !LoadFlags.mvNeeded(l) }).joinToString(" · "),
                tag = if (EmpiricalHook.isEmpirical(l.externalRef)) getString(R.string.calc_empirical) else null,
                selected = l.id == selId && store.selection().first == rifleId)
        }
        show(items, getString(R.string.calc_no_ammo), getString(R.string.calc_add_ammo), { addAmmo() }, "or browse a database") { importFromEmpirical() }
    }

    companion object {
        const val EXTRA_RIFLE = "rifle"
        const val EXTRA_PICK = "pick"
    }
}

class BulletLibraryActivity : CalcListActivity() {
    private var pick = false
    private var unified = false
    private var copySource = false
    private var filter = 0
    private lateinit var search: EditText
    private var all: List<Bullet> = emptyList()
    private var groups: List<BulletGroups.Group> = emptyList()

    private val importLib = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LibraryImport.run(this, uri) { reload() }
    }

    private val addForResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = res.data?.getStringExtra(RESULT_ID)
        if (res.resultCode == Activity.RESULT_OK && id != null) {
            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_ID, id)); finish()
        }
    }

    private val pickCopySource = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val id = res.data?.getStringExtra(RESULT_ID)
        if (res.resultCode == Activity.RESULT_OK && id != null) openEditor(copyFrom = id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        copySource = intent.getBooleanExtra(EXTRA_COPY_SOURCE, false)
        unified = intent.getBooleanExtra(EXTRA_UNIFIED, false)
        pick = copySource || unified || intent.getBooleanExtra(EXTRA_PICK, false)
        val startFilter = intent.getIntExtra(EXTRA_FILTER, 0).coerceIn(0, 2)
        filter = savedInstanceState?.getInt("filter") ?: startFilter
        if (unified) filter = 0
        val title = when {
            unified -> "Choose the bullet"
            copySource -> "Copy from a bullet"
            pick -> getString(R.string.calc_bullet)
            startFilter == 2 -> getString(R.string.calc_my_bullets)
            else -> getString(R.string.calc_bullet_library)
        }
        setupList(title, listOf("Choose the data source…"),
            onClick = { item ->
                if (pick) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_ID, item.id)); finish()
                } else startActivity(Intent(this, BulletEditActivity::class.java).putExtra(BulletEditActivity.EXTRA_ID, item.id))
            }, onMenu = { item, _ -> chooseSource(item.id) })
        findViewById<View>(R.id.searchBox).visibility = View.VISIBLE
        search = findViewById(R.id.search)
        if (savedInstanceState == null) intent.getStringExtra(EXTRA_QUERY)?.let { search.setText(it) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        val chips = findViewById<ChipGroup>(R.id.filters)
        chips.visibility = if (unified) View.GONE else View.VISIBLE
        listOf("All", "Library", "My bullets").forEachIndexed { i, t ->
            chips.addView(Chip(this).apply {
                id = View.generateViewId(); text = t; isCheckable = true; isChecked = i == filter
                setOnClickListener { filter = i; refresh() }
            })
        }
        if (unified) {
            (findViewById<View>(R.id.searchBox) as com.google.android.material.textfield.TextInputLayout).hint = "Search bullets: maker, name, calibre, 140gr…"
            findViewById<com.google.android.material.button.MaterialButton>(R.id.bottomAction).apply {
                visibility = View.VISIBLE
                text = getString(R.string.calc_enter_own_bc)
                setOnClickListener { setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_MANUAL, true)); finish() }
            }
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.addTop).apply {
            visibility = if (copySource || unified) View.GONE else View.VISIBLE
            text = getString(if (pick) R.string.calc_add_new_bullet else R.string.calc_add_bullet)
            setOnClickListener { addBullet() }
        }
        recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, dp(24))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("filter", filter)
    }

    override fun onResume() { super.onResume(); reload() }

    private fun addBullet() {
        if (all.isEmpty()) { openEditor(copyFrom = null); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.calc_add_bullet)
            .setItems(arrayOf("Start blank", "Copy from a bullet…")) { _, which ->
                if (which == 0) openEditor(copyFrom = null)
                else pickCopySource.launch(Intent(this, BulletLibraryActivity::class.java).putExtra(EXTRA_COPY_SOURCE, true))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openEditor(copyFrom: String?) {
        val i = Intent(this, BulletEditActivity::class.java)
        if (copyFrom != null) i.putExtra(BulletEditActivity.EXTRA_COPY_FROM, copyFrom)
        if (pick) addForResult.launch(i.putExtra(BulletEditActivity.EXTRA_RETURN, true)) else startActivity(i)
    }

    private fun reload() {
        all = BulletBook.ordered(store.userBullets() + store.libraryBullets())
        refresh()
    }

    private fun refresh() {
        val q = search.text?.toString().orEmpty()
        val pool = all.filter { filter == 0 || (filter == 1) == (it.source == "library") }
        groups = BulletGroups.group(pool, BulletSources.preferred(this)).filter { g -> g.members.any { BulletSearch.matches(it, q) } }
        val items = groups.map { g ->
            val b = g.default
            val also = g.others.map { BulletSearch.sourceLabel(it) }.distinct().takeIf { it.isNotEmpty() }?.let { "also in " + it.joinToString(", ") }
            ProfileItem(b.id, BulletSearch.title(b), listOfNotNull(b.caliber.takeIf { it.isNotBlank() }, BulletSearch.subtitle(b), also).joinToString(" · "),
                tag = if (unified) BulletSearch.sourceLabel(b) else if (b.source == "library") null else "mine", showMenu = g.others.isNotEmpty())
        }
        when {
            unified && items.isEmpty() && all.isEmpty() -> show(items, getString(R.string.calc_no_bullets_own_bc))
            unified && items.isEmpty() -> show(items, getString(R.string.calc_no_match_own_bc))
            filter == 2 && q.isBlank() -> show(items, getString(R.string.calc_my_bullets_empty))
            !BulletLibraryHook.hasLibrary(this) && filter != 2 && q.isBlank() && items.isEmpty() ->
                show(items, getString(R.string.calc_no_library), getString(R.string.calc_import_library)) { importLib.launch(LibraryImport.MIME) }
            else -> show(items, getString(R.string.calc_no_bullets))
        }
    }

    private fun chooseSource(id: String) {
        val g = groups.firstOrNull { it.default.id == id } ?: return
        val labels = g.members.map { "${BulletSearch.sourceLabel(it)}: ${BulletGroups.dataKind(it)}" }
        MaterialAlertDialogBuilder(this).setTitle(BulletSearch.title(g.default))
            .setSingleChoiceItems(labels.toTypedArray(), 0) { d, i ->
                d.dismiss()
                val b = g.members[i]
                BulletSources.setPreferred(this, g.key, b.id)
                if (pick) { setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_ID, b.id)); finish() } else refresh()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!copySource && !unified) menu.add(0, 1, 0, R.string.calc_import_library)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) { importLib.launch(LibraryImport.MIME); return true }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_FILTER = "filter"
        const val EXTRA_COPY_SOURCE = "copySource"
        const val EXTRA_QUERY = "query"
        const val EXTRA_UNIFIED = "unified"
        const val RESULT_ID = "bulletId"
        const val RESULT_MANUAL = "manual"
    }
}

object BulletSources {
    private const val PREFS = "bullet_sources"

    fun preferred(c: Context): Map<String, String> =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun setPreferred(c: Context, key: String, bulletId: String) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, bulletId).apply()
}
