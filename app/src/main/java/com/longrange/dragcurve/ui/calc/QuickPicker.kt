package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.ProfileStore
import com.google.android.material.R as MR

object RecentGear {
    private const val PREFS = "calc_ui"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun rifles(c: Context) = Recents.decode(p(c).getString("recentRifles", null))
    fun loads(c: Context) = Recents.decode(p(c).getString("recentLoads", null))

    fun used(c: Context, rifleId: String?, loadId: String?) {
        val r = Recents.push(rifles(c), rifleId); val l = Recents.push(loads(c), loadId)
        if (r != rifles(c) || l != loads(c)) p(c).edit().putString("recentRifles", Recents.encode(r)).putString("recentLoads", Recents.encode(l)).apply()
    }
}

object QuickPicker {
    fun firearm(a: CalcActivity, store: ProfileStore, onPicked: () -> Unit) {
        val rifles = store.rifles()
        if (rifles.isEmpty()) { a.startActivity(Intent(a, RifleEditActivity::class.java)); return }
        val (curR, curL) = store.selection()
        val order = Recents.order(rifles.map { it.id }, RecentGear.rifles(a))
        val byId = rifles.associateBy { it.id }
        sheet(a, "Firearm") { sheet, list ->
            order.mapNotNull { byId[it] }.forEach { r ->
                val n = store.loads(r.id).size
                list.addView(row(a, r.name, RifleText.subtitle(r) + " · $n ammo", selected = r.id == curR) {
                    val loadId = if (r.id == curR) curL else
                        Recents.order(store.loads(r.id).map { it.id }, RecentGear.loads(a)).firstOrNull()
                    store.saveSelection(r.id, loadId)
                    RecentGear.used(a, r.id, loadId)
                    sheet.dismiss(); onPicked()
                })
            }
            list.addView(action(a, "All firearms…") { sheet.dismiss(); a.startActivity(Intent(a, RiflesActivity::class.java).putExtra(RiflesActivity.EXTRA_PICK, true)) })
            list.addView(action(a, a.getString(R.string.calc_add_firearm)) { sheet.dismiss(); a.startActivity(Intent(a, RifleEditActivity::class.java)) })
        }
    }

    fun ammo(a: CalcActivity, store: ProfileStore, rifleId: String, onPicked: () -> Unit) {
        val loads = store.loads(rifleId)
        if (loads.isEmpty()) { a.startActivity(Intent(a, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId)); return }
        val cur = store.selection().second
        val order = Recents.order(loads.map { it.id }, RecentGear.loads(a))
        val byId = loads.associateBy { it.id }
        val st = store.settings()
        val rifleName = store.rifles().firstOrNull { it.id == rifleId }?.name.orEmpty()
        sheet(a, "Ammo for $rifleName") { sheet, list ->
            order.mapNotNull { byId[it] }.forEach { l ->
                val b = store.bullet(l.bulletId)
                val mv = Num.trim(Units.toDisplay(Quantity.VELOCITY, l.muzzleVelocityFps, st), 0) + " " + Units.label(Quantity.VELOCITY, st)
                val sub = listOfNotNull(LoadFlags.text(l), BcSelection.summary(l, b), mv.takeIf { !LoadFlags.mvNeeded(l) }).joinToString(" · ")
                list.addView(row(a, l.name, sub, selected = l.id == cur) {
                    store.saveSelection(rifleId, l.id)
                    RecentGear.used(a, rifleId, l.id)
                    sheet.dismiss(); onPicked()
                })
            }
            list.addView(action(a, "All ammo…") {
                sheet.dismiss()
                a.startActivity(Intent(a, LoadsActivity::class.java).putExtra(LoadsActivity.EXTRA_RIFLE, rifleId).putExtra(LoadsActivity.EXTRA_PICK, true))
            })
            list.addView(action(a, a.getString(R.string.calc_add_ammo)) {
                sheet.dismiss(); a.startActivity(Intent(a, LoadEditActivity::class.java).putExtra(LoadEditActivity.EXTRA_RIFLE, rifleId))
            })
        }
    }

    private fun sheet(a: CalcActivity, title: String, fill: (BottomSheetDialog, LinearLayout) -> Unit) {
        val dlg = BottomSheetDialog(a)
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(4), 0, a.dp(16)) }
        col.addView(TextView(a).apply {
            text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(a.dp(20), a.dp(8), a.dp(20), a.dp(8))
            setTextColor(themeColor(MR.attr.colorOnSurface))
        })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        fill(dlg, list)
        val scroll = ScrollView(a).apply { addView(col) }
        dlg.setContentView(scroll)
        dlg.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dlg.behavior.skipCollapsed = true
        dlg.show()
    }

    private fun row(a: CalcActivity, title: String, sub: String, selected: Boolean, onClick: () -> Unit): View {
        val v = LayoutInflater.from(a).inflate(R.layout.calc_item_profile, null, false)
        v.findViewById<TextView>(R.id.title).text = title
        v.findViewById<TextView>(R.id.subtitle).apply { text = sub; visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<ImageView>(R.id.check).visibility = if (selected) View.VISIBLE else View.INVISIBLE
        v.findViewById<View>(R.id.more).visibility = View.GONE
        v.setOnClickListener { onClick() }
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return v
    }

    private fun action(a: CalcActivity, label: String, onClick: () -> Unit): View = TextView(a).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); typeface = Typeface.DEFAULT_BOLD
        minHeight = a.dp(60); gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding(a.dp(20), 0, a.dp(20), 0)
        setTextColor(themeColor(MR.attr.colorSecondary))
        val tv = TypedValue()
        if (a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
