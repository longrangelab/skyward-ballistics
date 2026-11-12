package com.longrange.dragcurve.sync

import com.longrange.dragcurve.exchange.LibraryIngest
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.JsonProfileStore
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface SharedFileAccess {
    val name: String
    fun read(): String?
    fun write(text: String)
}

open class SharedFileUnreachableException(message: String) : IOException(message)

class DeletionsNeedConfirmation(val count: Int, val lines: List<String>) : RuntimeException("The shared file would remove $count record(s) here.")

class SharedFileEmptied(val missing: Boolean) : RuntimeException(if (missing) "The shared file is missing." else "The shared file is empty.")

class SharedFileRemote(
    private val access: SharedFileAccess,
    private val refuseEmpty: Boolean = false,
    private val onDownload: (String) -> Unit = {},
) : RemoteFile {
    private var last: String? = null

    override fun list(): List<RemoteMeta> {
        val t = access.read()
        last = t
        if (t.isNullOrBlank()) {
            if (refuseEmpty) throw SharedFileEmptied(missing = t == null)
            return emptyList()
        }
        return listOf(RemoteMeta(ID, version(t)))
    }

    override fun meta(id: String): RemoteMeta? {
        val t = access.read()
        return if (t.isNullOrBlank()) null else RemoteMeta(ID, version(t))
    }

    override fun download(id: String): String {
        val t = last?.takeIf { it.isNotBlank() } ?: access.read() ?: throw SharedFileUnreachableException("The shared file disappeared while syncing.")
        onDownload(t)
        return t
    }

    override fun create(content: String): RemoteMeta = update(ID, content)

    override fun update(id: String, content: String): RemoteMeta {
        access.write(content)
        last = content
        return RemoteMeta(ID, version(content))
    }

    override fun delete(id: String) {}

    companion object {
        const val ID = "shared"
        fun version(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class LocalFileAccess(val file: File, private val tries: Int = 10, private val pauseMs: Long = 300) : SharedFileAccess {
    override val name: String get() = file.name

    override fun read(): String? {
        if (!file.exists()) return null
        return retry("read") { LibraryIngest.decode(file.readBytes()) }
    }

    override fun write(text: String) {
        val dir = file.absoluteFile.parentFile
        if (dir == null || !dir.isDirectory) throw SharedFileUnreachableException(
            "The folder of the shared file (${dir?.path ?: file.path}) can't be reached. Is Google Drive for desktop running and signed in?")
        val tmp = File(dir, file.name + ".skyward-tmp")
        try {
            retry("write") {
                tmp.writeText(text, Charsets.UTF_8)
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
        if (read() != text) throw SharedFileUnreachableException("The shared file was not saved completely. Press Sync again.")
    }

    private fun <T> retry(what: String, block: () -> T): T {
        var last: IOException? = null
        repeat(tries) { i ->
            try { return block() } catch (e: IOException) {
                last = e
                if (i < tries - 1) Thread.sleep(pauseMs)
            }
        }
        throw SharedFileUnreachableException("Can't $what the shared file ${file.path}: ${last?.message ?: "it is in use"}. " +
            "Google Drive may be busy with it; try Sync again in a moment.")
    }
}

object SharedFileSync {
    data class Options(
        val deviceId: String,
        val lastSyncAt: Long = 0L,
        val allowDeletions: Int? = null,
        val allowEmptyFile: Boolean = false,
        val automatic: Boolean = false,
    )

    data class Changes(val added: Int, val updated: Int, val removed: Int, val libAdded: Int, val libUpdated: Int, val libRemoved: Int)

    sealed class Outcome {
        abstract val text: String
        open val ok: Boolean get() = false
        data class Done(val result: SyncResult, val here: Changes, override val text: String) : Outcome() { override val ok get() = true }
        data class NeedsConfirmation(val count: Int, val lines: List<String>, override val text: String) : Outcome()
        data class FileEmptied(val missing: Boolean, override val text: String) : Outcome()
        data class Failed(override val text: String) : Outcome()
    }

    fun autoDeletionLimit(recordsHere: Int): Int = minOf(10, recordsHere / 3)

    fun run(store: ProfileStore, access: SharedFileAccess, o: Options): Outcome {
        val before = SyncContent.of(store)
        val here = before.rifles.size + before.loads.size + before.bullets.size + before.cards.size + before.shots.size
        val limit = o.allowDeletions ?: if (o.automatic) autoDeletionLimit(here) else 0
        val gate: (String) -> Unit = { text ->
            val parsed = try { SyncFile.parse(text) } catch (e: IllegalArgumentException) { null }
            if (parsed != null) {
                val r = removals(store, parsed)
                if (r.count > limit) throw DeletionsNeedConfirmation(r.count, r.lines)
            }
        }
        val remote = SharedFileRemote(access, refuseEmpty = o.lastSyncAt > 0 && !o.allowEmptyFile, onDownload = gate)
        val result = try {
            SyncEngine(o.deviceId).sync(store, remote, o.lastSyncAt)
        } catch (e: DeletionsNeedConfirmation) {
            return Outcome.NeedsConfirmation(e.count, e.lines,
                "The shared file says ${e.count} record(s) here were deleted on another device:\n" + e.lines.joinToString("\n") { "•  $it" } +
                    "\n\nNothing has been changed yet.")
        } catch (e: SharedFileEmptied) {
            return Outcome.FileEmptied(e.missing,
                (if (e.missing) "The shared file ${access.name} is not there any more" else "The shared file ${access.name} is empty now") +
                    ", although it held your data at the last sync. Nothing was changed. If you emptied or replaced it on purpose, " +
                    "Skyward can write this device's data into it.")
        }
        if (!result.ok) return Outcome.Failed(explain(result.error ?: "The sync didn't finish."))
        val after = SyncContent.of(store)
        val ch = changes(before, after)
        return Outcome.Done(result, ch, summary(result, ch, after))
    }

    private fun explain(error: String): String =
        error.replace("The sync file in Google Drive can't be used: ", "The shared file can't be used (it is not a Skyward sync or backup file): ")

    fun summary(r: SyncResult, c: Changes, after: SyncContent): String = buildString {
        if (r.createdFile) {
            val fa = after.rifles.size; val ob = after.bullets.size
            append("Wrote this device's $fa firearm${if (fa == 1) "" else "s"}, ${after.loads.size} ammo and $ob own bullet${if (ob == 1) "" else "s"} into the shared file")
            append(" — everything matches the shared file.")
        } else {
            append("Added ${c.added}, updated ${c.updated}, removed ${c.removed}")
            if (r.uploaded && r.pushed > 0) append("; sent ${r.pushed} change${if (r.pushed == 1) "" else "s"} from this device to the shared file")
            append(" — everything matches the shared file.")
        }
        if (c.libAdded + c.libUpdated + c.libRemoved > 0)
            append(" Bullet library: ${c.libAdded} added, ${c.libUpdated} updated, ${c.libRemoved} removed.")
        if (r.conflicts > 0) append(" ${r.conflicts} record(s) had been changed on two devices; the newest change was kept.")
        r.messages.take(3).forEach { append("\n• ").append(it) }
    }

    fun changes(before: SyncContent, after: SyncContent): Changes {
        var a = 0; var u = 0; var d = 0
        fun <T> count(b: Map<String, T>, n: Map<String, T>) {
            n.forEach { (k, v) -> val o = b[k]; if (o == null) a++ else if (o != v) u++ }
            d += b.keys.count { it !in n }
        }
        count(before.rifles, after.rifles); count(before.loads, after.loads); count(before.bullets, after.bullets)
        count(before.cards, after.cards); count(before.shots, after.shots)
        val ra = a; val ru = u; val rd = d
        a = 0; u = 0; d = 0
        count(before.library, after.library)
        return Changes(ra, ru, rd, a, u, d)
    }

    data class Removals(val count: Int, val lines: List<String>)

    fun removals(store: ProfileStore, p: SyncFile.Parsed): Removals {
        val tombs = p.libs.deleted.groupBy { it.kind to it.id }.values.map { g -> g.maxByOrNull { it.deletedAt }!! }
        val rifles = store.rifles().associateBy { it.id }
        val loads = store.loads(null)
        val cards = store.targetCards(null, null)
        val shots = store.shotRecords(null)
        val bullets = store.userBullets().associateBy { it.id }
        val library = store.libraryBullets().associateBy { it.id }
        val goneRifles = HashSet<String>(); val goneLoads = HashSet<String>(); val goneCards = HashSet<String>(); val goneShots = HashSet<String>()
        val lines = ArrayList<String>()
        fun newer(t: com.longrange.dragcurve.model.Tombstone, stamp: Long) = t.deletedAt > stamp
        for (t in tombs.filter { it.kind == JsonProfileStore.RIFLE }) {
            val r = rifles[t.id] ?: continue
            if (!newer(t, r.updatedAt)) continue
            goneRifles += r.id
            val ls = loads.filter { it.rifleId == r.id }
            goneLoads += ls.map { it.id }
            goneCards += cards.filter { it.rifleId == r.id }.map { it.id }
            goneShots += shots.filter { it.rifleId == r.id }.map { it.id }
            lines += "Firearm \"${r.name}\"" + if (ls.isNotEmpty()) " with its ${ls.size} ammo" else ""
        }
        for (t in tombs.filter { it.kind == JsonProfileStore.LOAD }) {
            val l = loads.firstOrNull { it.id == t.id } ?: continue
            if (l.id in goneLoads || !newer(t, l.updatedAt)) continue
            goneLoads += l.id
            goneCards += cards.filter { it.loadId == l.id }.map { it.id }
            lines += "Ammo \"${l.name}\""
        }
        for (t in tombs.filter { it.kind == JsonProfileStore.CARD }) {
            val c = cards.firstOrNull { it.id == t.id } ?: continue
            if (c.id in goneCards || !newer(t, c.updatedAt)) continue
            goneCards += c.id
            lines += "Target card \"${c.name}\""
        }
        var shotLines = 0
        for (t in tombs.filter { it.kind == JsonProfileStore.SHOT }) {
            val s = shots.firstOrNull { it.id == t.id } ?: continue
            if (s.id in goneShots || !newer(t, s.updatedAt)) continue
            goneShots += s.id; shotLines++
        }
        if (shotLines > 0) lines += "$shotLines round-count entr${if (shotLines == 1) "y" else "ies"}"
        val usedAfter = loads.filter { it.id !in goneLoads }.map { it.bulletId }.toSet()
        var goneBullets = 0
        for (t in tombs.filter { it.kind == JsonProfileStore.BULLET }) {
            val b = bullets[t.id] ?: continue
            if (library.containsKey(t.id) || b.id in usedAfter || !newer(t, b.updatedAt)) continue
            goneBullets++
            lines += "Bullet \"${b.name}\""
        }
        var goneLib = 0
        for (t in tombs.filter { it.kind == JsonProfileStore.LIBRARY_BULLET }) {
            val b = library[t.id] ?: continue
            if (b.id in usedAfter || !newer(t, b.updatedAt)) continue
            goneLib++
        }
        if (goneLib > 0) lines += "$goneLib bullet-library bullet(s)"
        val count = goneRifles.size + goneLoads.size + goneCards.size + goneShots.size + goneBullets + goneLib
        return Removals(count, lines.take(12) + if (lines.size > 12) listOf("…and ${lines.size - 12} more") else emptyList())
    }
}
