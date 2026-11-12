package com.longrange.dragcurve.desktop

import com.longrange.dragcurve.exchange.FormatRegistry
import com.longrange.dragcurve.exchange.ImportResult
import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.exchange.Part
import com.longrange.dragcurve.exchange.Parts
import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.store.JsonProfileStore
import com.longrange.dragcurve.store.SafetySnapshots
import com.longrange.dragcurve.sync.LocalFileAccess
import com.longrange.dragcurve.sync.SharedFileSync
import java.io.File
import java.util.Properties
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppPaths {
    fun baseDir(): File {
        System.getProperty("skyward.dataDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val appData = System.getenv("APPDATA")
        val windows = System.getProperty("os.name", "").lowercase().contains("windows")
        return if (windows && !appData.isNullOrBlank()) File(appData, "Skyward Ballistics")
        else File(System.getProperty("user.home"), ".skyward")
    }
}

class DesktopData(val baseDir: File, clock: () -> Long = { System.currentTimeMillis() }) {
    val storeDir = File(baseDir, "calc")
    val store = JsonProfileStore(storeDir, clock)
    val snapshots = SafetySnapshots(storeDir, File(baseDir, "snapshots"), clock)

    init {
        storeDir.mkdirs()
        store.safetyHook = { reason -> snapshots.take(reason, skipIfNewerThanMs = COALESCE_MS) }
        FormatRegistry.libraryBullets = { store.libraryBullets() }
    }

    fun exportMasterText(): String = com.longrange.dragcurve.exchange.Parts.masterText(store)

    fun exportMaster(file: File): String {
        val snap = store.snapshot()
        writeAtomic(file, exportMasterText())
        return "${snap.rifles.size} firearms, ${snap.loads.size} ammo, ${snap.bullets.size} of your own bullets, " +
            "${store.libraryBullets().size} library bullets, ${snap.shots.size} round-count entries."
    }

    fun exportLibrary(file: File) {
        writeAtomic(file, SkywardFormat.export(LibraryKind.BULLETS, Libraries(bullets = store.libraryBullets())) { null })
    }

    fun partsOfDatabase(): List<Part> = Parts.list(Parts.everything(store))

    fun exportAlong(picked: Set<String>): List<Part> = Parts.select(Parts.everything(store), picked).broughtAlong

    fun exportParts(file: File, picked: Set<String>): String {
        val sel = Parts.select(Parts.everything(store), picked)
        if (sel.isEmpty) throw IllegalArgumentException("Nothing is chosen.")
        writeAtomic(file, Parts.exportText(store, picked))
        return countText(sel.libs)
    }

    fun partsOfFile(r: ImportResult): List<Part> = Parts.list(r.libs, store, r.kind)

    fun importAlong(r: ImportResult, picked: Set<String>): List<Part> = Parts.select(r.libs, picked, store, r.rifleNameHints).broughtAlong

    fun importParts(r: ImportResult, picked: Set<String>): ImportReport {
        if (picked.isEmpty()) throw IllegalArgumentException("Nothing is chosen.")
        return Parts.import(store, r, picked)
    }

    fun defaultExportName(what: String = "master"): String =
        "skyward-$what-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json"

    fun prepareImport(files: List<File>): LibraryIngest.Prepared {
        val items = ArrayList<LibraryIngest.Item>()
        val skipped = ArrayList<String>()
        var first: Exception? = null
        for (f in files) {
            try {
                if (f.length() > LibraryIngest.MAX_BYTES) throw IllegalArgumentException("${f.name} is larger than ${LibraryIngest.MAX_BYTES / (1024 * 1024)} MB.")
                val p = LibraryIngest.prepare(f.readBytes(), f.name)
                items += p.items; skipped += p.skipped
            } catch (e: Exception) {
                if (files.size == 1) throw e
                if (first == null) first = e
                skipped += "${f.name}: not a format this app reads."
            }
        }
        if (items.isEmpty()) throw first ?: IllegalArgumentException("None of these files is in a format this app reads.")
        return LibraryIngest.Prepared(items, skipped)
    }

    fun commitImport(p: LibraryIngest.Prepared): ImportReport = LibraryIngest.commit(store, p)

    fun importBulletLibrary(file: File): ImportReport {
        val text = LibraryIngest.decode(file.readBytes())
        val libs = SkywardFormat.import(text)
        if (libs.bullets.isEmpty()) throw IllegalArgumentException("This file has no bullets.")
        return store.importBulletLibrary(Libraries(bullets = libs.bullets))
    }

    val sharedSync = SharedSyncSettings(File(baseDir, "shared-sync.properties"))

    fun syncShared(allowDeletions: Int = 0, allowEmptyFile: Boolean = false, automatic: Boolean = false): SharedFileSync.Outcome {
        val path = sharedSync.path ?: return SharedFileSync.Outcome.Failed("Choose the shared file first.")
        val o = try {
            SharedFileSync.run(store, LocalFileAccess(File(path)), SharedFileSync.Options(
                deviceId = sharedSync.deviceId, lastSyncAt = sharedSync.lastSyncAt,
                allowDeletions = if (automatic) null else allowDeletions, allowEmptyFile = allowEmptyFile, automatic = automatic))
        } catch (e: Exception) {
            SharedFileSync.Outcome.Failed("The sync didn't finish: ${e.message ?: e.javaClass.simpleName}")
        }
        sharedSync.record(o)
        return o
    }

    fun safetyCopies(): List<SafetySnapshots.Info> = runCatching { snapshots.list() }.getOrDefault(emptyList())

    fun restoreSafetyCopy(id: String) {
        val content = snapshots.read(id)
        synchronized(store) {
            snapshots.take("Before restoring an earlier copy")
            store.restoreFrom(content.libs, content.library, content.settings)
        }
    }

    companion object {
        const val COALESCE_MS = 60_000L

        fun writeAtomic(file: File, text: String) {
            file.absoluteFile.parentFile?.mkdirs()
            val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        fun countText(libs: Libraries): String {
            fun n(k: Int, one: String, many: String = one + "s") = if (k == 0) null else "$k ${if (k == 1) one else many}"
            val (lib, own) = libs.bullets.partition { it.source == "library" }
            return listOfNotNull(
                n(libs.rifles.size, "firearm"), n(libs.loads.size, "ammo", "ammo"), n(own.size, "own bullet"),
                n(lib.size, "library bullet"), n(libs.cards.size, "target card"), n(libs.shots.size, "round-count entry", "round-count entries"),
            ).joinToString(", ").ifEmpty { "Nothing" } + "."
        }

        fun reportText(r: ImportReport): String = buildString {
            append(
                r.summary.ifBlank {
                    "${r.added} added, ${r.updated} updated" +
                        (if (r.renamed > 0) ", ${r.renamed} renamed" else "") +
                        (if (r.skipped > 0) ", ${r.skipped} unchanged or skipped" else "") +
                        (if (r.deleted > 0) ", ${r.deleted} removed" else "") + "."
                }
            )
            if (r.messages.isNotEmpty()) {
                append("\n\n")
                r.messages.take(30).forEach { append("• ").append(it).append('\n') }
                if (r.messages.size > 30) append("…and ${r.messages.size - 30} more notes.")
            }
        }
    }
}

class SharedSyncSettings(private val file: File) {
    private val p = Properties().also { pr -> runCatching { if (file.exists()) file.inputStream().use { pr.load(it) } } }

    val path: String? get() = p.getProperty("path")?.takeIf { it.isNotBlank() }
    val deviceId: String get() = p.getProperty("deviceId") ?: UUID.randomUUID().toString().also { p.setProperty("deviceId", it); save() }
    val lastSyncAt: Long get() = p.getProperty("lastSyncAt")?.toLongOrNull() ?: 0L
    val lastResult: String? get() = p.getProperty("lastResult")
    val lastOk: Boolean get() = p.getProperty("lastOk") != "false"
    var auto: Boolean
        get() = p.getProperty("auto") != "false"
        set(v) { p.setProperty("auto", v.toString()); save() }

    fun choose(f: File) {
        if (path != f.absolutePath) { p.remove("lastSyncAt"); p.remove("lastResult"); p.remove("lastOk") }
        p.setProperty("path", f.absolutePath)
        save()
    }

    fun stop() { listOf("path", "lastSyncAt", "lastResult", "lastOk").forEach { p.remove(it) }; save() }

    fun record(o: SharedFileSync.Outcome) {
        when (o) {
            is SharedFileSync.Outcome.Done -> { p.setProperty("lastSyncAt", System.currentTimeMillis().toString()); p.setProperty("lastOk", "true") }
            else -> p.setProperty("lastOk", "false")
        }
        p.setProperty("lastResult", when (o) {
            is SharedFileSync.Outcome.NeedsConfirmation -> "Waiting for you: the shared file would remove ${o.count} record(s) here. Press Sync to see what."
            is SharedFileSync.Outcome.FileEmptied -> "Waiting for you: the shared file is ${if (o.missing) "missing" else "empty"}. Press Sync to decide."
            else -> o.text.substringBefore('\n')
        })
        save()
    }

    @Synchronized private fun save() {
        runCatching {
            file.absoluteFile.parentFile?.mkdirs()
            DesktopData.writeAtomic(file, java.io.StringWriter().also { w -> p.store(w, "Skyward Ballistics: shared sync file") }.toString())
        }
    }
}
