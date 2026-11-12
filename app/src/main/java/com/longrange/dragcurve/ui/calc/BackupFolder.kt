package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.store.SafetySnapshots
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.Executors

object BackupFolder {
    const val DEBOUNCE_MS = 60_000L
    private const val K_TREE = "backupTree"
    private const val K_NAME = "backupFolderName"
    private const val K_LAST = "backupLastAt"
    private const val K_LAST_FILE = "backupLastFile"
    private const val K_ERROR = "backupError"
    private const val K_HASH = "backupHash"
    private const val K_MADE = "backupMade"

    data class Status(val on: Boolean, val folderName: String, val lastAt: Long, val lastFile: String, val error: String)

    private lateinit var app: Context
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "backup-folder").also { it.isDaemon = true } }
    private val listeners = LinkedHashSet<() -> Unit>()
    private val debounced = Runnable { request(force = false) }

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }

    private fun prefs() = InstallState.device(app)

    fun isOn(c: Context): Boolean { init(c); return prefs().getString(K_TREE, null) != null }

    fun status(c: Context): Status {
        init(c)
        val p = prefs()
        return Status(p.getString(K_TREE, null) != null, p.getString(K_NAME, "") ?: "", p.getLong(K_LAST, 0L),
            p.getString(K_LAST_FILE, "") ?: "", p.getString(K_ERROR, "") ?: "")
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun notifyListeners() { main.post { synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } } } }

    fun choose(c: Context, tree: Uri) {
        init(c)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(tree, flags)
        val old = prefs().getString(K_TREE, null)
        if (old != null && old != tree.toString()) {
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
        val e = prefs().edit().putString(K_TREE, tree.toString()).putString(K_NAME, folderName(tree)).remove(K_ERROR).remove(K_HASH)
        if (old != tree.toString()) e.remove(K_MADE).remove(K_LAST).remove(K_LAST_FILE)
        e.apply()
        request(force = true)
    }

    fun turnOff(c: Context) {
        init(c)
        main.removeCallbacks(debounced)
        prefs().getString(K_TREE, null)?.let { t ->
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(t), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        prefs().edit().remove(K_TREE).remove(K_NAME).remove(K_LAST).remove(K_LAST_FILE).remove(K_ERROR).remove(K_HASH).remove(K_MADE).apply()
        notifyListeners()
    }

    fun onLocalChange() {
        if (!::app.isInitialized || prefs().getString(K_TREE, null) == null) return
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    @Volatile private var resumeCheckedAt = 0L

    fun onAppResume(c: Context) {
        init(c)
        if (prefs().getString(K_TREE, null) == null) return
        val now = System.currentTimeMillis()
        if (now - resumeCheckedAt < 5 * 60_000L) return
        resumeCheckedAt = now
        request(force = false)
    }

    fun backupNow() = request(force = true)

    private fun request(force: Boolean) {
        if (!::app.isInitialized || prefs().getString(K_TREE, null) == null) return
        io.execute { runCatching { writeOnce(force) }; notifyListeners() }
    }

    private fun made(): List<BackupFiles.Made> = runCatching {
        val a = JSONArray(prefs().getString(K_MADE, "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { BackupFiles.Made(it.getString("day"), it.getString("name"), it.getString("uri")) } }
    }.getOrDefault(emptyList())

    private fun saveMade(list: List<BackupFiles.Made>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("day", it.day).put("name", it.name).put("uri", it.uri)) }
        prefs().edit().putString(K_MADE, a.toString()).apply()
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        app.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private fun writeOnce(force: Boolean) {
        val treeStr = prefs().getString(K_TREE, null) ?: return
        val tree = Uri.parse(treeStr)
        val cr = app.contentResolver
        try {
            if (cr.persistedUriPermissions.none { it.uri == tree && it.isWritePermission }) throw SecurityException("no permission")
            val store = Stores.get(app)
            val snap = store.snapshot()
            if (snap.rifles.isEmpty() && snap.loads.isEmpty() && snap.bullets.isEmpty()) return
            val text = com.longrange.dragcurve.exchange.Parts.masterText(store)
            val hash = SafetySnapshots.sha(JSONObject(text).apply { remove("exportedAt") }.toString().toByteArray(Charsets.UTF_8))
            val today = LocalDate.now().toString()
            var made = made()
            val mine = made.firstOrNull { it.day == today }?.takeIf { exists(Uri.parse(it.uri)) }
            if (!force && mine != null && hash == prefs().getString(K_HASH, null)) return
            val target: Uri = mine?.let { Uri.parse(it.uri) } ?: run {
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                DocumentsContract.createDocument(cr, parent, "application/json", BackupFiles.name(today))
                    ?: throw IllegalStateException("The folder did not accept a new file.")
            }
            (runCatching { cr.openOutputStream(target, "wt") }.getOrNull() ?: cr.openOutputStream(target))
                ?.bufferedWriter()?.use { it.write(text) } ?: throw IllegalStateException("Can't write the backup file.")
            val name = runCatching {
                cr.query(target, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull() ?: BackupFiles.name(today)
            made = BackupFiles.record(made, BackupFiles.Made(today, name, target.toString()))
            val drop = BackupFiles.toDelete(made)
            drop.forEach { m -> runCatching { DocumentsContract.deleteDocument(cr, Uri.parse(m.uri)) } }
            saveMade(made - drop.toSet())
            prefs().edit().putLong(K_LAST, System.currentTimeMillis()).putString(K_LAST_FILE, name).putString(K_HASH, hash).remove(K_ERROR).apply()
        } catch (e: Exception) {
            val msg = when (e) {
                is SecurityException, is java.io.FileNotFoundException, is IllegalArgumentException ->
                    "The backup folder can't be reached. It may have been removed or renamed, or Skyward lost access to it. Choose the folder again."
                else -> "The last backup didn't work (${e.message ?: e.javaClass.simpleName}). It will be tried again after your next change."
            }
            prefs().edit().putString(K_ERROR, msg).apply()
        }
    }

    private fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        app.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifBlank { "Chosen folder" }
}
