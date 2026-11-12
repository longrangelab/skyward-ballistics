package com.longrange.dragcurve.store

import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class SafetySnapshots(
    private val storeDir: File,
    val dir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val keep: Int = 10,
    val maxAgeDays: Int = 90,
    val minKeep: Int = 3,
) {
    data class Counts(val firearms: Int, val ammo: Int, val bullets: Int, val cards: Int, val library: Int) {
        val total: Int get() = firearms + ammo + bullets + cards + library
    }

    data class Info(val id: String, val takenAt: Long, val reason: String, val counts: Counts, val libraryHash: String?)

    data class Content(val libs: Libraries, val library: List<Bullet>, val settings: AppSettings?)

    private val libCounts = HashMap<String, Int>()

    @Synchronized fun list(): List<Info> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { f -> runCatching { info(f.name.removeSuffix(".json"), JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull() }
            .sortedByDescending { it.takenAt }

    fun latest(): Info? = list().firstOrNull()

    @Synchronized fun take(reason: String, skipIfNewerThanMs: Long = 0L): Info? {
        val now = clock()
        val last = latest()
        if (last != null && skipIfNewerThanMs > 0 && now - last.takenAt in 0 until skipIfNewerThanMs) return null
        val files = LinkedHashMap<String, String>()
        for (name in COPIED) {
            val f = File(storeDir, name)
            if (f.isFile) files[name] = f.readText(Charsets.UTF_8)
        }
        val libFile = File(storeDir, JsonProfileStore.LIBRARY)
        val libBytes = if (libFile.isFile) libFile.readBytes() else null
        val libHash = libBytes?.let { sha(it) }
        if (files.keys.none { it in RECORDS } && libBytes == null) return null
        if (last != null && last.libraryHash == libHash && sameFiles(last.id, files)) return null

        if (!dir.exists()) dir.mkdirs()
        if (libHash != null) {
            val blob = File(dir, "$LIB_PREFIX$libHash.json")
            if (!blob.exists()) atomicWrite(blob, libBytes)
        }
        val counts = Counts(
            firearms = count(files[JsonProfileStore.RIFLES], "rifles"),
            ammo = count(files[JsonProfileStore.LOADS], "loads"),
            bullets = count(files[JsonProfileStore.BULLETS], "bullets"),
            cards = count(files[JsonProfileStore.CARDS], "cards"),
            library = libHash?.let { h -> libCounts.getOrPut(h) { count(String(libBytes, Charsets.UTF_8), "bullets") } } ?: 0,
        )
        var takenAt = now
        while (File(dir, "$PREFIX$takenAt.json").exists()) takenAt++
        val o = JSONObject().put("format", FORMAT).put("version", 1).put("takenAt", takenAt).put("reason", reason)
            .put("counts", JSONObject().put("firearms", counts.firearms).put("ammo", counts.ammo).put("bullets", counts.bullets)
                .put("cards", counts.cards).put("library", counts.library))
            .put("library", libHash ?: JSONObject.NULL)
            .put("files", JSONObject().also { fo -> files.forEach { (k, v) -> fo.put(k, v) } })
        val id = "$PREFIX$takenAt"
        atomicWrite(File(dir, "$id.json"), o.toString().toByteArray(Charsets.UTF_8))
        prune()
        return Info(id, takenAt, reason, counts, libHash)
    }

    @Synchronized fun prune() {
        val all = list()
        val cutoff = clock() - maxAgeDays * 24L * 3600L * 1000L
        val drop = all.filterIndexed { i, s -> i >= keep || (i >= minKeep && s.takenAt < cutoff) }
        drop.forEach { File(dir, "${it.id}.json").delete() }
        val used = (all - drop.toSet()).mapNotNull { it.libraryHash }.toSet()
        dir.listFiles { f -> f.name.startsWith(LIB_PREFIX) }?.forEach { f ->
            val h = f.name.removePrefix(LIB_PREFIX).removeSuffix(".json")
            if (h !in used) f.delete()
        }
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    @Synchronized fun read(id: String): Content {
        val f = File(dir, "$id.json")
        require(f.isFile) { "That copy is no longer on this phone." }
        val o = JSONObject(f.readText(Charsets.UTF_8))
        require(o.optString("format") == FORMAT) { "That file is not a Skyward safety copy." }
        val tmp = File(dir, "restore-${clock()}")
        try {
            tmp.mkdirs()
            val files = o.optJSONObject("files") ?: JSONObject()
            for (k in files.keys()) if (k in COPIED) File(tmp, k).writeText(files.getString(k), Charsets.UTF_8)
            val h = if (o.isNull("library")) null else o.optString("library").takeIf { it.isNotEmpty() }
            if (h != null) {
                val blob = File(dir, "$LIB_PREFIX$h.json")
                require(blob.isFile) { "The bullet library of that copy is missing." }
                blob.copyTo(File(tmp, JsonProfileStore.LIBRARY), overwrite = true)
            }
            val s = JsonProfileStore(tmp)
            val libs = Libraries(rifles = s.rifles(), loads = s.loads(null), bullets = s.userBullets(), cards = s.targetCards(), shots = s.shotRecords())
            require(s.problems.isEmpty()) { "That copy can't be read: " + s.problems.joinToString(" ") }
            return Content(libs, s.libraryBullets(), if (File(tmp, JsonProfileStore.SETTINGS).isFile) s.settings() else null)
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun info(id: String, o: JSONObject): Info? {
        if (o.optString("format") != FORMAT) return null
        val c = o.optJSONObject("counts") ?: JSONObject()
        return Info(
            id = id, takenAt = o.optLong("takenAt"), reason = o.optString("reason", "Safety copy"),
            counts = Counts(c.optInt("firearms"), c.optInt("ammo"), c.optInt("bullets"), c.optInt("cards"), c.optInt("library")),
            libraryHash = if (o.isNull("library")) null else o.optString("library").takeIf { it.isNotEmpty() },
        )
    }

    private fun sameFiles(id: String, files: Map<String, String>): Boolean = runCatching {
        val o = JSONObject(File(dir, "$id.json").readText(Charsets.UTF_8)).optJSONObject("files") ?: return false
        val mine = files.filterKeys { it != JsonProfileStore.STATE }
        val theirs = o.keys().asSequence().filter { it != JsonProfileStore.STATE }.associateWith { o.getString(it) }
        mine == theirs
    }.getOrDefault(false)

    private fun count(text: String?, key: String): Int =
        if (text == null) 0 else runCatching { JSONObject(text).optJSONArray(key)?.length() ?: 0 }.getOrDefault(0)

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val FORMAT = "skyward-snapshot"
        const val PREFIX = "snap-"
        const val LIB_PREFIX = "library-"
        val COPIED = listOf(
            JsonProfileStore.RIFLES, JsonProfileStore.LOADS, JsonProfileStore.BULLETS, JsonProfileStore.CARDS,
            JsonProfileStore.SHOTS, JsonProfileStore.TOMBSTONES, JsonProfileStore.SETTINGS, JsonProfileStore.STATE,
        )
        private val RECORDS = setOf(JsonProfileStore.RIFLES, JsonProfileStore.LOADS, JsonProfileStore.BULLETS, JsonProfileStore.CARDS, JsonProfileStore.SHOTS)

        fun sha(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}
