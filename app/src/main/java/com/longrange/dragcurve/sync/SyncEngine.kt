package com.longrange.dragcurve.sync

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.JsonProfileStore
import java.io.IOException

data class SyncResult(
    val ok: Boolean,
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val uploaded: Boolean = false,
    val createdFile: Boolean = false,
    val duplicatesRemoved: Int = 0,
    val attempts: Int = 1,
    val messages: List<String> = emptyList(),
    val error: String? = null,
    val networkError: Boolean = false,
)

class SyncEngine(
    private val deviceId: String,
    private val maxAttempts: Int = 3,
) {
    fun sync(local: ProfileStore, remote: RemoteFile, lastSyncAt: Long = 0L): SyncResult {
        val before = SyncContent.of(local)
        val messages = LinkedHashSet<String>()
        var conflicts = 0
        try {
            for (attempt in 1..maxAttempts) {
                val files = remote.list().sortedWith(compareBy<RemoteMeta>({ it.createdTime }, { it.id }))
                val primary = files.firstOrNull()
                var primaryContent: SyncContent? = null
                val merged = ArrayList<RemoteMeta>()
                for (f in files) {
                    val parsed = try {
                        SyncFile.parse(remote.download(f.id))
                    } catch (e: IllegalArgumentException) {
                        if (f == primary) return SyncResult(false, error = "The sync file in Google Drive can't be used: ${e.message}", attempts = attempt, messages = messages.toList())
                        messages += "Ignored an unreadable extra copy of the sync file (${e.message})."
                        continue
                    }
                    conflicts += conflicts(local, parsed, lastSyncAt)
                    messages += if (local is JsonProfileStore) local.syncing { apply(local, parsed) } else apply(local, parsed)
                    if (f == primary) primaryContent = parsed.content else merged += f
                }

                val after = SyncContent.of(local)
                val upload = primaryContent == null || after != primaryContent
                if (upload) {
                    val text = SyncFile.build(local, deviceId)
                    if (primary == null) {
                        remote.create(text)
                        if (remote.list().size > 1 && attempt < maxAttempts) continue
                    } else {
                        val now = remote.meta(primary.id)
                        if (now == null || now.version != primary.version) continue
                        remote.update(primary.id, text)
                    }
                }
                var removed = 0
                for (d in merged) { remote.delete(d.id); removed++ }
                return SyncResult(
                    ok = true,
                    pulled = after.changesFrom(before),
                    pushed = if (upload) after.changesFrom(primaryContent ?: SyncContent.EMPTY) else 0,
                    conflicts = conflicts,
                    uploaded = upload,
                    createdFile = upload && primary == null,
                    duplicatesRemoved = removed,
                    attempts = attempt,
                    messages = messages.toList(),
                )
            }
            return SyncResult(false, pulled = SyncContent.of(local).changesFrom(before), attempts = maxAttempts, messages = messages.toList(),
                error = "The sync file kept changing while syncing (another device syncing at the same time). It will be tried again.")
        } catch (e: IOException) {
            return SyncResult(false, pulled = SyncContent.of(local).changesFrom(before), messages = messages.toList(),
                error = e.message ?: e.javaClass.simpleName, networkError = e !is RemoteHttpException || e.code >= 500 || e.code == 429)
        }
    }

    private fun apply(local: ProfileStore, p: SyncFile.Parsed): List<String> {
        val msgs = ArrayList<String>()
        val mine = local.libraryBullets().associateBy { it.id }
        val known = local.snapshot().deleted
        val tombs = known.filter { it.kind == JsonProfileStore.LIBRARY_BULLET }.associateBy { it.id }
        val goneLoads = known.filter { it.kind == JsonProfileStore.LOAD }.associateBy { it.id }
        val used = (local.loads(null) + p.libs.loads.filter { l -> goneLoads[l.id].let { it == null || it.deletedAt < l.updatedAt } })
            .map { it.bulletId }.toSet()
        val take = ArrayList<Bullet>()
        for (rb in p.library) {
            val ex = mine[rb.id]
            when {
                ex == null -> {
                    val t = tombs[rb.id]
                    if (t == null || t.deletedAt < rb.updatedAt || rb.id in used) take += rb
                }
                ex.copy(updatedAt = 0, source = "library") == rb.copy(updatedAt = 0) -> {}
                rb.updatedAt > ex.updatedAt -> take += rb
            }
        }
        if (take.isNotEmpty()) msgs += local.importBulletLibrary(Libraries(bullets = take)).messages.filterNot { it.contains("already up to date") }
        val rep = if (local is JsonProfileStore) local.merge(p.libs, p.rifleNameHints, LibraryKind.MASTER) else local.merge(p.libs)
        msgs += rep.messages.filter { m -> NOISE.none { m.contains(it) } }
        local.applyLibraryTombstones(p.libs.deleted)
        return msgs
    }

    private fun conflicts(local: ProfileStore, p: SyncFile.Parsed, lastSyncAt: Long): Int {
        fun <T> count(mine: List<T>, theirs: List<T>, id: (T) -> String, stamp: (T) -> Long, bare: (T) -> T): Int {
            val m = mine.associateBy(id)
            return theirs.count { t ->
                val l = m[id(t)]
                l != null && bare(l) != bare(t) && stamp(l) > lastSyncAt && stamp(t) > lastSyncAt
            }
        }
        val snap = local.snapshot()
        return count(snap.rifles, p.libs.rifles, { it.id }, { it.updatedAt }, { it.copy(updatedAt = 0) }) +
            count(snap.loads, p.libs.loads, { it.id }, { it.updatedAt }, { it.copy(updatedAt = 0) }) +
            count(snap.bullets, p.libs.bullets, { it.id }, { it.updatedAt }, { it.copy(updatedAt = 0, source = "user", libraryOrder = null) }) +
            count(snap.cards, p.libs.cards, { it.id }, { it.updatedAt }, { it.copy(updatedAt = 0) }) +
            count(snap.shots, p.libs.shots, { it.id }, { it.updatedAt }, { it.copy(updatedAt = 0) })
    }

    private companion object {
        val NOISE = listOf("were already here unchanged", "the copy here is newer; kept it", "left deleted")
    }
}
