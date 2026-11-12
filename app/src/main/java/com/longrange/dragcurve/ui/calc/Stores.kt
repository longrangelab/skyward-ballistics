package com.longrange.dragcurve.ui.calc

import android.content.Context
import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.exchange.EmpiricalLink
import com.longrange.dragcurve.exchange.EpCatalog
import com.longrange.dragcurve.exchange.FormatRegistry
import com.longrange.dragcurve.exchange.LibraryFiles
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.JsonProfileStore
import com.longrange.dragcurve.sync.SyncScheduler
import java.io.File

object Stores {
    @Volatile private var store: ProfileStore? = null

    fun get(context: Context): ProfileStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val app = context.applicationContext
            InstallState.detect(app, dir(app.filesDir))
            val s = JsonProfileStore(dir(app.filesDir))
            FormatRegistry.libraryBullets = { s.libraryBullets() }
            s.safetyHook = { reason -> SafetyCopies.snapshots(app).take(reason, skipIfNewerThanMs = SafetyCopies.COALESCE_MS) }
            SyncScheduler.init(context)
            BackupFolder.init(app)
            com.longrange.dragcurve.sync.SharedSync.init(app)
            s.onDataChanged = { SyncScheduler.onLocalChange(); BackupFolder.onLocalChange(); com.longrange.dragcurve.sync.SharedSync.onLocalChange() }
            store = s
            SafetyCopies.dailyIfDue(app)
            return s
        }
    }

    fun dir(filesDir: File): File = File(filesDir, "calc")

    fun formats(): List<LibraryFormat> = FormatRegistry.all

    fun importInto(context: Context, format: LibraryFormat, text: String): ImportReport =
        LibraryFiles.importInto(get(context), format, text)
}

object BulletLibraryHook {
    fun hasLibrary(c: Context): Boolean = Stores.get(c).libraryBullets().isNotEmpty()

    fun import(c: Context, text: String): ImportReport {
        val libs = SkywardFormat.import(text)
        if (libs.bullets.isEmpty()) throw IllegalArgumentException("This file has no bullets.")
        return Stores.get(c).importBulletLibrary(Libraries(bullets = libs.bullets))
    }

    fun remove(c: Context): Boolean = Stores.get(c).removeLibraryCollection("")
}

object EmpiricalHook {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("empirical_link", Context.MODE_PRIVATE)

    fun linkedUri(c: Context): String? = Stores.get(c).empiricalLink()
    fun setLinkedUri(c: Context, uri: String?) {
        val s = Stores.get(c)
        s.saveEmpiricalLink(uri)
        s.saveEmpiricalLastHash(null)
        if (uri == null) prefs(c).edit().clear().apply()
    }
    fun lastRefresh(c: Context): Long = prefs(c).getLong("time", 0L)
    fun lastResult(c: Context): String? = prefs(c).getString("result", null)

    fun changed(c: Context, text: String): Boolean = Stores.get(c).empiricalLastHash() != EmpiricalLink.hash(text)

    @Synchronized
    fun refresh(c: Context, text: String): ImportReport {
        val store = Stores.get(c)
        store.safetySnapshot("Before Empirical refresh")
        val (libs, report) = EmpiricalLink.refresh(text, store.snapshot(), { id -> store.bullet(id) }, store.libraryBullets())
        store.replaceAll(libs)
        store.saveEmpiricalLastHash(EmpiricalLink.hash(text))
        rememberCatalog(c, text)
        prefs(c).edit().putLong("time", System.currentTimeMillis()).putString("result", ReportText.short(report)).apply()
        return report
    }

    fun preview(c: Context, text: String): EmpiricalLink.Preview {
        val store = Stores.get(c)
        return EmpiricalLink.preview(text, store.snapshot(), { id -> store.bullet(id) }, store.libraryBullets())
    }

