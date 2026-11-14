package com.longrange.dragcurve.ui.calc

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.longrange.dragcurve.exchange.ComponentLibrary
import com.longrange.dragcurve.exchange.EpCatalog
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.model.ImportReport
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object ComponentLibraryUi {
    private const val PREFS = "component_library"
    private const val K_OFFERED = "offered"
    private const val K_AUTO = "autoUpdate"
    private const val K_CHECKED = "checkedAt"
    private const val K_UPDATED = "updatedAt"
    private const val K_ETAG = "etag"
    private const val K_LAST_MODIFIED = "lastModified"
    private const val K_HASH = "hash"
    private const val K_ERROR = "lastError"

    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val checkedThisRun = AtomicBoolean(false)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Status(
        val installed: Boolean, val bullets: Int, val catalog: EpCatalog, val autoUpdate: Boolean,
        val checkedAt: Long, val updatedAt: Long, val lastError: String,
    ) {
        val counts: String get() = ComponentLibrary.counts(bullets, catalog)
    }

    fun status(c: Context): Status {
        val store = Stores.get(c)
        val p = prefs(c)
        val n = store.libraryBullets().count { it.collection == ComponentLibrary.COLLECTION && it.replacedBy.isEmpty() }
        return Status(n > 0, n, EpCatalog.fromJson(store.componentCatalog()), p.getBoolean(K_AUTO, false),
            p.getLong(K_CHECKED, 0L), p.getLong(K_UPDATED, 0L), p.getString(K_ERROR, "").orEmpty())
    }

    fun setAutoUpdate(c: Context, on: Boolean) = prefs(c).edit().putBoolean(K_AUTO, on).apply()

    fun offerPending(c: Context): Boolean {
        if (prefs(c).getBoolean(K_OFFERED, false)) return false
        val s = status(c)
        return !s.installed || s.catalog.isEmpty
    }

    fun offer(a: CalcActivity, onDone: () -> Unit) {
        prefs(a).edit().putBoolean(K_OFFERED, true).apply()
        var downloading = false
        MaterialAlertDialogBuilder(a).setTitle("Download the component library?")
            .setMessage("A free library from Empirical Precision, to get you started:\n\n" +
                "•  about 1,100 bullets with their G7 BCs, some with measured drag curves\n" +
                "•  powders, primers, case makers and cartridge names for your reloading recipes\n\n" +
                "It is about 1 MB, downloaded from empiricalprecision.com. Nothing about you is sent. " +
                "Skyward then checks for a newer copy about once a week; ammo you have set up never changes without you.\n\n" +
                "You can download it later, turn updates off or remove it under More → Libraries.")
            .setPositiveButton("Download") { _, _ ->
                downloading = true
                setAutoUpdate(a, true)
                download(a, onDone)
            }
            .setNegativeButton("Not now", null)
            .setOnDismissListener { if (!downloading) onDone() }
            .show()
    }

    fun download(a: CalcActivity, onDone: () -> Unit = {}) {
        if (!busy.compareAndSet(false, true)) { android.widget.Toast.makeText(a, "The component library is already downloading.", android.widget.Toast.LENGTH_SHORT).show(); onDone(); return }
        val app = a.applicationContext
        android.widget.Toast.makeText(a, "Downloading the component library…", android.widget.Toast.LENGTH_SHORT).show()
        LibraryImport.background(a, { try { update(app, force = false) } finally { busy.set(false) } }) { r ->
            r.onSuccess { o ->
                val rep = o.report
                MaterialAlertDialogBuilder(a).setTitle(if (rep == null) "Component library is up to date" else "Component library ready")
                    .setMessage(o.counts + "." + if (rep == null) "\n\nThere is no newer copy yet." else "\n\n" + ReportText.long(rep) +
                        "\n\nThe bullets are listed as \"${ComponentLibrary.COLLECTION}\" beside your other libraries, and the powders, " +
                        "primers, cases and cartridges are in the reloading recipe of your ammo.")
                    .setPositiveButton(android.R.string.ok, null).setOnDismissListener { onDone() }.show()
            }.onFailure { e ->
                prefs(app).edit().putString(K_ERROR, e.message ?: e.javaClass.simpleName).apply()
                MaterialAlertDialogBuilder(a).setTitle("Couldn't download the component library")
                    .setMessage((e.message ?: e.toString()) + "\n\nCheck the internet connection and try again under More → Libraries.")
                    .setPositiveButton(android.R.string.ok, null).setOnDismissListener { onDone() }.show()
            }
        }
    }

    fun checkIfDue(c: Context) {
        val app = c.applicationContext
        val p = prefs(app)
        if (!p.getBoolean(K_AUTO, false) || !ComponentLibrary.due(p.getLong(K_CHECKED, 0L), System.currentTimeMillis())) return
        if (!status(app).installed || !checkedThisRun.compareAndSet(false, true)) return
        io.execute {
            if (!busy.compareAndSet(false, true)) return@execute
            try { update(app, force = false) } catch (e: Exception) {
                p.edit().putString(K_ERROR, e.message ?: e.javaClass.simpleName).putLong(K_CHECKED, System.currentTimeMillis()).apply()
            } finally { busy.set(false) }
        }
    }

    class Outcome(val report: ImportReport?, val counts: String)

    private fun update(c: Context, force: Boolean): Outcome {
        val p = prefs(c)
        val store = Stores.get(c)
        val have = status(c)
        val fresh = force || !have.installed || have.catalog.isEmpty
        val got = LibraryIngest.fetch(ComponentLibrary.URL,
            p.getString(K_ETAG, null).takeIf { !fresh }, p.getString(K_LAST_MODIFIED, null).takeIf { !fresh })
        val now = System.currentTimeMillis()
        val bytes = got.bytes
        if (bytes == null) {
            p.edit().putLong(K_CHECKED, now).remove(K_ERROR).apply()
            return Outcome(null, have.counts)
        }
        val text = LibraryIngest.decode(bytes)
        val hash = ComponentLibrary.hash(text)
        if (!fresh && hash == p.getString(K_HASH, null)) {
            p.edit().putLong(K_CHECKED, now).putString(K_ETAG, got.etag).putString(K_LAST_MODIFIED, got.lastModified).remove(K_ERROR).apply()
            return Outcome(null, have.counts)
        }
        val parsed = ComponentLibrary.parse(text)
        val rep = ComponentLibrary.apply(store, parsed)
        p.edit().putLong(K_CHECKED, now).putLong(K_UPDATED, now).putString(K_HASH, hash)
            .putString(K_ETAG, got.etag).putString(K_LAST_MODIFIED, got.lastModified).remove(K_ERROR).apply()
        return Outcome(rep, parsed.counts)
    }

    fun remove(c: Context): Boolean {
        if (!ComponentLibrary.remove(Stores.get(c))) return false
        prefs(c).edit().remove(K_HASH).remove(K_ETAG).remove(K_LAST_MODIFIED).remove(K_UPDATED).remove(K_ERROR).putBoolean(K_AUTO, false).apply()
        return true
    }
}
