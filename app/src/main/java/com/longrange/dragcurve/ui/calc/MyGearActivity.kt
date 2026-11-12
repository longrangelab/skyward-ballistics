package com.longrange.dragcurve.ui.calc

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.BrowseKind
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.google.android.material.R as MR

object SideLink {
    const val MENU_BROWSE = 0x1B01

    fun browseAction(menu: android.view.Menu) {
        val item = menu.add(android.view.Menu.NONE, MENU_BROWSE, 50, "Browse").setIcon(R.drawable.ic_calc_search)
        item.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        item.contentDescription = ProfileBrowserActivity.MENU_TITLE
    }

    fun make(c: android.content.Context, label: String, onClick: () -> Unit): TextView = TextView(c).apply {
        text = android.text.SpannableString(label).apply { setSpan(android.text.style.UnderlineSpan(), 0, label.length, 0) }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = android.view.Gravity.CENTER
        minHeight = c.dp(48)
        setPadding(c.dp(16), c.dp(4), c.dp(16), c.dp(4))
        setTextColor(themeColor(MR.attr.colorSecondary))
        setOnClickListener { onClick() }
    }
}

class MyGearActivity : CalcActivity() {
    private lateinit var form: Form
    private lateinit var tabBar: View
    override val snackAnchor: View? get() = if (::tabBar.isInitialized) tabBar else null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabBar = TabNav.install(this, R.layout.calc_activity_scroll, Tab.GEAR)
        setupToolbar(getString(R.string.calc_tab_gear), up = false)
        form = contentForm()
    }

    override fun onResume() { super.onResume(); render() }

    private val redraw = Runnable { if (!isFinishing && !isDestroyed) render() }
    private fun redrawSoon() { tabBar.removeCallbacks(redraw); tabBar.postDelayed(redraw, 150) }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        SideLink.browseAction(menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == SideLink.MENU_BROWSE) { ProfileBrowserActivity.open(this, BrowseKind.FIREARM, null); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        form.settings = settings
        form.clear()
        val rifles = store.rifles()
        val (selR, selL) = store.selection()

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(12), dp(12), dp(4)) }
        buttons.addView(MaterialButton(this).apply {
            text = getString(R.string.calc_add_firearm); setIconResource(R.drawable.ic_calc_add)
            setOnClickListener { startActivity(Intent(this@MyGearActivity, RifleEditActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        form.addView(buttons)

        form.header(getString(R.string.calc_firearms))
        if (rifles.isEmpty()) {
            form.note("You have no firearms yet. Tap \"Add Firearm\" to set up your first one, then add the ammo you shoot in it.")
            form.addView(SideLink.make(this, "or browse a database") { ProfileBrowserActivity.open(this, BrowseKind.FIREARM, null) })
        }
        rifles.forEach { r -> firearmCard(r, store.loads(r.id), r.id == selR, selL) }

        form.header(getString(R.string.calc_my_bullets))
        form.group {
            nav(getString(R.string.calc_my_bullets), "${store.userBullets().size}", sub = "Bullets you entered yourself, with their BCs") {
                startActivity(Intent(this@MyGearActivity, BulletLibraryActivity::class.java).putExtra(BulletLibraryActivity.EXTRA_FILTER, 2))
            }
        }
        form.note("A tick marks what the Calculator is using. Tap a firearm or ammo to change it; ⋮ for more.")
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun firearmCard(r: Rifle, loads: List<Load>, selected: Boolean, selL: String?) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        }
        card.setCardBackgroundColor(card.themeColor(MR.attr.colorSurfaceContainerHigh))
        card.strokeColor = card.themeColor(MR.attr.colorOutlineVariant)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(col)
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        }
        form.addView(card)

        col.addView(item(r.name, RifleText.subtitle(r) + " · ${loads.size} ammo", empirical = EmpiricalHook.isEmpirical(r.externalRef),
            selected = selected, big = true,
            onClick = { startActivity(Intent(this, RifleEditActivity::class.java).putExtra(RifleEditActivity.EXTRA_ID, r.id)) },
            menu = listOf(
                getString(R.string.calc_edit) to { startActivity(Intent(this, RifleEditActivity::class.java).putExtra(RifleEditActivity.EXTRA_ID, r.id)) },
                "Use on the Calculator" to { use(r.id, if (selected) selL else loads.firstOrNull()?.id) },
                getString(R.string.calc_add_ammo) to { addAmmo(r.id) },
                getString(R.string.calc_round_count) to { ShotCounterActivity.open(this, r.id) },
                ProfileBrowserActivity.MENU_TITLE + " (ammo)" to { ProfileBrowserActivity.open(this, BrowseKind.AMMO, r.id) },
                getString(R.string.calc_duplicate) to { duplicateFirearm(r) },
                getString(R.string.calc_delete) to { UndoDelete.deleteFirearm(this, store, r.id, tabBar) { render() } },
            )))
        col.addView(roundsRow(r))
        loads.forEach { l ->
            divider(col, indent = 40)
            val b = store.bullet(l.bulletId)
            val mv = Num.trim(Units.toDisplay(Quantity.VELOCITY, l.muzzleVelocityFps, settings), 0) + " " + Units.label(Quantity.VELOCITY, settings)
            val tnf = AmmoTnf.get(l, b) { redrawSoon() }?.let { AmmoTnf.short(it) }
            val sub = listOfNotNull(LoadFlags.text(l), BcSelection.summary(l, b), mv.takeIf { !LoadFlags.mvNeeded(l) }, tnf).joinToString(" · ")
            col.addView(item(l.name, sub, empirical = EmpiricalHook.isEmpirical(l.externalRef), selected = selected && l.id == selL, big = false, indent = 24,
                onClick = { editAmmo(l) },
                menu = listOf(
                    getString(R.string.calc_edit) to { editAmmo(l) },
                    "Use on the Calculator" to { use(r.id, l.id) },
                    getString(R.string.calc_duplicate) to {
                        store.saveLoad(l.copy(id = newId("load"), name = l.name + " copy", externalRef = null)); render()
                    },
                    getString(R.string.calc_delete) to { UndoDelete.deleteAmmo(this, store, l.id, tabBar) { render() } },
                )))
        }
        divider(col, indent = 40)
        col.addView(TextView(this).apply {
            text = "+  " + getString(R.string.calc_add_ammo)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = android.graphics.Typeface.DEFAULT_BOLD
            minHeight = dp(56); gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(40), 0, dp(16), 0)
            setTextColor(themeColor(MR.attr.colorSecondary))
            val tv = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            contentDescription = "Add ammo for ${r.name}"
            setOnClickListener { addAmmo(r.id) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun roundsRow(r: Rifle): View {
        val list = store.shotRecords(r.id)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(56), 0, dp(12), dp(10))
            val tv = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
            setOnClickListener { ShotCounterActivity.open(this@MyGearActivity, r.id) }
        }
        row.addView(TextView(this).apply {
            text = ShotText.summary(this@MyGearActivity, list) ?: "No rounds logged"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(themeColor(MR.attr.colorOnSurfaceVariant))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "Count shots"; contentDescription = "Count shots for ${r.name}"
            setOnClickListener { ShotCounterActivity.open(this@MyGearActivity, r.id) }
        })
        return row
    }

    private fun divider(col: LinearLayout, indent: Int) {
        val d = View(this)
        col.addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(indent); marginEnd = dp(16) })
        d.setBackgroundColor(d.themeColor(MR.attr.colorOutlineVariant))
    }

    private fun item(title: String, sub: String, empirical: Boolean, selected: Boolean, big: Boolean, indent: Int = 0,
                     onClick: () -> Unit, menu: List<Pair<String, () -> Unit>>): View {
        val v = LayoutInflater.from(this).inflate(R.layout.calc_item_profile, null, false)
        v.setPaddingRelative(dp(16 + indent), v.paddingTop, v.paddingEnd, v.paddingBottom)
        v.findViewById<TextView>(R.id.title).apply { text = title; if (!big) setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) }
        v.findViewById<TextView>(R.id.subtitle).apply { text = sub; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<ImageView>(R.id.check).apply {
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
            contentDescription = "On the Calculator"
        }
        v.findViewById<TextView>(R.id.tag).apply {
            if (empirical) {
                visibility = View.VISIBLE; text = getString(R.string.calc_empirical)
                ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(ContextCompat.getColor(context, R.color.tag_empirical)))
            } else visibility = View.GONE
        }
        val more = v.findViewById<ImageButton>(R.id.more)
        more.contentDescription = "More for $title"
        more.setOnClickListener { b ->
            val pm = PopupMenu(this, b)
            menu.forEachIndexed { i, m -> pm.menu.add(0, i, i, m.first) }
            pm.setOnMenuItemClickListener { mi -> menu[mi.itemId].second(); true }
            pm.show()
        }
        v.setOnClickListener { onClick() }
        v.setOnLongClickListener { more.performClick(); true }
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return v
    }

    private fun use(rifleId: String, loadId: String?) {
        store.saveSelection(rifleId, loadId)
        RecentGear.used(this, rifleId, loadId)
        TabNav.go(this, Tab.GEAR, Tab.CALCULATOR)
    }

    private fun addAmmo(rifleId: String) =
        startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId))

    private fun editAmmo(l: Load) =
        startActivity(Intent(this, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_ID, l.id).putExtra(LoadEditActivity.EXTRA_RIFLE, l.rifleId))

    private fun duplicateFirearm(r: Rifle) {
        val copy = r.copy(id = newId("rifle"), name = r.name + " copy", externalRef = null)
        store.saveRifle(copy)
        store.loads(r.id).forEach { store.saveLoad(it.copy(id = newId("load"), rifleId = copy.id, externalRef = null)) }
        render()
    }
}