    @Synchronized
    fun importSelected(c: Context, text: String, choice: EmpiricalLink.Choice): ImportReport {
        val store = Stores.get(c)
        store.safetySnapshot("Before importing from Empirical Precision")
        val (libs, report) = EmpiricalLink.importSelected(text, store.snapshot(), { id -> store.bullet(id) }, store.libraryBullets(), choice)
        store.replaceAll(libs)
        rememberCatalog(c, text)
        return report
    }

    private fun rememberCatalog(c: Context, text: String) {
        runCatching {
            val store = Stores.get(c)
            store.saveEmpiricalCatalog(EpCatalog.fromJson(store.empiricalCatalog()).plus(EmpiricalLink.catalog(text)).toJson())
        }
    }

    fun recordFailure(c: Context, message: String) {
        prefs(c).edit().putLong("time", System.currentTimeMillis()).putString("result", "Failed: $message").apply()
    }

    fun isEmpirical(ref: String?) = ref?.startsWith(EmpiricalLink.PREFIX) == true
}

object DriveBackupHook {
    private const val MIN_GAP_MS = 10 * 60 * 1000L
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var running = false

    fun state(c: Context) = com.longrange.dragcurve.sync.DriveBackups.state(Stores.get(c))

    private fun email(): String? = runCatching { SyncScheduler.status().email }.getOrNull()

    fun reader(c: Context): com.longrange.dragcurve.sync.DriveReader {
        val app = c.applicationContext
        return com.longrange.dragcurve.sync.DriveRestReader(http = com.longrange.dragcurve.sync.UrlConnectionHttp(10_000), token = {
            try { com.longrange.dragcurve.sync.DriveAuth.tokenSilently(app, email(), com.longrange.dragcurve.sync.DriveAuth.SCOPE_READONLY) }
            catch (e: com.longrange.dragcurve.sync.DriveAuth.SignInRequiredException) { throw com.longrange.dragcurve.sync.RemoteAuthException("Google Drive needs you to allow access again.") }
            catch (e: Exception) { throw java.io.IOException(com.longrange.dragcurve.sync.DriveAuth.explain(e)) }
        })
    }

    fun refresh(c: Context): com.longrange.dragcurve.sync.DriveBackups.Result {
        val app = c.applicationContext
        val file = com.longrange.dragcurve.sync.DriveBackups.copyFile(app.filesDir)
        return try {
            com.longrange.dragcurve.sync.DriveBackups.refresh(Stores.get(app), app.filesDir, reader(app), android.net.Uri.fromFile(file).toString(), System.currentTimeMillis())
        } catch (e: Exception) {
            com.longrange.dragcurve.sync.DriveBackups.Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun onAppResume(c: Context) {
        val st = runCatching { state(c) }.getOrNull() ?: return
        if (!st.enabled || running || System.currentTimeMillis() - st.checkedAt < MIN_GAP_MS) return
        running = true
        val app = c.applicationContext
        io.execute { try { refresh(app) } finally { running = false } }
    }

    fun time(c: Context, rfc3339: String): String = runCatching {
        val fmt = if (android.text.format.DateFormat.is24HourFormat(c)) "d MMM HH:mm" else "d MMM h:mm a"
        java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(java.time.Instant.parse(rfc3339).toEpochMilli()))
    }.getOrDefault(rfc3339)

    fun statusLine(c: Context): String = com.longrange.dragcurve.sync.DriveBackups.statusLine(state(c)) { time(c, it) }

    fun describe(c: Context, r: com.longrange.dragcurve.sync.DriveBackups.Result): String = when (r) {
        is com.longrange.dragcurve.sync.DriveBackups.Result.Updated -> "Found a newer backup: ${r.file.name}, ${time(c, r.file.modifiedTime)} (${r.kind}). Open Browse database to see what is new."
        is com.longrange.dragcurve.sync.DriveBackups.Result.Unchanged -> "Nothing newer. The newest backup is still ${r.file.name}, ${time(c, r.file.modifiedTime)}."
        is com.longrange.dragcurve.sync.DriveBackups.Result.NoBackup -> "No Empirical Precision backup in that folder yet. " + com.longrange.dragcurve.sync.DriveBackups.HELP
        is com.longrange.dragcurve.sync.DriveBackups.Result.FolderMissing -> "That Drive folder can't be found any more. Choose the folder again."
        is com.longrange.dragcurve.sync.DriveBackups.Result.SignInNeeded -> "Google Drive needs you to allow access again. Tap \"Allow access again\"."
        is com.longrange.dragcurve.sync.DriveBackups.Result.Failed -> r.message
        is com.longrange.dragcurve.sync.DriveBackups.Result.Off -> "Turned off."
    }
}

object ReportText {
    fun short(r: ImportReport): String =
        if (r.summary.isNotBlank()) r.summary else "${r.added} added, ${r.updated} updated" +
            (if (r.renamed > 0) ", ${r.renamed} renamed" else "") +
            (if (r.skipped > 0) ", ${r.skipped} skipped" else "")

