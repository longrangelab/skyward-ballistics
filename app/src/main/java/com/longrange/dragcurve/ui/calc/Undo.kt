package com.longrange.dragcurve.ui.calc

import android.app.Activity
import android.content.Context
import android.view.View
import com.google.android.material.snackbar.Snackbar
import com.longrange.dragcurve.R
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.ProfileStore

object UndoDelete {
    const val WINDOW_MS = 6_000

    private class Pending(val text: String, val at: Long, val restore: Libraries, val selection: Pair<String?, String?>?)
    @Volatile private var pending: Pending? = null

    fun firearmUnit(store: ProfileStore, rifleId: String) = Libraries(
        rifles = store.rifles().filter { it.id == rifleId },
        loads = store.loads(rifleId),
        cards = store.targetCards(rifleId = rifleId),
        shots = store.shotRecords(rifleId),
    )

    fun ammoUnit(store: ProfileStore, loadId: String) = Libraries(
        loads = store.loads(null).filter { it.id == loadId },
        cards = store.targetCards(loadId = loadId),
    )

    fun deleteFirearm(a: Activity, store: ProfileStore, rifleId: String, anchor: View? = null, after: () -> Unit) {
        val unit = firearmUnit(store, rifleId)
        val name = unit.rifles.firstOrNull()?.name ?: return
        val sel = store.selection()
        store.deleteRifle(rifleId)
        val extra = if (unit.loads.isNotEmpty()) " and its ${unit.loads.size} ammo" else ""
        show(a, "Deleted \"$name\"$extra", unit, sel, anchor, after)
        after()
    }

    fun deleteAmmo(a: Activity, store: ProfileStore, loadId: String, anchor: View? = null, after: () -> Unit) {
        val unit = ammoUnit(store, loadId)
        val name = unit.loads.firstOrNull()?.name ?: return
        val sel = store.selection()
        store.deleteLoad(loadId)
        show(a, "Deleted \"$name\"", unit, sel, anchor, after)
        after()
    }

    fun offer(text: String, restore: Libraries, selection: Pair<String?, String?>? = null) {
        pending = Pending(text, System.currentTimeMillis(), restore, selection)
    }

    fun showPending(a: Activity, anchor: View? = null, after: () -> Unit = {}) {
        val p = pending ?: return
        pending = null
        if (System.currentTimeMillis() - p.at > WINDOW_MS) return
        show(a, p.text, p.restore, p.selection, anchor, after)
    }

    fun show(a: Activity, text: String, restore: Libraries, selection: Pair<String?, String?>?, anchor: View?, after: () -> Unit) {
        val root = a.findViewById<View>(R.id.content) ?: a.findViewById(android.R.id.content) ?: return
        val app = a.applicationContext
        val bar = Snackbar.make(root, text, WINDOW_MS).setAction("Undo") {
            undo(app, restore, selection)
            after()
        }
        if (anchor != null && anchor.isShown) bar.anchorView = anchor
        bar.show()
    }

    fun undo(c: Context, restore: Libraries, selection: Pair<String?, String?>?) {
        val store = Stores.get(c)
        store.restoreDeleted(restore)
        if (selection != null) {
            val (r, l) = selection
            val now = store.selection()
            if ((r != null && restore.rifles.any { it.id == r }) || (l != null && restore.loads.any { it.id == l })) {
                store.saveSelection(r ?: now.first, l ?: now.second)
            }
        }
    }
}
