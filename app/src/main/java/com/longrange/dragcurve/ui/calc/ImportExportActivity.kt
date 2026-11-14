package com.longrange.dragcurve.ui.calc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.ScrollView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.longrange.dragcurve.R
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.exchange.ProfileBrowser
import com.longrange.dragcurve.model.ProfileSource
import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.sync.DriveAuth
import com.longrange.dragcurve.sync.SharedFileSync
import com.longrange.dragcurve.sync.SharedSync
import com.longrange.dragcurve.sync.SyncScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object LibraryImport {
    val MIME = arrayOf(
        "application/json", "text/plain", "application/octet-stream", "text/csv", "text/comma-separated-values",
        "application/zip", "application/x-zip-compressed",
    )
    private val io = Executors.newSingleThreadExecutor()

    fun readText(c: Context, uri: Uri): String =
        c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Can't open the file.")

    fun readBytes(c: Context, uri: Uri): ByteArray =
        (c.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Can't open the file."))
            .use { LibraryIngest.readLimited(it, LibraryIngest.MAX_BYTES) }
            ?: throw IllegalArgumentException("That file is larger than ${LibraryIngest.MAX_BYTES / (1024 * 1024)} MB.")

    fun displayName(c: Context, uri: Uri): String = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    fun <T> background(a: CalcActivity, work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) done(r) }
        }
    }

    fun run(a: CalcActivity, uri: Uri, onDone: () -> Unit) {
        val app = a.applicationContext
        background(a, { BulletLibraryHook.import(app, readText(app, uri)) }) { r ->
            r.onSuccess { rep ->
                val total = Stores.get(app).libraryBullets().size
                MaterialAlertDialogBuilder(a).setTitle("Bullet library imported")
                    .setMessage("$total bullets in the library.\n\n" + ReportText.long(rep))
                    .setPositiveButton(android.R.string.ok, null).show()
            }.onFailure { e ->
                MaterialAlertDialogBuilder(a).setTitle("Couldn't import the bullet library")
                    .setMessage(e.message ?: e.toString()).setPositiveButton(android.R.string.ok, null).show()
            }
            onDone()
        }
    }
}

abstract class ImportExportActivity : CalcActivity() {
    protected enum class Section { LIBRARIES, BACKUP }
    protected abstract val section: Section

    private lateinit var form: Form
    private var pendingKind: LibraryKind? = null
    private var pendingFormat: String? = null
    private var pendingLibrary = false