    fun long(r: ImportReport): String = buildString {
        append(short(r))
        if (r.messages.isNotEmpty()) { append("\n\n"); r.messages.forEach { append("• ").append(it).append('\n') } }
    }
}

object InstallState {
    const val DEVICE_PREFS = "device_local"
    private const val K_GUIDE = "guidePending"
    private const val K_RESTORED = "restoreNoticePending"
    @Volatile var kind: InstallKind = InstallKind.EXISTING
        private set

    fun device(c: Context) = c.applicationContext.getSharedPreferences(DEVICE_PREFS, Context.MODE_PRIVATE)

    fun detect(c: Context, storeDir: File) {
        val hadId = c.getSharedPreferences("drive_sync", Context.MODE_PRIVATE).contains("deviceId")
        val hasFiles = storeDir.listFiles()?.any { it.isFile && it.name.endsWith(".json") } == true
        kind = InstallKind.of(hadId, hasFiles)
        when (kind) {
            InstallKind.FRESH -> device(c).edit().putBoolean(K_GUIDE, true).apply()
            InstallKind.RESTORED -> device(c).edit().putBoolean(K_RESTORED, true).apply()
            InstallKind.EXISTING -> {}
        }
    }

    fun guidePending(c: Context) = device(c).getBoolean(K_GUIDE, false)
    fun guideDone(c: Context) = device(c).edit().putBoolean(K_GUIDE, false).apply()
    fun restoreNoticePending(c: Context) = device(c).getBoolean(K_RESTORED, false)
    fun restoreNoticeShown(c: Context) = device(c).edit().putBoolean(K_RESTORED, false).apply()
}

object SafetyCopies {
    const val COALESCE_MS = 60_000L
    const val DAY_MS = 24L * 3600 * 1000
    @Volatile private var snaps: com.longrange.dragcurve.store.SafetySnapshots? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun snapshots(c: Context): com.longrange.dragcurve.store.SafetySnapshots = snaps ?: synchronized(this) {
        snaps ?: com.longrange.dragcurve.store.SafetySnapshots(Stores.dir(c.applicationContext.filesDir), File(c.applicationContext.filesDir, "snapshots")).also { snaps = it }
    }

    @Volatile private var checkedAt = 0L

    fun dailyIfDue(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        if (now - checkedAt < 3_600_000L) return
        checkedAt = now
        io.execute {
            runCatching {
                val last = snapshots(app).latest()
                if (last == null || System.currentTimeMillis() - last.takenAt >= DAY_MS) Stores.get(app).safetySnapshot("Daily copy")
            }
        }
    }

    fun restore(c: Context, id: String) {
        val app = c.applicationContext
        val store = Stores.get(app) as JsonProfileStore
        val content = snapshots(app).read(id)
        synchronized(store) {
            snapshots(app).take("Before restoring an earlier copy")
            store.restoreFrom(content.libs, content.library, content.settings)
        }
    }
}
