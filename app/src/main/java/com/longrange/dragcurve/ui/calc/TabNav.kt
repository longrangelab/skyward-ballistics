package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.longrange.dragcurve.R

enum class Tab(val menuId: Int) { CALCULATOR(R.id.tab_calculator), GEAR(R.id.tab_gear), ANALYSIS(R.id.tab_analysis), MORE(R.id.tab_more) }

object TabNav {
    fun install(a: CalcActivity, layout: Int, tab: Tab): NavigationBarView {
        val content = a.layoutInflater.inflate(layout, null)
        val land = a.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val nav: NavigationBarView = if (land) NavigationRailView(a) else BottomNavigationView(a)
        nav.id = R.id.tabBar
        nav.inflateMenu(R.menu.calc_tabs)
        nav.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        nav.selectedItemId = tab.menuId
        nav.setOnItemSelectedListener { item ->
            val to = Tab.entries.firstOrNull { it.menuId == item.itemId } ?: return@setOnItemSelectedListener false
            if (to != tab) go(a, from = tab, to = to)
            false
        }
        nav.setOnItemReselectedListener { }
        val divider = View(a).apply { setBackgroundColor(themeColor(com.google.android.material.R.attr.colorOutlineVariant)) }
        val row = LinearLayout(a).apply { orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (land) {
            row.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(divider, LinearLayout.LayoutParams(a.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        } else {
            row.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            row.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(1)))
            row.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val frame = FrameLayout(a)
        frame.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.findViewById<View>(R.id.launchOverlay)?.let { ov ->
            (ov.parent as? ViewGroup)?.removeView(ov)
            frame.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        a.setContentView(frame)
        return nav
    }

    fun intentFor(a: Activity, to: Tab): Intent = when (to) {
        Tab.CALCULATOR -> Intent(a, CalculatorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        Tab.GEAR -> Intent(a, MyGearActivity::class.java)
        Tab.ANALYSIS -> Intent(a, AnalysisActivity::class.java)
        Tab.MORE -> Intent(a, MoreActivity::class.java)
    }

    fun go(a: Activity, from: Tab, to: Tab) {
        a.startActivity(intentFor(a, to))
        if (from != Tab.CALCULATOR && to != Tab.CALCULATOR) a.finish()
        fade(a)
    }

    fun open(a: Activity, to: Tab) {
        if (to == Tab.CALCULATOR) { a.startActivity(intentFor(a, to)); fade(a); return }
        a.startActivity(Intent(a, CalculatorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        a.startActivity(intentFor(a, to))
        fade(a)
    }

    @Suppress("DEPRECATION")
    fun fade(a: Activity) {
        val inA = if (Motion.on(a)) android.R.anim.fade_in else 0
        val outA = if (Motion.on(a)) android.R.anim.fade_out else 0
        a.overridePendingTransition(inA, outA)
    }
}