    private fun exportPicked(uri: Uri?) {
        val kind = pendingKind; val fmt = pendingFormat?.let { id -> Stores.formats().firstOrNull { it.id == id } }
        val library = pendingLibrary
        pendingKind = null; pendingFormat = null; pendingLibrary = false
        if (uri != null && kind != null && fmt != null) writeExport(uri, kind, fmt, library)
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { exportPicked(it) }
    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { exportPicked(it) }

    private val openImport = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) readImport(uris)
    }

    private val openLibrary = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LibraryImport.run(this, uri) { render() }
    }

    private val openSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) LibraryImport.background(this, { ProfileSourcesUi.link(applicationContext, uri) }) { r ->
            r.onSuccess { checkSources() }.onFailure { e -> alert("Can't link that file", e.message ?: e.toString()) }
        }
    }

    private val driveReadConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK && res.data == null) return@registerForActivityResult
        try {
            DriveAuth.resultFromIntent(this, res.data)
            chooseDriveFolder(null)
        } catch (e: Exception) {
            alert("Couldn't get access to Google Drive", DriveAuth.explain(e))
        }
    }

    private fun allowDriveRead() {
        DriveAuth.authorize(this, SyncScheduler.status().email, DriveAuth.SCOPE_READONLY)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) driveReadConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else chooseDriveFolder(null)
            }
            .addOnFailureListener { e -> if (!isFinishing) alert("Couldn't get access to Google Drive", DriveAuth.explain(e)) }
    }

    private fun chooseDriveFolder(search: String?) {
        val app = applicationContext
        LibraryImport.background(this, { DriveBackupHook.reader(app).folders(search) }) { r ->
            r.onSuccess { folders ->
                val items = folders.map { it.name } + listOf("Search by folder name…")
                MaterialAlertDialogBuilder(this)
                    .setTitle(if (search.isNullOrBlank()) "Which Drive folder holds your Empirical backups?" else "Folders matching \"$search\"")
                    .setItems(items.toTypedArray()) { _, i ->
                        if (i == folders.size) askFolderName()
                        else {
                            com.longrange.dragcurve.sync.DriveBackups.choose(store, app.filesDir, folders[i])
                            render(); checkDriveNow()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }.onFailure { e ->
                if (e is com.longrange.dragcurve.sync.RemoteAuthException) allowDriveRead()
                else alert("Can't list your Drive folders", e.message ?: e.toString())
            }
        }
    }

    private fun askFolderName() {
        val input = android.widget.EditText(this).apply { hint = "Part of the folder's name"; setSingleLine(); textSize = 20f }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("Search Drive folders").setView(box)
            .setPositiveButton("Search") { _, _ -> chooseDriveFolder(input.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun checkDriveNow() {
        val app = applicationContext
        LibraryImport.background(this, { DriveBackupHook.refresh(app) }) { r ->
            r.onSuccess { alert("Empirical backups in Google Drive", DriveBackupHook.describe(this, it)) }
            render(); checkSources()
        }
    }

    private fun Form.Group.driveBackupsRow() {
        val st = DriveBackupHook.state(this@ImportExportActivity)
        if (!st.enabled) {
            nav("Empirical backups in Google Drive…", "", sub = "Optional. Pick a Drive folder once; Skyward then shows the newest Empirical backup saved in it") { allowDriveRead() }
            return
        }
        nav("Empirical backups in Google Drive", "", sub = "Folder \"${st.folderName}\" · " + DriveBackupHook.statusLine(this@ImportExportActivity) +
            (if (st.checkedAt != 0L) " · checked " + ago(st.checkedAt) else "")) { manageDriveBackups() }
    }

    private fun manageDriveBackups() {
        val st = DriveBackupHook.state(this)
        val actions = listOfNotNull<Pair<String, () -> Unit>>(
            "Check now" to { checkDriveNow() },
            if (st.error.contains("allow access")) "Allow access again" to { allowDriveRead() } else null,
            "Change folder…" to { chooseDriveFolder(null) },
            "Turn off" to { com.longrange.dragcurve.sync.DriveBackups.turnOff(store, applicationContext.filesDir); render() },
        )
        MaterialAlertDialogBuilder(this).setTitle("Empirical backups in Google Drive")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private val reachable = HashMap<String, Boolean>()

    private val openEmpirical = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) linkEmpirical(uri)
    }

    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != RESULT_OK && res.data == null) return@registerForActivityResult
        try {
            val r = DriveAuth.resultFromIntent(this, res.data)
            SyncScheduler.signedIn(DriveAuth.emailOf(r))
        } catch (e: Exception) {
            alert("Couldn't sign in to Google Drive", DriveAuth.explain(e))
        }
        render()
    }

    private val syncListener: () -> Unit = { if (!isFinishing && !isDestroyed) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.calc_activity_scroll)
        setupToolbar(getString(if (section == Section.LIBRARIES) R.string.calc_libraries else R.string.calc_backup_sync))
        form = contentForm()
        pendingKind = savedInstanceState?.getString("kind")?.let { LibraryKind.valueOf(it) }
        pendingFormat = savedInstanceState?.getString("format")
        pendingLibrary = savedInstanceState?.getBoolean("library") ?: false
    }

    override fun onResume() {
        super.onResume()
        SyncScheduler.addListener(syncListener); BackupFolder.addListener(syncListener); SharedSync.addListener(syncListener)
        render()
        if (section == Section.LIBRARIES) checkSources()
    }

    private fun checkSources() {
        val app = applicationContext
        val list = ProfileBrowser.sources(store)
        LibraryImport.background(this, {
            list.associate { s -> s.id to runCatching { app.contentResolver.openInputStream(Uri.parse(s.uri))?.use { it.read() } != null }.getOrDefault(false) }
        }) { r -> r.onSuccess { reachable.clear(); reachable.putAll(it); render() } }
    }

    private fun Form.Group.empiricalLinkRow() {
        val link = EmpiricalHook.linkedUri(this@ImportExportActivity)
        if (link == null) {
            nav("Link an Empirical backup file…", "", sub = "Optional. Skyward then keeps those firearms and ammo up to date whenever the file changes") {
                openEmpirical.launch(LibraryImport.MIME)
            }
            return
        }
        val t = EmpiricalHook.lastRefresh(this@ImportExportActivity)
        val last = if (t == 0L) "never refreshed" else "refreshed " + SimpleDateFormat(if (DateFormat.is24HourFormat(this@ImportExportActivity)) "d MMM yyyy HH:mm" else "d MMM yyyy h:mm a", Locale.getDefault()).format(Date(t))
        val reach = when (reachable[ProfileBrowser.LINKED_EP_ID]) { false -> "⚠ Can't open the file: link it again · "; else -> "" }
        nav(ProfileBrowser.LINKED_EP_NAME, "", sub = reach + LibraryImport.displayName(this@ImportExportActivity, Uri.parse(link)) +
            "\nKept up to date automatically · $last" + (EmpiricalHook.lastResult(this@ImportExportActivity)?.let { " · $it" } ?: "")) {
            manageEmpiricalLink(Uri.parse(link))
        }
    }

    private fun manageEmpiricalLink(link: Uri) {
        val actions = listOf<Pair<String, () -> Unit>>(
            "Refresh from Empirical now" to { refreshEmpirical(link, force = true) },
            "Change linked file…" to { openEmpirical.launch(LibraryImport.MIME) },
            "Unlink" to {
                runCatching { contentResolver.releasePersistableUriPermission(link, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                EmpiricalHook.setLinkedUri(this, null); render()
            },
        )
        MaterialAlertDialogBuilder(this).setTitle(ProfileBrowser.LINKED_EP_NAME)
            .setMessage("The file is re-read when Skyward starts, if it changed, and its firearms and ammo here are updated. Profiles from it show an \"Empirical\" tag.")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun renderDatabases() {
        val list = ProfileBrowser.sources(store).filter { it.id != ProfileBrowser.LINKED_EP_ID && it.id != com.longrange.dragcurve.sync.DriveBackups.SOURCE_ID }
        form.header("Profile databases")
        form.note("Firearms and ammo in a database are copied into My gear when you tick them in Browse and tap Import. The databases are only read, never changed.")
        form.group {
            empiricalLinkRow()
            list.forEach { s ->
                val read = (if (s.receivedAt != 0L) "shared " + ago(s.receivedAt) + " · " else "") +
                    if (s.lastReadAt == 0L) "not read yet" else "last read " + ago(s.lastReadAt)
                val sub = when (reachable[s.id]) {
                    false -> "⚠ Can't open: the file was moved or deleted. Remove it and link it again"
                    true -> "Reachable · $read"
                    null -> read
                }
                nav(s.name, "", sub = sub) { manageSource(s) }
            }
            driveBackupsRow()
            nav("Add a database…", "", sub = "An Empirical Precision backup, or a Skyward Ballistics library or backup file") { openSource.launch(LibraryImport.MIME) }
            nav(ProfileBrowserActivity.MENU_TITLE, "", sub = "Search the databases and import firearms and ammo") {
                ProfileBrowserActivity.open(this@ImportExportActivity, com.longrange.dragcurve.exchange.BrowseKind.FIREARM, null)
            }
        }
        form.note(com.longrange.dragcurve.exchange.SharedIntake.HELP)
    }

    private fun manageSource(s: ProfileSource) {
        if (s.id == com.longrange.dragcurve.sync.DriveBackups.SOURCE_ID) {
            manageDriveBackups()
            return
        }
        if (s.id == ProfileBrowser.LINKED_EP_ID) {
            EmpiricalHook.linkedUri(this)?.let { manageEmpiricalLink(Uri.parse(it)) }
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(s.name).setItems(arrayOf("Rename", "Remove")) { _, which ->
            if (which == 0) {
                val input = android.widget.EditText(this).apply { setText(s.name); setSingleLine(); textSize = 20f; setSelection(text.length) }
                val pad = (20 * resources.displayMetrics.density).toInt()
                val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
                MaterialAlertDialogBuilder(this).setTitle("Rename database").setView(box)
                    .setPositiveButton(android.R.string.ok) { _, _ -> ProfileBrowser.rename(store, s.id, input.text?.toString().orEmpty()); render() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else {
                MaterialAlertDialogBuilder(this).setTitle("Remove \"${s.name}\"?")
                    .setMessage("The file itself is not touched, and what you imported from it stays. You can link it again later.")
                    .setPositiveButton("Remove") { _, _ -> ProfileSourcesUi.unlink(this, s); render() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onPause() { SyncScheduler.removeListener(syncListener); BackupFolder.removeListener(syncListener); SharedSync.removeListener(syncListener); super.onPause() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingKind?.let { outState.putString("kind", it.name) }
        pendingFormat?.let { outState.putString("format", it) }
        outState.putBoolean("library", pendingLibrary)
    }

    protected fun render() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val y = scroll.scrollY
        form.settings = settings
        form.clear()
        if (section == Section.LIBRARIES) renderLibraries() else renderBackup()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun renderLibraries() {
        renderComponentLibrary()

        form.header("Bullet libraries")
        form.note("Each library is kept apart and labelled with where it came from. When the same bullet is in several, " +
            "the bullet list shows it once with the best data first (your own, then a drag curve, then several BCs, then one BC); press and hold it to choose another source. " +
            "Ammo keeps the bullet data it was set up with: when a library update changes that bullet, the ammo says newer data is available and you switch when you are ready. " +
            "A library that some ammo uses can't be removed.")
        val library = store.libraryBullets().filter { it.replacedBy.isEmpty() && it.collection != com.longrange.dragcurve.exchange.ComponentLibrary.COLLECTION }
        val main = library.filter { it.collection.isEmpty() }
        form.group {
            val imported = main.maxOfOrNull { it.updatedAt }?.takeIf { it > 0 }
            nav("Bullet library", if (main.isEmpty()) "not imported" else "${main.size} bullets",
                sub = if (main.isEmpty()) "Optional. Without one you enter the BC from the box" else
                    "Imported " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(imported ?: 0L)) + " · tap to browse") {
                startActivity(Intent(this@ImportExportActivity, BulletLibraryActivity::class.java).putExtra(BulletLibraryActivity.EXTRA_FILTER, 1))
            }
            library.filter { it.collection.isNotEmpty() }.groupBy { it.collection }.forEach { (name, list) ->
                nav(name, "${list.size}", sub = (list.firstNotNullOfOrNull { it.provenance?.license?.takeIf { l -> l.isNotBlank() } }?.let { "$it · " } ?: "") + "tap to remove") {
                    MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Remove \"$name\"?")
                        .setMessage("Its ${list.size} bullets are removed. Everything else stays. You can import it again later, and a safety copy is kept.")
                        .setPositiveButton("Remove") { _, _ ->
                            if (!store.removeLibraryCollection(name))
                                alert("Library in use", "Some ammo uses a bullet from \"$name\". Change or delete that ammo (or copy the bullet to My bullets and pick the copy) first.")
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            nav("Import a bullet library…", "", sub = "A Skyward bullet library file") { openLibrary.launch(LibraryImport.MIME) }
            nav("Download a bullet or drag file…", "", sub = "A published bullet table or drag file whose web address you type") { askUrl() }
            if (library.isNotEmpty()) nav("Export bullet library…", "", sub = "As a library file or a table (.csv)") { export(LibraryKind.BULLETS, library = true) }
            if (main.isNotEmpty()) nav(getString(R.string.calc_remove_library), "") {
                MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Remove the bullet library?")
                    .setMessage("Your firearms, ammo and own bullets stay. You can import the library again later, and a safety copy is kept.")
                    .setPositiveButton("Remove") { _, _ ->
                        if (!BulletLibraryHook.remove(this@ImportExportActivity))
                            alert("Library in use", "Some ammo uses a library bullet. Change or delete that ammo (or copy the bullet to My bullets and pick the copy) before removing the library.")
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }

        renderDatabases()

        form.header("From other apps")
        form.group {
            nav(ImportFromAppActivity.MENU_TITLE, "", sub = "Reads the firearms and ammo in another calculator app on this phone, without changing it, for you to review and import") {
                ImportFromAppActivity.open(this@ImportExportActivity)
            }
        }
    }

    private fun renderComponentLibrary() {
        val s = ComponentLibraryUi.status(this)
        val day = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        form.header("Component library")
        form.group {
            if (!s.installed) {
                nav("Download the component library", "", sub = "Free from Empirical Precision: about 1,100 bullets with G7 BCs, plus powders, primers, " +
                    "case makers and cartridge names for reloading recipes. About 1 MB.") {
                    ComponentLibraryUi.setAutoUpdate(this@ImportExportActivity, true)
                    ComponentLibraryUi.download(this@ImportExportActivity) { render() }
                }
            } else {
                nav(com.longrange.dragcurve.exchange.ComponentLibrary.TITLE, "${s.bullets}", sub = s.counts +
                    (if (s.updatedAt > 0) "\nUpdated " + day.format(Date(s.updatedAt)) else "") +
                    (if (s.checkedAt > 0) " · checked " + day.format(Date(s.checkedAt)) else "") + " · tap to browse the bullets") {
                    startActivity(Intent(this@ImportExportActivity, BulletLibraryActivity::class.java)
                        .putExtra(BulletLibraryActivity.EXTRA_FILTER, 1)
                        .putExtra(BulletLibraryActivity.EXTRA_QUERY, com.longrange.dragcurve.exchange.ComponentLibrary.COLLECTION))
                }
                switch("Update automatically", s.autoUpdate, sub = "About once a week, when there is a newer copy. Ammo you have set up never changes without you.") { on ->
                    ComponentLibraryUi.setAutoUpdate(this@ImportExportActivity, on)
                }
                nav("Check for an update now", "", sub = if (s.lastError.isNotEmpty()) "⚠ Last check failed: ${s.lastError}" else null) {
                    ComponentLibraryUi.download(this@ImportExportActivity) { render() }
                }
                nav("Remove the component library", "") {
                    MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Remove the component library?")
                        .setMessage("Its bullets and the powder, primer, case and cartridge names are removed from this phone. Names already in your recipes stay. " +
                            "You can download it again later, and a safety copy is kept.")
                        .setPositiveButton("Remove") { _, _ ->
                            if (!ComponentLibraryUi.remove(this@ImportExportActivity))
                                alert("Library in use", "Some ammo uses a bullet from the component library. Change or delete that ammo (or copy the bullet to My bullets and pick the copy) first.")
                            render()
                        }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        form.note(com.longrange.dragcurve.exchange.ComponentLibrary.CREDIT)
    }

    private fun renderBackup() {
        val snap = store.snapshot()
        form.header("How your data is kept safe")
        form.note(DataSafetyText.POINTS.take(4).joinToString("\n") { "•  " + it.substringBefore(":") } + "\n•  Optional: a shared sync file, a backup folder and Google Drive sync, below.")

        renderSharedSync()
        renderBackupFolder()
        renderDriveSync()

        form.header("Earlier copies on this phone")
        form.group {
            val n = runCatching { SafetyCopies.snapshots(this@ImportExportActivity).list().size }.getOrDefault(0)
            nav("Restore an earlier copy…", "$n", sub = "One a day, and one before every import, refresh, library removal and sync that deletes something") {
                startActivity(Intent(this@ImportExportActivity, SnapshotsActivity::class.java))
            }
        }

        form.header("Export")
        form.group {
            nav("Export everything (backup)", "all", sub = "Firearms, ammo, your bullets, the bullet library, target cards and round counts in one file") { export(LibraryKind.MASTER) }
            nav("Export chosen parts…", "", sub = "Pick firearms, ammo, bullets, library bullets, target cards or round counts") {
                startActivity(PartsActivity.exportIntent(this@ImportExportActivity))
            }
            nav("Export Firearms", "${snap.rifles.size}") { export(LibraryKind.RIFLES) }
            nav("Export Ammo", "${snap.loads.size}", sub = "includes the bullets they use") { export(LibraryKind.LOADS) }
            nav("Export My Bullets", "${snap.bullets.size}") { export(LibraryKind.BULLETS) }
        }

        form.header("Import")
        form.group {
            nav("Import files…", "", sub = "One or several files, or a .zip of them: " + Stores.formats().filter { it.importKinds.isNotEmpty() }.joinToString { it.displayName }) {
                openImport.launch(LibraryImport.MIME)
            }
        }
        form.note("Pick one file and you choose which of its firearms, ammo, bullets, library bullets, target cards and round counts to bring in: that only adds and updates, never deletes, and the most recently edited copy wins. Several files (or a .zip) are merged whole. A safety copy is taken first. Published drag files and bullet tables from other sources are kept as their own named libraries, apart from your bullet library.")
    }

    private val openSharedFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) sharedFilePicked(uri) }
    private val createSharedFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) sharedFilePicked(uri) }

    private fun sharedFilePicked(uri: Uri) {
        try {
            SharedSync.choose(this, uri)
        } catch (e: SecurityException) {
            alert("Can't use that file", "Android didn't allow Skyward Ballistics to keep reading and writing it. Pick the file again from Google Drive (or another folder) in the file picker.")
            return
        }
        render()
        syncShared()
    }

    private fun chooseSharedFile() {
        MaterialAlertDialogBuilder(this).setTitle("Shared sync file")
            .setMessage("Every device that should show the same firearms, ammo and bullets uses the same file.\n\n" +
                "First device: create the file, for example skyward-sync.json in a Google Drive folder.\n" +
                "Other devices: choose that same file. In the file picker tap ☰ and Google Drive (or Drive) to find it.")
            .setPositiveButton("Choose the file…") { _, _ -> openSharedFile.launch(arrayOf("*/*")) }
            .setNeutralButton("Create a new file…") { _, _ -> createSharedFile.launch(com.longrange.dragcurve.sync.SyncFile.FILE_NAME) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    protected fun syncShared(allowDeletions: Int = 0, allowEmpty: Boolean = false) {
        SharedSync.syncNow(this, allowDeletions, allowEmpty) { o ->
            if (isFinishing || isDestroyed) return@syncNow
            render()
            when (o) {
                is SharedFileSync.Outcome.Done -> alert("Synced", o.text)
                is SharedFileSync.Outcome.Failed -> alert("Sync didn't finish", o.text)
                is SharedFileSync.Outcome.NeedsConfirmation -> MaterialAlertDialogBuilder(this)
                    .setTitle("Remove ${o.count} record(s) on this device?")
                    .setMessage(o.text + "\n\nA safety copy is taken first (Restore an earlier copy, below).")
                    .setPositiveButton("Remove and sync") { _, _ -> syncShared(o.count, allowEmpty) }
                    .setNegativeButton(android.R.string.cancel, null).show()
                is SharedFileSync.Outcome.FileEmptied -> MaterialAlertDialogBuilder(this)
                    .setTitle(if (o.missing) "The shared file is missing" else "The shared file is empty")
                    .setMessage(o.text)
                    .setPositiveButton("Write this device's data into it") { _, _ -> syncShared(allowDeletions, true) }
                    .setNeutralButton("Choose another file…") { _, _ -> chooseSharedFile() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }
        render()
    }

    private fun renderSharedSync() {
        val st = SharedSync.status(this)
        form.header("Sync with a shared file")
        form.group {
            if (!st.on) {
                nav("Choose or create the shared file…", "", sub = "For example skyward-sync.json in a Google Drive folder. Every phone, tablet and PC that uses the same file shows the same firearms, ammo, bullets, target cards and round counts after Sync") {
                    chooseSharedFile()
                }
            } else {
                info("File", st.fileName)
                val line = when {
                    st.running -> "Syncing…"
                    st.lastResult == null -> "Not synced yet"
                    !st.lastOk -> "⚠ " + st.lastResult
                    else -> "Synced " + ago(st.lastSyncAt) + " · " + st.lastResult
                }
                info("Status", "", sub = line)
                custom(com.google.android.material.button.MaterialButton(this@ImportExportActivity).apply {
                    text = if (st.running) "Syncing…" else "Sync"
                    textSize = 20f
                    isEnabled = !st.running
                    contentDescription = "Sync with the shared file"
                    setOnClickListener { syncShared() }
                })
                switch("Sync automatically", st.auto, sub = "When the app starts or comes back, and a little after changes. It never removes more than a few records without asking") { on ->
                    SharedSync.setAuto(on)
                }
                nav("Change shared file…", "") { chooseSharedFile() }
                nav("Stop using this file", "", sub = "Everything stays on this device and in the file") {
                    MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Stop syncing with \"${st.fileName}\"?")
                        .setMessage("Everything stays on this device, and the file stays where it is. You can choose it again any time.")
                        .setPositiveButton("Stop") { _, _ -> SharedSync.turnOff(this@ImportExportActivity); render() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        form.note("Sync reads the shared file, adds what is new on either side, applies deletions, keeps the newest change of each record, and writes the result back, so this device and the file end up the same. App settings are not synced. Works with any folder the file picker can open, such as Google Drive.")
    }

    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            BackupFolder.choose(this, uri)
            Snackbar.make(findViewById<View>(R.id.content), "Backup folder chosen. The first backup is being written.", Snackbar.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            alert("Can't use that folder", "Android didn't allow Skyward Ballistics to keep writing there. Choose a folder on this phone, for example Documents.")
        }
        render()
    }

    protected fun chooseBackupFolder() = openTree.launch(null)

    private fun renderBackupFolder() {
        val st = BackupFolder.status(this)
        form.header("Automatic backup file")
        form.group {
            if (!st.on) {
                nav("Choose a backup folder…", "", sub = "For example Documents/Skyward. Skyward writes skyward-backup-<date>.json there after changes " +
                    "(one file a day, the last ${BackupFiles.KEEP} kept). The files stay even if the app is uninstalled") { chooseBackupFolder() }
            } else {
                info("Folder", st.folderName)
                info("Last backup", if (st.lastAt == 0L) "not yet" else ago(st.lastAt), sub = st.lastFile.ifEmpty { null })
                if (st.error.isNotEmpty()) info("⚠ Needs a look", "", sub = st.error)
                nav("Back up now", "") { BackupFolder.backupNow(); Snackbar.make(findViewById<View>(R.id.content), "Writing the backup file…", Snackbar.LENGTH_SHORT).show() }
                nav(if (st.error.isNotEmpty()) "Choose the folder again…" else "Change folder…", "") { chooseBackupFolder() }
                nav("Turn off", "", sub = "The files already in the folder stay there") {
                    MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Turn off the automatic backup file?")
                        .setMessage("The backup files already in \"${st.folderName}\" stay there.")
                        .setPositiveButton("Turn off") { _, _ -> BackupFolder.turnOff(this@ImportExportActivity); render() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
    }

    private fun renderDriveSync() {
        val st = SyncScheduler.status()
        form.header("Google Drive sync")
        form.group {
            if (!st.enabled) {
                nav("Sign in to Google Drive", "", sub = "Keep firearms, ammo, bullets and target cards in step across your devices") { signInToDrive() }
            } else {
                info("Account", st.email ?: "Google account")
                val line = when {
                    st.running -> "Syncing…"
                    st.lastResult == null -> "Not synced yet"
                    !st.lastOk -> st.lastResult
                    else -> "Synced " + ago(st.lastSyncAt) + " · " + st.lastResult
                }
                info("Status", "", sub = line)
                if (st.needsSignIn) nav("Sign in to Google Drive", "", sub = "Google needs you to confirm access again") { signInToDrive(st.email) }
                nav("Sync now", "") { SyncScheduler.syncNow(); render() }
                switch("Automatic sync", st.auto, sub = "On start, when you come back to the app, and a few seconds after changes") { on ->
                    SyncScheduler.setAuto(on)
                }
                nav("Turn off sync", "") {
                    MaterialAlertDialogBuilder(this@ImportExportActivity).setTitle("Turn off Google Drive sync?")
                        .setMessage("Everything stays on this phone, and the copy in your Google Drive stays too. You can turn sync on again any time.")
                        .setPositiveButton("Turn off") { _, _ -> SyncScheduler.turnOff(revoke = false); render() }
                        .setNeutralButton("Turn off and remove access") { _, _ -> SyncScheduler.turnOff(revoke = true); render() }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        form.note("Your data is stored in your own Google Drive, in a hidden app folder (it doesn't show in your Drive file list, and nothing goes to any other server). Changes are merged record by record: the newest edit wins and deletions carry over. App settings are not synced.")
    }

    private fun signInToDrive(email: String? = null) {
        DriveAuth.authorize(this, email)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) driveConsent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else { SyncScheduler.signedIn(DriveAuth.emailOf(r)); render() }
            }
            .addOnFailureListener { e -> if (!isFinishing) alert("Couldn't sign in to Google Drive", DriveAuth.explain(e)) }
    }

    protected fun ago(t: Long): String =
        if (t == 0L) "never" else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    protected fun export(kind: LibraryKind, library: Boolean = false) {
        val fmts = Stores.formats().filter { kind in it.exportKinds }
        when {
            fmts.isEmpty() -> alert("Export", "No export format is available for this yet.")
            fmts.size == 1 -> startExport(kind, fmts[0], library)
            else -> MaterialAlertDialogBuilder(this).setTitle("Format")
                .setItems(fmts.map { it.displayName }.toTypedArray()) { _, i -> startExport(kind, fmts[i], library) }.show()
        }
    }

    private fun startExport(kind: LibraryKind, fmt: LibraryFormat, library: Boolean) {
        pendingKind = kind; pendingFormat = fmt.id; pendingLibrary = library
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val what = when (kind) { LibraryKind.RIFLES -> "firearms"; LibraryKind.LOADS -> "ammo"; LibraryKind.BULLETS -> if (library) "bullet-library" else "bullets"; LibraryKind.MASTER -> "master" }
        val prefix = if (fmt.id == SkywardFormat.id) "skyward" else fmt.id
        (if (fmt.mimeType == "text/csv") createCsv else createDoc).launch("$prefix-$what-$day.${fmt.fileExtension}")
    }

    private fun writeExport(uri: Uri, kind: LibraryKind, fmt: LibraryFormat, library: Boolean) {
        val app = applicationContext
        val s = store
        LibraryImport.background(this, {
            val libs = when {
                library -> Libraries(bullets = s.libraryBullets())
                kind == LibraryKind.MASTER && fmt.id == SkywardFormat.id -> com.longrange.dragcurve.exchange.Parts.everything(s)
                else -> s.snapshot()
            }
            val text = fmt.export(kind, libs) { id -> s.bullet(id) }
            (runCatching { app.contentResolver.openOutputStream(uri, "wt") }.getOrNull() ?: app.contentResolver.openOutputStream(uri))
                ?.bufferedWriter()?.use { it.write(text) }
                ?: throw IllegalStateException("Can't write the file.")
        }) { r ->
            r.onSuccess { Snackbar.make(findViewById<View>(R.id.content), "Exported to ${LibraryImport.displayName(this, uri)}", Snackbar.LENGTH_LONG).show() }
                .onFailure { e -> alert("Export failed", e.message ?: e.toString()) }
        }
    }

    private fun readImport(uris: List<Uri>) {
        val app = applicationContext
        LibraryImport.background(this, {
            val items = ArrayList<LibraryIngest.Item>()
            val skipped = ArrayList<String>()
            var firstError: Exception? = null
            for (uri in uris) {
                val name = LibraryImport.displayName(app, uri)
                try {
                    val p = LibraryIngest.prepare(LibraryImport.readBytes(app, uri), name)
                    items += p.items; skipped += p.skipped
                } catch (e: Exception) {
                    if (uris.size == 1) throw e
                    if (firstError == null) firstError = e
                    skipped += "$name: not a format this app reads."
                }
            }
            if (items.isEmpty()) throw firstError ?: IllegalArgumentException("None of these files is in a format this app reads.")
            LibraryIngest.Prepared(items, skipped)
        }) { r ->
            r.onSuccess { confirmImport(it) }
                .onFailure { e -> alert("Can't import", e.message ?: e.toString()) }
        }
    }

    private fun confirmImport(p: LibraryIngest.Prepared) {
        p.items.singleOrNull()?.takeIf { p.skipped.isEmpty() }?.let { item ->
            startActivity(PartsActivity.importIntent(this, item.result, item.name))
            return
        }
        val what = p.formatNames.singleOrNull() ?: "${p.items.size} files"
        val files = if (p.items.size > 1) "${p.items.size} files. " else ""
        val origin = p.items.flatMap { it.result.libs.bullets }.mapNotNull { it.provenance?.license?.takeIf { l -> l.isNotBlank() } }.distinct()
        MaterialAlertDialogBuilder(this).setTitle("Import $what?")
            .setMessage(files + "${p.rifles} firearms, ${p.loads} ammo, ${p.bullets} bullets." +
                (if (p.skipped.isNotEmpty()) "\n\n${p.skipped.size} file(s) could not be read and will be left out." else "") +
                (if (origin.isNotEmpty()) "\n\nLicence / terms: " + origin.joinToString("; ") else ""))
            .setPositiveButton("Import") { _, _ ->
                val s = store
                LibraryImport.background(this, { LibraryIngest.commit(s, p) }) { r ->
                    r.onSuccess { rep -> alert("Import finished", ReportText.long(rep)); render() }
                        .onFailure { e -> alert("Import failed", e.message ?: e.toString()) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askUrl() {
        val input = android.widget.EditText(this).apply {
            hint = "https://…"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle("Download a bullet or drag file")
            .setMessage("Type the address of a file you are allowed to use: a bullet library, a table (.csv), a drag file (.drg) or a .zip of them. Only that file is fetched; nothing of yours is sent.")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                val typed = input.text?.toString().orEmpty()
                val url = try { LibraryIngest.checkUrl(typed) } catch (e: IllegalArgumentException) { alert("Can't download", e.message ?: "That is not a web address."); return@setPositiveButton }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                LibraryImport.background(this, {
                    LibraryIngest.prepare(LibraryIngest.download(url), url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }, url, today)
                }) { r ->
                    r.onSuccess { confirmImport(it) }
                        .onFailure { e -> alert("Can't download", e.message ?: e.toString()) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun linkEmpirical(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            alert("Can't keep access", "Android didn't allow Skyward Ballistics to keep reading this file. Pick it from local storage (Files) rather than a cloud shortcut.")
            return
        }
        EmpiricalHook.linkedUri(this)?.takeIf { it != uri.toString() }?.let { old ->
            runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        EmpiricalHook.setLinkedUri(this, uri.toString())
        refreshEmpirical(uri, force = true)
    }

    private fun refreshEmpirical(uri: Uri, force: Boolean) {
        val app = applicationContext
        LibraryImport.background(this, {
            val text = LibraryImport.readText(app, uri)
            if (!force && !EmpiricalHook.changed(app, text)) null else EmpiricalHook.refresh(app, text)
        }) { r ->
            r.onSuccess { rep -> if (rep != null) alert("Empirical refresh", ReportText.long(rep)) }
                .onFailure { e ->
                    EmpiricalHook.recordFailure(app, e.message ?: e.javaClass.simpleName)
                    alert("Empirical refresh failed", e.message ?: e.toString())
                }
            render()
        }
    }
}

class LibrariesActivity : ImportExportActivity() {
    override val section = Section.LIBRARIES
}

class BackupSyncActivity : ImportExportActivity() {
    override val section = Section.BACKUP

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_FOLDER -> chooseBackupFolder()
            ACTION_EXPORT -> export(LibraryKind.MASTER)
            ACTION_SYNC -> syncShared()
        }
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_FOLDER = "folder"
        const val ACTION_EXPORT = "export"
        const val ACTION_SYNC = "sync"
    }
}
