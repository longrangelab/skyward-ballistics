package com.longrange.dragcurve.store

import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.model.ShotRecord
import com.longrange.dragcurve.model.TargetCard
import com.longrange.dragcurve.model.Tombstone
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonProfileStore(
    private val dir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : ProfileStore {
    val problems: MutableList<String> = mutableListOf()

    @Volatile var onDataChanged: (() -> Unit)? = null

    @Volatile var safetyHook: ((String) -> Unit)? = null

    private val syncMode = ThreadLocal<Boolean>()

    fun <T> syncing(block: () -> T): T {
        val prev = syncMode.get()
        syncMode.set(true)
        try { return block() } finally { if (prev == null) syncMode.remove() else syncMode.set(prev) }
    }

    private val isSyncing: Boolean get() = syncMode.get() == true

    private fun risky(reason: String) { safetyHook?.let { h -> runCatching { h(reason) } } }

    @Synchronized override fun safetySnapshot(reason: String) = risky(reason)

    @Synchronized fun reload() {
        rifleCache = null; loadCache = null; bulletCache = null; libraryCache = null; libraryIds = emptyMap()
        tombCache = null; cardCache = null; shotCache = null; stateCache = null; settingsCache = null
    }

    val folder: File get() = dir

    private var rifleCache: List<Rifle>? = null
    private var loadCache: List<Load>? = null
    private var bulletCache: List<Bullet>? = null
    private var libraryCache: List<Bullet>? = null
    private var libraryIds: Map<String, Bullet> = emptyMap()
    private var tombCache: List<Tombstone>? = null
    private var cardCache: List<TargetCard>? = null
    private var shotCache: List<ShotRecord>? = null
    private var stateCache: JSONObject? = null
    private var settingsCache: AppSettings? = null

    @Synchronized override fun rifles(): List<Rifle> =
        rifleCache ?: readList(RIFLES, "rifles") { JsonCodec.rifles(it) }.also { rifleCache = it }

    @Synchronized override fun loads(rifleId: String?): List<Load> {
        val all = loadCache ?: readList(LOADS, "loads") { JsonCodec.loads(it) }.also { loadCache = it }
        return if (rifleId == null) all else all.filter { it.rifleId == rifleId }
    }

    @Synchronized override fun userBullets(): List<Bullet> =
        bulletCache ?: readList(BULLETS, "bullets") { JsonCodec.bullets(it) }.also { bulletCache = it }

    @Synchronized override fun libraryBullets(): List<Bullet> {
        libraryCache?.let { return it }
        val list = readList(LIBRARY, "bullets") { JsonCodec.bullets(it) }
            .map { if (it.source == "library") it else it.copy(source = "library") }
            .sortedWith(compareBy<Bullet>({ it.collection.isNotEmpty() }, { it.collection }, { it.libraryOrder ?: Int.MAX_VALUE }))
        libraryCache = list
        libraryIds = list.associateBy { it.id }
        return list
    }

    @Synchronized override fun targetCards(rifleId: String?, loadId: String?): List<TargetCard> {
        val all = cardCache ?: readList(CARDS, "cards") { JsonCodec.cards(it) }.also { cardCache = it }
        return all.filter { (rifleId == null || it.rifleId == rifleId) && (loadId == null || it.loadId == loadId) }
    }

    @Synchronized override fun shotRecords(rifleId: String?): List<ShotRecord> {
        val all = shotCache ?: readList(SHOTS, "shots") { JsonCodec.shots(it) }.also { shotCache = it }
        return all.filter { rifleId == null || it.rifleId == rifleId }.sortedWith(compareBy({ it.recordedAt }, { it.id }))
    }

    @Synchronized fun tombstones(): List<Tombstone> =
        tombCache ?: readList(TOMBSTONES, "deleted") { JsonCodec.tombstones(it) }.let { prune(it) }.also { tombCache = it }

    @Synchronized override fun importBulletLibrary(libs: Libraries): ImportReport {
        if (!isSyncing && libs.bullets.isNotEmpty()) risky("Before importing a bullet library")
        val cur = LinkedHashMap<String, Bullet>()
        libraryBullets().forEach { cur[it.id] = it }
        val users = userBullets().map { it.id }.toSet()
        val now = clock()
        var added = 0; var updated = 0; var same = 0; var skipped = 0
        val msgs = mutableListOf<String>()
        val touched = HashSet<String>()
        val used = loads(null).map { it.bulletId }.toSet()
        val repoint = HashMap<String, String>()
        for (b0 in libs.bullets) {
            val b = b0.copy(source = "library", updatedAt = if (b0.updatedAt == 0L) now else b0.updatedAt)
            if (b.id in users) { skipped++; msgs += "Library bullet '${b.name}' skipped: a bullet of your own already has its id."; continue }
            val ex = cur[b.id]
            when {
                ex == null -> { cur[b.id] = b; added++; touched += b.id }
                ex.copy(updatedAt = 0) == b.copy(updatedAt = 0) -> same++
                else -> {
                    if (!isSyncing && ex.id in used && ex.replacedBy.isEmpty() && b.replacedBy.isEmpty() && !sameDragData(ex, b)) {
                        var rid = replacedId(ex)
                        while (cur[rid]?.let { sameDragData(it, ex) } == false) rid += "x"
                        cur[rid] = ex.copy(id = rid, replacedBy = ex.id, libraryOrder = null, updatedAt = now)
                        repoint[ex.id] = rid; touched += rid
                    }
                    cur[b.id] = b; updated++; touched += b.id
                }
            }
        }
        if (same > 0) msgs.add(0, "$same library bullet(s) were already up to date.")
        if (repoint.isNotEmpty()) {
            val n = loads(null).count { it.bulletId in repoint }
            writeLoads(loads(null).map { l -> repoint[l.bulletId]?.let { l.copy(bulletId = it, updatedAt = now) } ?: l })
            msgs += "${repoint.size} bullet(s) that your ammo uses got new data. That ammo ($n) keeps the data it was set up with; " +
                "open it to see the newer data and switch when you are ready."
        }
        writeLibrary(cur.values.toList())
        val tombs = tombstones()
        if (tombs.any { it.kind == LIBRARY_BULLET && it.id in touched }) writeTombs(tombs.filterNot { it.kind == LIBRARY_BULLET && it.id in touched })
        return ImportReport(added = added, updated = updated, renamed = 0, skipped = skipped + same, messages = msgs)
    }

    @Synchronized override fun updateLibraryCollection(collection: String, bullets: List<Bullet>): ImportReport {
        require(bullets.all { it.collection == collection }) { "Every bullet must belong to \"$collection\"." }
        val rep = importBulletLibrary(Libraries(bullets = bullets))
        val keep = bullets.map { it.id }.toSet()
        val used = loads(null).map { it.bulletId }.toSet()
        val all = libraryBullets()
        val drop = all.filter { it.collection == collection && it.id !in keep && it.id !in used }.map { it.id }.toSet()
        if (drop.isEmpty()) return rep
        val gone = all.count { it.id in drop && it.replacedBy.isEmpty() }
        writeLibrary(all.filterNot { it.id in drop })
        remember(drop.map { Tombstone(LIBRARY_BULLET, it, clock()) })
        return if (gone == 0) rep else rep.copy(deleted = rep.deleted + gone, messages = rep.messages + "$gone bullet(s) no longer in \"$collection\" were removed.")
    }

    @Synchronized override fun dropReplacedLibraryBullet(id: String): Boolean {
        val b = libraryById(id) ?: return false
        if (b.replacedBy.isEmpty() || loads(null).any { it.bulletId == id }) return false
        writeLibrary(libraryBullets().filter { it.id != id })
        remember(listOf(Tombstone(LIBRARY_BULLET, id, clock())))
        return true
    }

    @Synchronized override fun clearBulletLibrary(): Boolean {
        val ids = libraryBullets().map { it.id }.toSet()
        if (loads(null).any { it.bulletId in ids }) return false
        if (ids.isNotEmpty()) risky("Before removing the bullet library")
        writeLibrary(emptyList())
        val now = clock()
        if (ids.isNotEmpty()) remember(ids.map { Tombstone(LIBRARY_BULLET, it, now) })
        return true
    }

    @Synchronized override fun removeLibraryCollection(collection: String): Boolean {
        val all = libraryBullets()
        val ids = all.filter { it.collection == collection }.map { it.id }.toSet()
        if (loads(null).any { it.bulletId in ids }) return false
        if (ids.isEmpty()) return true
        risky(if (collection.isEmpty()) "Before removing the bullet library" else "Before removing the library \"$collection\"")
        writeLibrary(all.filterNot { it.id in ids })
        val now = clock()
        remember(ids.map { Tombstone(LIBRARY_BULLET, it, now) })
        return true
    }

    @Synchronized override fun applyLibraryTombstones(deleted: List<Tombstone>): Int {
        val lib = deleted.filter { it.kind == LIBRARY_BULLET }
        if (lib.isEmpty()) return 0
        val cur = libraryBullets()
        val used = loads(null).map { it.bulletId }.toSet()
        val keep = ArrayList<Tombstone>()
        val drop = HashSet<String>()
        for (t in lib) {
            val ex = cur.firstOrNull { it.id == t.id }
            if (ex != null && t.deletedAt <= ex.updatedAt) continue
            if (ex != null) { if (ex.id in used) continue; drop += ex.id }
            keep += t
        }
        if (drop.isNotEmpty()) {
            risky("Before Google Drive sync removed ${drop.size} library bullet(s)")
            writeLibrary(cur.filterNot { it.id in drop })
        }
        val known = tombstones()
        val merged = unionTombs(known + keep)
        if (merged.toSet() != known.toSet()) writeTombs(merged)
        return drop.size
    }

    @Synchronized override fun bullet(id: String): Bullet? =
        userBullets().firstOrNull { it.id == id } ?: libraryById(id)

    @Synchronized fun isLibraryBullet(id: String): Boolean = libraryById(id) != null

    private fun libraryById(id: String): Bullet? { libraryBullets(); return libraryIds[id] }

    @Synchronized override fun saveRifle(r: Rifle) {
        val rec = (if (r.id.isBlank()) r.copy(id = newId()) else r).copy(updatedAt = clock())
        writeRifles(upsert(rifles(), rec) { it.id })
        forget(RIFLE, rec.id)
    }

    @Synchronized override fun saveLoad(l: Load) {
        val rec = (if (l.id.isBlank()) l.copy(id = newId()) else l).copy(updatedAt = clock())
        writeLoads(upsert(loads(null), rec) { it.id })
        forget(LOAD, rec.id)
    }

    @Synchronized override fun saveBullet(b: Bullet) { saveUserBullet(b) }

    @Synchronized override fun saveUserBullet(b: Bullet): Bullet {
        val lib = libraryById(b.id)
        val rec = when {
            lib != null -> b.copy(
                id = newId(), source = "user", libraryOrder = null,
                name = if (b.name == lib.name) "${b.name} (user)" else b.name,
            )
            b.id.isBlank() -> b.copy(id = newId(), source = "user")
            else -> if (b.source == "user") b else b.copy(source = "user", libraryOrder = null)
        }.copy(updatedAt = clock(), collection = "")
        writeBullets(upsert(userBullets(), rec) { it.id })
        forget(BULLET, rec.id)
        return rec
    }

    @Synchronized override fun saveTargetCard(c: TargetCard): TargetCard {
        val rec = (if (c.id.isBlank()) c.copy(id = newId()) else c).copy(updatedAt = clock())
        writeCards(upsert(targetCards(), rec) { it.id })
        forget(CARD, rec.id)
        return rec
    }

    @Synchronized override fun deleteTargetCard(id: String) {
        if (targetCards().any { it.id == id }) writeCards(targetCards().filter { it.id != id })
        remember(listOf(Tombstone(CARD, id, clock())))
    }

    @Synchronized override fun saveShotRecord(s: ShotRecord): ShotRecord {
        val rec = (if (s.id.isBlank()) s.copy(id = newId()) else s).copy(updatedAt = clock())
        writeShots(upsert(shotRecords(), rec) { it.id })
        forget(SHOT, rec.id)
        return rec
    }

    @Synchronized override fun deleteShotRecord(id: String) {
        if (shotRecords().any { it.id == id }) writeShots(shotRecords().filter { it.id != id })
        remember(listOf(Tombstone(SHOT, id, clock())))
    }

    private fun dropShots(now: Long, rifleId: String): List<Tombstone> {
        val drop = shotRecords().filter { it.rifleId == rifleId }
        if (drop.isEmpty()) return emptyList()
        writeShots(shotRecords().filter { it.rifleId != rifleId })
        return drop.map { Tombstone(SHOT, it.id, now) }
    }

    private fun dropCards(now: Long, gone: (TargetCard) -> Boolean): List<Tombstone> {
        val drop = targetCards().filter(gone)
        if (drop.isEmpty()) return emptyList()
        writeCards(targetCards().filterNot(gone))
        return drop.map { Tombstone(CARD, it.id, now) }
    }

    @Synchronized override fun deleteRifle(id: String) {
        val now = clock()
        val gone = loads(null).filter { it.rifleId == id }
        if (rifles().any { it.id == id }) writeRifles(rifles().filter { it.id != id })
        if (gone.isNotEmpty()) writeLoads(loads(null).filter { it.rifleId != id })
        val cards = dropCards(now) { it.rifleId == id }
        val shots = dropShots(now, id)
        remember(listOf(Tombstone(RIFLE, id, now)) + gone.map { Tombstone(LOAD, it.id, now) } + cards + shots)
        val (r, l) = selection()
        if (r == id) saveSelection(null, null) else if (l != null && loads(null).none { it.id == l }) saveSelection(r, null)
    }

    @Synchronized override fun deleteLoad(id: String) {
        val now = clock()
        writeLoads(loads(null).filter { it.id != id })
        val cards = dropCards(now) { it.loadId == id }
        remember(listOf(Tombstone(LOAD, id, now)) + cards)
        val (r, l) = selection()
        if (l == id) saveSelection(r, null)
    }

    @Synchronized override fun deleteBullet(id: String): Boolean {
        if (isLibraryBullet(id) && userBullets().none { it.id == id }) return false
        if (loads(null).any { it.bulletId == id }) return false
        writeBullets(userBullets().filter { it.id != id })
        remember(listOf(Tombstone(BULLET, id, clock())))
        return true
    }

    @Synchronized override fun settings(): AppSettings =
        settingsCache ?: (readObject(SETTINGS)?.let { JsonCodec.settings(it) } ?: AppSettings()).also { settingsCache = it }

    @Synchronized override fun saveSettings(s: AppSettings) {
        writeText(SETTINGS, JsonCodec.settings(s).toString(1))
        settingsCache = s
    }

    private fun state(): JSONObject = stateCache ?: (readObject(STATE) ?: JSONObject()).also { stateCache = it }

    private fun saveState(mutate: (JSONObject) -> Unit) {
        val s = JSONObject(state().toString())
        mutate(s)
        writeText(STATE, s.toString(1))
        stateCache = s
    }

    @Synchronized override fun lastShot(): ShotInputs =
        state().optJSONObject("lastShot")?.let { runCatching { JsonCodec.shotInputs(it) }.getOrNull() } ?: ShotInputs()

    @Synchronized override fun saveLastShot(s: ShotInputs) = saveState { it.put("lastShot", JsonCodec.shotInputs(s)) }

    @Synchronized override fun selection(): Pair<String?, String?> {
        val o = state().optJSONObject("selection") ?: return null to null
        return o.optSN("rifleId") to o.optSN("loadId")
    }

    @Synchronized override fun saveSelection(rifleId: String?, loadId: String?) = saveState {
        it.put("selection", JSONObject().putOpt2("rifleId", rifleId).putOpt2("loadId", loadId))
    }

    @Synchronized override fun empiricalLink(): String? = state().optJSONObject("empirical")?.optSN("link")
    @Synchronized override fun saveEmpiricalLink(uri: String?) = saveState { s ->
        val e = s.optJSONObject("empirical") ?: JSONObject()
        if (uri == null) { e.remove("link"); e.remove("lastHash") } else e.put("link", uri)
        s.put("empirical", e)
    }
    @Synchronized override fun profileSources(): List<com.longrange.dragcurve.model.ProfileSource> =
        state().optJSONArray("sources").objects().mapNotNull { o ->
            val id = o.optSN("id"); val uri = o.optSN("uri")
            if (id == null || uri == null) null
            else com.longrange.dragcurve.model.ProfileSource(id, uri, o.optS("name", "Database"), o.optL("lastReadAt"), o.optS("lastError", ""), o.optL("receivedAt"))
        }

    @Synchronized override fun saveProfileSources(sources: List<com.longrange.dragcurve.model.ProfileSource>) = saveState { s ->
        s.put("sources", org.json.JSONArray().also { a ->
            sources.forEach { a.put(JSONObject().put("id", it.id).put("uri", it.uri).put("name", it.name).put("lastReadAt", it.lastReadAt).put("lastError", it.lastError).put("receivedAt", it.receivedAt)) }
        })
    }

    @Synchronized override fun driveBackupState(): String? = state().optJSONObject("driveBackups")?.toString()
    @Synchronized override fun saveDriveBackupState(json: String?) = saveState { s ->
        if (json == null) s.remove("driveBackups") else s.put("driveBackups", JSONObject(json))
    }

    @Synchronized override fun empiricalCatalog(): String? = state().optJSONObject("empirical")?.optJSONObject("catalog")?.toString()
    @Synchronized override fun saveEmpiricalCatalog(json: String?) = saveState { s ->
        val e = s.optJSONObject("empirical") ?: JSONObject()
        if (json == null) e.remove("catalog") else e.put("catalog", JSONObject(json))
        s.put("empirical", e)
    }
    @Synchronized override fun componentCatalog(): String? = readObject(COMPONENTS)?.toString()
    @Synchronized override fun saveComponentCatalog(json: String?) {
        if (json == null) File(dir, COMPONENTS).delete() else writeText(COMPONENTS, json)
    }
    @Synchronized override fun empiricalLastHash(): String? = state().optJSONObject("empirical")?.optSN("lastHash")
    @Synchronized override fun saveEmpiricalLastHash(h: String?) = saveState { s ->
        val e = s.optJSONObject("empirical") ?: JSONObject()
        if (h == null) e.remove("lastHash") else e.put("lastHash", h)
        s.put("empirical", e)
    }

    @Synchronized override fun snapshot(): Libraries = Libraries(rifles(), loads(null), userBullets(), tombstones(), targetCards(), shotRecords())

    @Synchronized override fun replaceAll(libs: Libraries) {
        risky("Before replacing firearms and ammo (Empirical refresh)")
        libraryBullets()
        val now = clock()
        val oldR = rifles().associateBy { it.id }
        val oldL = loads(null).associateBy { it.id }
        val oldB = userBullets().associateBy { it.id }
        val r = libs.rifles.map { n -> oldR[n.id].let { o -> if (o != null && o.copy(updatedAt = 0) == n.copy(updatedAt = 0)) o else n.copy(updatedAt = now) } }
        val l = libs.loads.map { n -> oldL[n.id].let { o -> if (o != null && o.copy(updatedAt = 0) == n.copy(updatedAt = 0)) o else n.copy(updatedAt = now) } }
        val b = libs.bullets.filter { libraryIds[it.id] == null }
            .map { n -> oldB[n.id].let { o -> if (o != null && o.copy(updatedAt = 0) == n.copy(updatedAt = 0)) o else n.copy(updatedAt = now) } }
        val gone = (oldR.keys - r.map { it.id }.toSet()).map { Tombstone(RIFLE, it, now) } +
            (oldL.keys - l.map { it.id }.toSet()).map { Tombstone(LOAD, it, now) } +
            (oldB.keys - b.map { it.id }.toSet()).map { Tombstone(BULLET, it, now) }
        writeBullets(b); writeRifles(r); writeLoads(l)
        val alive = r.map { RIFLE to it.id } + l.map { LOAD to it.id } + b.map { BULLET to it.id } + targetCards().map { CARD to it.id } +
            shotRecords().map { SHOT to it.id }
        writeTombs(unionTombs(tombstones() + gone + libs.deleted).filter { (it.kind to it.id) !in alive.toSet() })
    }

    @Synchronized override fun merge(incoming: Libraries): ImportReport = merge(incoming, emptyMap(), null)

    private enum class Outcome { ADD, IDENTICAL, RENAME, REPLACE, KEEP_LOCAL, STAY_DELETED }

    private fun decide(local: Long?, sameContent: Boolean, stamp: Long, tomb: Tombstone?, kind: LibraryKind?): Outcome {
        val manual = stamp == 0L && kind != LibraryKind.MASTER
        if (local == null) return if (tomb != null && !manual && tomb.deletedAt >= stamp) Outcome.STAY_DELETED else Outcome.ADD
        if (sameContent) return Outcome.IDENTICAL
        if (manual) return Outcome.RENAME
        return if (stamp > local) Outcome.REPLACE else Outcome.KEEP_LOCAL
    }

    @Synchronized
    fun merge(incoming: Libraries, rifleNameHints: Map<String, String>, kind: LibraryKind? = null): ImportReport {
        libraryBullets()
        if (!isSyncing) risky("Before import")
        else wouldDelete(incoming.deleted).takeIf { it > 0 }?.let { n -> risky("Before Google Drive sync removed $n record(s)") }
        val now = clock()
        var added = 0; var renamed = 0; var identical = 0; var skipped = 0; var updated = 0; var deleted = 0
        val msgs = mutableListOf<String>()
        val tombs = tombstones().associateBy { it.kind to it.id }.toMutableMap()
        fun stamp(t: Long) = if (t == 0L) now else t

        val bullets = userBullets().toMutableList()
        val bulletMap = HashMap<String, String>()
        val newLibrary = ArrayList<Bullet>()
        for (b0 in incoming.bullets) {
            val i = bullets.indexOfFirst { it.id == b0.id }
            val lib = libraryIds[b0.id]
            if (b0.source == "library" && i < 0) {
                bulletMap[b0.id] = b0.id
                when {
                    lib == null -> { newLibrary += b0; added++ }
                    lib == b0 -> identical++
                    else -> { identical++; msgs += "Library bullet '${b0.name}' differs from this device's library; kept this device's version." }
                }
                continue
            }
            val b = b0.copy(source = "user", libraryOrder = null, collection = "")
            val ex = bullets.getOrNull(i)
            val outcome = if (ex == null && lib != null) Outcome.RENAME
                else decide(ex?.updatedAt, ex != null && ex.copy(updatedAt = 0) == b.copy(updatedAt = 0), b.updatedAt, tombs[BULLET to b.id], kind)
            when (outcome) {
                Outcome.ADD -> { bullets += b.copy(updatedAt = stamp(b.updatedAt)); bulletMap[b.id] = b.id; tombs.remove(BULLET to b.id); added++ }
                Outcome.IDENTICAL -> { identical++; bulletMap[b.id] = b.id }
                Outcome.RENAME -> {
                    val copy = b.copy(id = newId(), name = b.name + IMPORTED, updatedAt = now)
                    bullets += copy; bulletMap[b0.id] = copy.id; renamed++
                    msgs += "Bullet '${b0.name}' differs from the one already here; imported as '${copy.name}'."
                }
                Outcome.REPLACE -> { bullets[i] = b; bulletMap[b.id] = b.id; updated++ }
                Outcome.KEEP_LOCAL -> { bulletMap[b.id] = b.id; skipped++; msgs += "Bullet '${b.name}': the copy here is newer; kept it." }
                Outcome.STAY_DELETED -> { skipped++; msgs += "Bullet '${b.name}' was deleted here after that copy was saved; left deleted." }
            }
        }

        val rifles = rifles().toMutableList()
        val rifleMap = HashMap<String, String>()
        for (r in incoming.rifles) {
            val i = rifles.indexOfFirst { it.id == r.id }
            val ex = rifles.getOrNull(i)
            when (decide(ex?.updatedAt, ex != null && ex.copy(updatedAt = 0) == r.copy(updatedAt = 0), r.updatedAt, tombs[RIFLE to r.id], kind)) {
                Outcome.ADD -> { rifles += r.copy(updatedAt = stamp(r.updatedAt)); rifleMap[r.id] = r.id; tombs.remove(RIFLE to r.id); added++ }
                Outcome.IDENTICAL -> { identical++; rifleMap[r.id] = r.id }
                Outcome.RENAME -> {
                    val copy = r.copy(id = newId(), name = r.name + IMPORTED, updatedAt = now)
                    rifles += copy; rifleMap[r.id] = copy.id; renamed++
                    msgs += "Firearm '${r.name}' differs from the one already here; imported as '${copy.name}'."
                }
                Outcome.REPLACE -> { rifles[i] = r; rifleMap[r.id] = r.id; updated++ }
                Outcome.KEEP_LOCAL -> { rifleMap[r.id] = r.id; skipped++; msgs += "Firearm '${r.name}': the copy here is newer; kept it." }
                Outcome.STAY_DELETED -> { skipped++; msgs += "Firearm '${r.name}' was deleted here after that copy was saved; left deleted." }
            }
        }

        val loads = loads(null).toMutableList()
        val loadMap = HashMap<String, String>()
        for (l0 in incoming.loads) {
            val rifleId = rifleMap[l0.rifleId]
                ?: l0.rifleId.takeIf { id -> rifles.any { it.id == id } }
                ?: rifleNameHints[l0.id]?.let { name ->
                    rifles.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()?.id
                }
            if (rifleId == null) {
                skipped++
                val hint = rifleNameHints[l0.id]?.let { " ('$it')" } ?: ""
                msgs += "Ammo '${l0.name}' skipped: its firearm$hint is not here. Import the firearm first."
                continue
            }
            val bulletId = bulletMap[l0.bulletId]
                ?: l0.bulletId.takeIf { id -> bullets.any { it.id == id } || libraryIds.containsKey(id) || newLibrary.any { it.id == id } }
            if (bulletId == null) {
                skipped++
                msgs += "Ammo '${l0.name}' skipped: its bullet (${l0.bulletId}) is not in the file or the library."
                continue
            }
            val l = l0.copy(rifleId = rifleId, bulletId = bulletId)
            val i = loads.indexOfFirst { it.id == l.id }
            val ex = loads.getOrNull(i)
            when (decide(ex?.updatedAt, ex != null && ex.copy(updatedAt = 0) == l.copy(updatedAt = 0), l.updatedAt, tombs[LOAD to l.id], kind)) {
                Outcome.ADD -> { loads += l.copy(updatedAt = stamp(l.updatedAt)); loadMap[l0.id] = l.id; tombs.remove(LOAD to l.id); added++ }
                Outcome.IDENTICAL -> { identical++; loadMap[l0.id] = l.id }
                Outcome.RENAME -> {
                    val copy = l.copy(id = newId(), name = l.name + IMPORTED, updatedAt = now)
                    loads += copy; loadMap[l0.id] = copy.id; renamed++
                    msgs += "Ammo '${l.name}' differs from the one already here; imported as '${copy.name}'."
                }
                Outcome.REPLACE -> { loads[i] = l; loadMap[l0.id] = l.id; updated++ }
                Outcome.KEEP_LOCAL -> { loadMap[l0.id] = l.id; skipped++; msgs += "Ammo '${l.name}': the copy here is newer; kept it." }
                Outcome.STAY_DELETED -> { skipped++; msgs += "Ammo '${l.name}' was deleted here after that copy was saved; left deleted." }
            }
        }

        val cards = targetCards().toMutableList()
        for (c0 in incoming.cards) {
            val rid = rifleMap[c0.rifleId] ?: c0.rifleId.takeIf { id -> rifles.any { it.id == id } }
            val lid = loadMap[c0.loadId] ?: c0.loadId.takeIf { id -> loads.any { it.id == id } }
            if (rid == null || lid == null) {
                skipped++; msgs += "Target card '${c0.name}' skipped: its firearm or ammo is not here."; continue
            }
            val c = c0.copy(rifleId = rid, loadId = lid)
            val i = cards.indexOfFirst { it.id == c.id }
            val ex = cards.getOrNull(i)
            when (decide(ex?.updatedAt, ex != null && ex.copy(updatedAt = 0) == c.copy(updatedAt = 0), c.updatedAt, tombs[CARD to c.id], kind)) {
                Outcome.ADD -> { cards += c.copy(updatedAt = stamp(c.updatedAt)); tombs.remove(CARD to c.id); added++ }
                Outcome.IDENTICAL -> identical++
                Outcome.RENAME -> {
                    val copy = c.copy(id = newId(), name = c.name + IMPORTED, updatedAt = now)
                    cards += copy; renamed++
                    msgs += "Target card '${c.name}' differs from the one already here; imported as '${copy.name}'."
                }
                Outcome.REPLACE -> { cards[i] = c; updated++ }
                Outcome.KEEP_LOCAL -> { skipped++; msgs += "Target card '${c.name}': the copy here is newer; kept it." }
                Outcome.STAY_DELETED -> { skipped++; msgs += "Target card '${c.name}' was deleted here after that copy was saved; left deleted." }
            }
        }

        val shots = shotRecords().toMutableList()
        for (s0 in incoming.shots) {
            val rid = rifleMap[s0.rifleId] ?: s0.rifleId.takeIf { id -> rifles.any { it.id == id } }
            if (rid == null) { skipped++; continue }
            val s = s0.copy(rifleId = rid)
            val i = shots.indexOfFirst { it.id == s.id }
            val ex = shots.getOrNull(i)
            when (decide(ex?.updatedAt, ex != null && ex.copy(updatedAt = 0) == s.copy(updatedAt = 0), s.updatedAt, tombs[SHOT to s.id], kind)) {
                Outcome.ADD -> { shots += s.copy(updatedAt = stamp(s.updatedAt)); tombs.remove(SHOT to s.id); added++ }
                Outcome.IDENTICAL -> identical++
                Outcome.RENAME -> { if (ex == null || ex.copy(updatedAt = 0, rifleId = "") != s.copy(updatedAt = 0, rifleId = "")) { shots += s.copy(id = newId(), updatedAt = now); renamed++ } else identical++ }
                Outcome.REPLACE -> { shots[i] = s; updated++ }
                Outcome.KEEP_LOCAL -> skipped++
                Outcome.STAY_DELETED -> skipped++
            }
        }

        for (t in incoming.deleted) {
            when (t.kind) {
                RIFLE -> {
                    val ex = rifles.firstOrNull { it.id == t.id }
                    if (ex != null && t.deletedAt <= ex.updatedAt) continue
                    if (ex != null) {
                        rifles.remove(ex); deleted++
                        loads.filter { it.rifleId == t.id }.forEach { ld -> loads.remove(ld); deleted++; tombs[LOAD to ld.id] = Tombstone(LOAD, ld.id, t.deletedAt) }
                        cards.filter { it.rifleId == t.id }.forEach { cd -> cards.remove(cd); tombs[CARD to cd.id] = Tombstone(CARD, cd.id, t.deletedAt) }
                        shots.filter { it.rifleId == t.id }.forEach { sh -> shots.remove(sh); tombs[SHOT to sh.id] = Tombstone(SHOT, sh.id, t.deletedAt) }
                        msgs += "Firearm '${ex.name}' was deleted on the other side; removed here with its ammo."
                    }
                }
                LOAD -> {
                    val ex = loads.firstOrNull { it.id == t.id }
                    if (ex != null && t.deletedAt <= ex.updatedAt) continue
                    if (ex != null) {
                        loads.remove(ex); deleted++; msgs += "Ammo '${ex.name}' was deleted on the other side; removed here."
                        cards.filter { it.loadId == t.id }.forEach { cd -> cards.remove(cd); tombs[CARD to cd.id] = Tombstone(CARD, cd.id, t.deletedAt) }
                    }
                }
                SHOT -> {
                    val ex = shots.firstOrNull { it.id == t.id }
                    if (ex != null && t.deletedAt <= ex.updatedAt) continue
                    if (ex != null) { shots.remove(ex); deleted++ }
                }
                CARD -> {
                    val ex = cards.firstOrNull { it.id == t.id }
                    if (ex != null && t.deletedAt <= ex.updatedAt) continue
                    if (ex != null) { cards.remove(ex); deleted++; msgs += "Target card '${ex.name}' was deleted on the other side; removed here." }
                }
                BULLET -> {
                    if (libraryIds.containsKey(t.id)) continue
                    val ex = bullets.firstOrNull { it.id == t.id }
                    if (ex != null && t.deletedAt <= ex.updatedAt) continue
                    if (ex != null) {
                        if (loads.any { it.bulletId == t.id }) { msgs += "Bullet '${ex.name}' was deleted on the other side but ammo here still uses it; kept."; continue }
                        bullets.remove(ex); deleted++; msgs += "Bullet '${ex.name}' was deleted on the other side; removed here."
                    }
                }
                else -> continue
            }
            val key = t.kind to t.id
            if ((tombs[key]?.deletedAt ?: Long.MIN_VALUE) < t.deletedAt) tombs[key] = t
        }

        if (identical > 0) msgs.add(0, "$identical record(s) were already here unchanged.")
        if (newLibrary.isNotEmpty()) writeLibrary(libraryBullets() + newLibrary)
        writeBullets(bullets)
        writeRifles(rifles)
        writeLoads(loads)
        if (cards != targetCards()) writeCards(cards)
        if (shots.sortedBy { it.id } != shotRecords().sortedBy { it.id }) writeShots(shots)
        writeTombs(tombs.values.toList())
        return ImportReport(added = added, updated = updated, renamed = renamed, skipped = skipped + identical, messages = msgs, deleted = deleted)
    }

    private fun wouldDelete(deleted: List<Tombstone>): Int = deleted.count { t ->
        val stamp: Long? = when (t.kind) {
            RIFLE -> rifles().firstOrNull { it.id == t.id }?.updatedAt
            LOAD -> loads(null).firstOrNull { it.id == t.id }?.updatedAt
            CARD -> targetCards().firstOrNull { it.id == t.id }?.updatedAt
            SHOT -> shotRecords().firstOrNull { it.id == t.id }?.updatedAt
            BULLET -> userBullets().firstOrNull { it.id == t.id }?.updatedAt
            else -> null
        }
        stamp != null && t.deletedAt > stamp
    }

    @Synchronized override fun restoreDeleted(libs: Libraries) {
        val tombs = tombstones()
        val now = clock()
        fun stamp(kind: String, id: String) = maxOf(now, (tombs.filter { it.kind == kind && it.id == id }.maxOfOrNull { it.deletedAt } ?: 0L) + 1)
        val back = HashSet<Pair<String, String>>()
        val b = libs.bullets.filter { it.source != "library" && libraryById(it.id) == null }
        if (b.isNotEmpty()) {
            var list = userBullets()
            b.forEach { x -> list = upsert(list, x.copy(source = "user", libraryOrder = null, collection = "", updatedAt = stamp(BULLET, x.id))) { it.id }; back += BULLET to x.id }
            writeBullets(list)
        }
        if (libs.rifles.isNotEmpty()) {
            var list = rifles()
            libs.rifles.forEach { x -> list = upsert(list, x.copy(updatedAt = stamp(RIFLE, x.id))) { it.id }; back += RIFLE to x.id }
            writeRifles(list)
        }
        if (libs.loads.isNotEmpty()) {
            var list = loads(null)
            libs.loads.forEach { x -> list = upsert(list, x.copy(updatedAt = stamp(LOAD, x.id))) { it.id }; back += LOAD to x.id }
            writeLoads(list)
        }
        if (libs.cards.isNotEmpty()) {
            var list = targetCards()
            libs.cards.forEach { x -> list = upsert(list, x.copy(updatedAt = stamp(CARD, x.id))) { it.id }; back += CARD to x.id }
            writeCards(list)
        }
        if (libs.shots.isNotEmpty()) {
            var list = shotRecords()
            libs.shots.forEach { x -> list = upsert(list, x.copy(updatedAt = stamp(SHOT, x.id))) { it.id }; back += SHOT to x.id }
            writeShots(list)
        }
        if (tombs.any { (it.kind to it.id) in back }) writeTombs(tombs.filterNot { (it.kind to it.id) in back })
    }

    @Synchronized fun restoreFrom(libs: Libraries, library: List<Bullet>, settings: AppSettings?) {
        libraryBullets()
        val now = clock()
        fun <T> keepOrStamp(old: Map<String, T>, n: T, id: String, bare: (T) -> T, stamped: (T) -> T): T =
            old[id].let { o -> if (o != null && bare(o) == bare(n)) o else stamped(n) }
        val oldR = rifles().associateBy { it.id }
        val oldL = loads(null).associateBy { it.id }
        val oldB = userBullets().associateBy { it.id }
        val oldC = targetCards().associateBy { it.id }
        val oldS = shotRecords().associateBy { it.id }
        val oldLib = libraryBullets().associateBy { it.id }
        val r = libs.rifles.map { keepOrStamp(oldR, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val l = libs.loads.map { keepOrStamp(oldL, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val b = libs.bullets.filter { it.source != "library" }
            .map { keepOrStamp(oldB, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val c = libs.cards.map { keepOrStamp(oldC, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val sh = libs.shots.map { keepOrStamp(oldS, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val lib = library.map { it.copy(source = "library") }
            .map { keepOrStamp(oldLib, it, it.id, { x -> x.copy(updatedAt = 0) }, { x -> x.copy(updatedAt = now) }) }
        val gone = (oldR.keys - r.map { it.id }.toSet()).map { Tombstone(RIFLE, it, now) } +
            (oldL.keys - l.map { it.id }.toSet()).map { Tombstone(LOAD, it, now) } +
            (oldB.keys - b.map { it.id }.toSet()).map { Tombstone(BULLET, it, now) } +
            (oldC.keys - c.map { it.id }.toSet()).map { Tombstone(CARD, it, now) } +
            (oldS.keys - sh.map { it.id }.toSet()).map { Tombstone(SHOT, it, now) } +
            (oldLib.keys - lib.map { it.id }.toSet()).map { Tombstone(LIBRARY_BULLET, it, now) }
        writeLibrary(lib); writeBullets(b); writeRifles(r); writeLoads(l); writeCards(c)
        if (sh.isNotEmpty() || oldS.isNotEmpty()) writeShots(sh)
        val alive = (r.map { RIFLE to it.id } + l.map { LOAD to it.id } + b.map { BULLET to it.id } +
            c.map { CARD to it.id } + sh.map { SHOT to it.id } + lib.map { LIBRARY_BULLET to it.id }).toSet()
        writeTombs(unionTombs(tombstones() + gone).filter { (it.kind to it.id) !in alive })
        if (settings != null) saveSettings(settings)
        val (sr, sl) = selection()
        if (sr != null && r.none { it.id == sr }) saveSelection(null, null)
        else if (sl != null && l.none { it.id == sl }) saveSelection(sr, null)
    }

    private fun prune(l: List<Tombstone>): List<Tombstone> {
        val cutoff = clock() - TOMBSTONE_DAYS * 24L * 3600L * 1000L
        return l.filter { it.deletedAt >= cutoff }
    }

    private fun unionTombs(l: List<Tombstone>): List<Tombstone> =
        l.groupBy { it.kind to it.id }.values.map { g -> g.maxByOrNull { it.deletedAt }!! }

    private fun remember(t: List<Tombstone>) = writeTombs(unionTombs(tombstones() + t))

    private fun forget(kind: String, id: String) {
        val cur = tombstones()
        if (cur.any { it.kind == kind && it.id == id }) writeTombs(cur.filterNot { it.kind == kind && it.id == id })
    }

    private fun writeTombs(l: List<Tombstone>) {
        val p = prune(l)
        if (p.isEmpty() && !File(dir, TOMBSTONES).exists()) { tombCache = p; return }
        writeText(TOMBSTONES, JSONObject().put("version", FILE_VERSION).put("deleted", JsonCodec.tombstones(p)).toString(1))
        tombCache = p
    }

    private fun writeRifles(l: List<Rifle>) { writeText(RIFLES, wrap("rifles", JsonCodec.rifles(l))); rifleCache = l }
    private fun writeLoads(l: List<Load>) { writeText(LOADS, wrap("loads", JsonCodec.loads(l))); loadCache = l }
    private fun writeLibrary(l: List<Bullet>) {
        writeText(LIBRARY, wrap("bullets", JsonCodec.bullets(l)))
        libraryCache = null; libraryIds = emptyMap()
    }
    private fun writeCards(l: List<TargetCard>) { writeText(CARDS, wrap("cards", JsonCodec.cards(l))); cardCache = l }
    private fun writeShots(l: List<ShotRecord>) { writeText(SHOTS, wrap("shots", JsonCodec.shots(l))); shotCache = l }
    private fun writeBullets(l: List<Bullet>) { writeText(BULLETS, wrap("bullets", JsonCodec.bullets(l))); bulletCache = l }

    private fun wrap(key: String, a: org.json.JSONArray): String =
        com.longrange.dragcurve.exchange.LibraryWriter.write(JSONObject().put("version", FILE_VERSION).put(key, a))

    private fun <T> readList(name: String, key: String, decode: (org.json.JSONArray?) -> List<T>): List<T> {
        val o = readObject(name) ?: return emptyList()
        return try { decode(o.optJSONArray(key)) } catch (e: Exception) { quarantine(name, e); emptyList() }
    }

    private fun readObject(name: String): JSONObject? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try { JSONObject(f.readText(Charsets.UTF_8)) } catch (e: Exception) { quarantine(name, e); null }
    }

    private fun quarantine(name: String, e: Exception) {
        val f = File(dir, name)
        val bad = File(dir, "$name.bad-${System.currentTimeMillis()}")
        runCatching { Files.move(f.toPath(), bad.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        problems += "$name could not be read (${e.message}); kept as ${bad.name}."
    }

    private fun writeText(name: String, text: String) {
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (name == STATE) stateCache = null
        if (name != STATE && name != SETTINGS) onDataChanged?.let { cb -> runCatching { cb() } }
    }

    private fun <T> upsert(list: List<T>, rec: T, id: (T) -> String): List<T> {
        val i = list.indexOfFirst { id(it) == id(rec) }
        return if (i < 0) list + rec else list.toMutableList().also { it[i] = rec }
    }

    companion object {
        const val RIFLES = "rifles.json"
        const val LOADS = "loads.json"
        const val BULLETS = "bullets.json"
        const val LIBRARY = "library.json"
        const val TOMBSTONES = "tombstones.json"
        const val CARDS = "cards.json"
        const val SHOTS = "shots.json"
        const val SETTINGS = "settings.json"
        const val STATE = "state.json"
        const val COMPONENTS = "components.json"
        const val FILE_VERSION = 1
        const val IMPORTED = " (imported)"
        const val TOMBSTONE_DAYS = 180
        const val RIFLE = "rifle"
        const val LOAD = "load"
        const val BULLET = "bullet"
        const val CARD = "card"
        const val SHOT = "shot"
        const val LIBRARY_BULLET = "library"

        fun sameDragData(a: Bullet, b: Bullet): Boolean =
            a.dragModel == b.dragModel && a.bcPoints == b.bcPoints && a.dragCurve == b.dragCurve &&
                a.seasonalBcPoints == b.seasonalBcPoints && a.diameterIn == b.diameterIn && a.weightGr == b.weightGr &&
                a.lengthIn == b.lengthIn && a.noseLengthIn == b.noseLengthIn

        fun replacedId(b: Bullet): String = "${b.id}~${b.updatedAt}"
    }
}
