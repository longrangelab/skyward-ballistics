package com.longrange.dragcurve.sync

import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.model.ProfileStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/*
 * Sync with a Coriolis Wind Lab base station over its own Wi-Fi.
 *
 * The base keeps one gear file in this app's library format and never reads it:
 *   GET  /gear/info  {"ok","ver","bytes","gz","max", and on newer firmware "fw","api","formats","engine"}
 *   GET  /gear       the file, usually gzip
 *   POST /gear?base=<ver>&gz=0|1   multipart upload; 409 if someone saved since <ver>
 * Phones on the base's page merge with it record by record, with the same rules
 * as SyncEngine. Here the base is just another RemoteFile, so SyncEngine runs on
 * it unchanged. The base holds at most `max` bytes (384 KB), so the copy written
 * there leaves out bullet-library bullets that don't fit (see BaseTrim).
 */

class BaseResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    fun text(): String = String(body, Charsets.UTF_8)
}

fun interface BaseHttp {
    fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BaseResponse
}

/** Plain HttpURLConnection. On Android `open` is Network::openConnection, so the request goes over the base's Wi-Fi. */
class UrlConnectionBaseHttp(
    private val baseUrl: String = CoriolisBase.DEFAULT_URL,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 30_000,
    private val open: (URL) -> URLConnection = { it.openConnection() },
) : BaseHttp {
    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BaseResponse =
        try {
            once(method, path, headers, body)
        } catch (e: IOException) {
            // The base serves one client at a time; while other phones poll live wind it
            // sometimes drops a connection ("Connection reset"). A GET is safe to repeat once.
            // An upload is not repeated: the base= check would turn a repeat into a false conflict.
            if (method != "GET" || e is java.net.SocketTimeoutException) throw e
            Thread.sleep(300)
            once(method, path, headers, body)
        }

    private fun once(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BaseResponse {
        val c = open(URL(baseUrl.trimEnd('/') + path)) as HttpURLConnection
        try {
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.useCaches = false
            c.instanceFollowRedirects = false
            c.requestMethod = method
            // Asking for gzip ourselves stops Android from unpacking it behind our back; we unpack by magic bytes.
            c.setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = LinkedHashMap<String, String>()
            c.headerFields.forEach { (k, v) -> if (k != null && v != null && v.isNotEmpty()) h[k] = v.first() }
            return BaseResponse(code, h, bytes)
        } finally {
            c.disconnect()
        }
    }
}

/** Something the user can act on, worded for them. An IOException, so SyncEngine reports it instead of crashing. */
class BaseStationException(message: String) : IOException(message)

data class BaseInfo(
    val ok: Boolean,
    val ver: Long,
    val bytes: Long,
    val gz: Boolean,
    val max: Long,
    val fw: String?,
    val api: Int?,
    val formats: List<Int>?,
    val engine: String?,
) {
    /** Why this app must not sync with this base, or null when it may. Older firmware reports no api/formats: allowed. */
    fun problem(): String? = when {
        !ok -> "The base station's storage isn't working, so it can't keep gear. Restart the base station."
        api != null && api > CoriolisBase.API -> "This base station uses a newer gear connection (version $api). Update Skyward Ballistics."
        formats != null && SkywardFormat.VERSION !in formats ->
            "The base station's page can't read this app's library format (version ${SkywardFormat.VERSION}). Update the base station firmware."
        else -> null
    }

    companion object {
        fun parse(text: String): BaseInfo {
            val o = try { JSONObject(text) } catch (e: Exception) { throw BaseStationException("The base station answered with something that isn't gear information.") }
            val formats = o.optJSONArray("formats")?.let { a -> (0 until a.length()).mapNotNull { i -> a.opt(i).let { v -> (v as? Number)?.toInt() } } }
            return BaseInfo(
                ok = o.optBoolean("ok", false),
                ver = o.optLong("ver", 0L),
                bytes = o.optLong("bytes", 0L),
                gz = o.optBoolean("gz", false),
                max = o.optLong("max", 0L).takeIf { it > 0 } ?: CoriolisBase.DEFAULT_MAX_BYTES,
                fw = o.optString("fw", "").ifEmpty { null },
                api = if (o.has("api")) o.optInt("api") else null,
                formats = formats,
                engine = o.optString("engine", "").ifEmpty { null },
            )
        }
    }
}

object CoriolisBase {
    const val DEFAULT_URL = "http://192.168.4.1"
    const val API = 1
    const val DEFAULT_MAX_BYTES = 384L * 1024L
    const val ID = "coriolis-gear"

    fun info(http: BaseHttp): BaseInfo {
        val r = http.request("GET", "/gear/info", emptyMap(), null)
        if (r.code == 404) throw BaseStationException("This Coriolis base station has firmware without gear storage. Update its firmware.")
        if (r.code != 200) throw BaseStationException("The base station answered ${r.code} to /gear/info.")
        return BaseInfo.parse(r.text())
    }

    fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else bytes

    fun decode(bytes: ByteArray): String = String(gunzipIfNeeded(bytes), Charsets.UTF_8).removePrefix("﻿")

    fun multipart(fileBytes: ByteArray, fileName: String = "gear.json", contentType: String = "application/octet-stream"): Pair<String, ByteArray> {
        val boundary = "skyward-" + UUID.randomUUID().toString()
        val out = ByteArrayOutputStream()
        out.write(("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: $contentType\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(fileBytes)
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return "multipart/form-data; boundary=$boundary" to out.toByteArray()
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * Fits a master library into the base's limit (gzip bytes). Everything that is
 * yours always goes: firearms, ammo, your own bullets, target cards, round counts,
 * deletions, and the library bullets your ammo uses. The rest of the bullet library
 * fills what is left: your ammo's calibers first, then in library order, with
 * bullets that newer library data replaced last. Leaving a record out of the
 * base's copy never deletes it anywhere: only a deletion record does that.
 */
object BaseTrim {
    data class Result(val json: JSONObject, val text: String, val gz: ByteArray, val leftOut: Int)

    const val MARGIN_BYTES = 1024L

    fun fit(master: JSONObject, maxGzBytes: Long): Result {
        val bullets = master.optJSONArray("bullets") ?: JSONArray()
        val all = (0 until bullets.length()).mapNotNull { bullets.optJSONObject(it) }
        val (library, own) = all.partition { it.optString("source", "user") == "library" }
        val loads = master.optJSONArray("loads") ?: JSONArray()
        val usedIds = (0 until loads.length()).mapNotNull { loads.optJSONObject(it)?.optString("bulletId", "")?.ifEmpty { null } }.toSet()
        val usedLib = library.filter { it.optString("id") in usedIds }
        val diameters = all.filter { it.optString("id") in usedIds }.map { it.optDouble("diameterIn", Double.NaN) }.filter { !it.isNaN() }
        fun caliberMatch(b: JSONObject): Boolean { val d = b.optDouble("diameterIn", Double.NaN); return diameters.any { Math.abs(it - d) <= 0.0015 } }
        val rest = library.filter { it.optString("id") !in usedIds }.sortedWith(
            compareBy<JSONObject>(
                { if (it.optString("replacedBy", "").isNotEmpty()) 1 else 0 },
                { if (caliberMatch(it)) 0 else 1 },
                { if (it.has("libraryOrder") && !it.isNull("libraryOrder")) it.optInt("libraryOrder") else Int.MAX_VALUE },
                { it.optString("name", "") },
                { it.optString("id", "") },
            ))
        val budget = maxGzBytes - MARGIN_BYTES

        fun build(n: Int): Result {
            val o = JSONObject(master.toString())
            o.put("bullets", JSONArray().also { a -> (own + usedLib + rest.take(n)).forEach { a.put(it) } })
            val text = o.toString()
            return Result(o, text, CoriolisBase.gzip(text.toByteArray(Charsets.UTF_8)), rest.size - n)
        }

        val full = build(rest.size)
        if (full.gz.size <= budget) return full
        val none = build(0)
        if (none.gz.size > budget) return none
        var lo = 0; var hi = rest.size; var best = none
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val r = build(mid)
            if (r.gz.size <= budget) { lo = mid; best = r } else hi = mid - 1
        }
        return best
    }
}

/**
 * The base station as a RemoteFile. One instance per sync. `onDownload` sees the
 * base's file before it is merged (the deletion check uses it).
 */
class CoriolisBaseFile(
    private val http: BaseHttp,
    private val onDownload: (String) -> Unit = {},
) : RemoteFile {
    var info: BaseInfo? = null
        private set
    /** Bullet-library bullets left out of the last copy written to the base. */
    var leftOut: Int = 0
        private set
    var uploaded: Boolean = false
        private set
    private var downloaded: String? = null

    private fun readInfo(): BaseInfo {
        val i = CoriolisBase.info(http)
        i.problem()?.let { throw BaseStationException(it) }
        info = i
        return i
    }

    override fun list(): List<RemoteMeta> {
        val i = readInfo()
        return if (i.bytes <= 0) emptyList() else listOf(RemoteMeta(CoriolisBase.ID, i.ver.toString()))
    }

    override fun meta(id: String): RemoteMeta? {
        val i = readInfo()
        return if (i.bytes <= 0) null else RemoteMeta(CoriolisBase.ID, i.ver.toString())
    }

    override fun download(id: String): String {
        val r = http.request("GET", "/gear", emptyMap(), null)
        if (r.code == 204) throw BaseStationException("The base station's gear file went away while syncing. It will be tried again.")
        if (r.code != 200) throw BaseStationException("The base station couldn't send its gear file (${r.code}).")
        val text = try { CoriolisBase.decode(r.body) } catch (e: IOException) {
            throw BaseStationException("The base station's gear file is damaged (${e.message}). Open the Coriolis page on a phone to repair it.")
        }
        downloaded = text
        onDownload(text)
        return text
    }

    override fun create(content: String): RemoteMeta = upload(content)

    override fun update(id: String, content: String): RemoteMeta = upload(content)

    override fun delete(id: String) {}

    private fun upload(content: String): RemoteMeta {
        val i = info ?: readInfo()
        val fit = BaseTrim.fit(JSONObject(content), i.max)
        leftOut = fit.leftOut
        // The copy here may be the base's copy plus library bullets that don't fit
        // there: when what fits equals what the base has, there is nothing to send.
        downloaded?.let { d ->
            val same = runCatching { SyncFile.parse(fit.text).content == SyncFile.parse(d).content }.getOrDefault(false)
            if (same) return RemoteMeta(CoriolisBase.ID, i.ver.toString())
        }
        if (fit.gz.size > i.max - BaseTrim.MARGIN_BYTES) throw BaseStationException(
            "Your gear is too big for the base station even without the bullet library " +
                "(${(fit.gz.size + 1023) / 1024} KB, limit ${i.max / 1024} KB).")
        val (type, body) = CoriolisBase.multipart(fit.gz)
        val r = http.request("POST", "/gear?base=${i.ver}&gz=1", mapOf("Content-Type" to type), body)
        val j = runCatching { JSONObject(r.text()) }.getOrNull()
        if (r.code == 200 && j?.optBoolean("ok", false) == true) {
            val v = j.optLong("ver", i.ver + 1)
            info = i.copy(ver = v, bytes = fit.gz.size.toLong(), gz = true)
            uploaded = true
            return RemoteMeta(CoriolisBase.ID, v.toString())
        }
        throw BaseStationException(when (j?.optString("err") ?: "") {
            "conflict" -> "Another phone saved gear to the base station at the same moment. It will be tried again."
            "too_big" -> "The base station refused the gear file as too big (limit ${i.max / 1024} KB)."
            "flash_full" -> "The base station's storage is full."
            "fs" -> "The base station's storage isn't working. Restart the base station."
            else -> "The base station couldn't save the gear file (${r.code})."
        })
    }
}

/** One sync with the base station: SyncEngine plus the same deletion check as a shared file. */
object CoriolisBaseSync {
    fun run(store: ProfileStore, http: BaseHttp, o: SharedFileSync.Options): SharedFileSync.Outcome {
        val before = SyncContent.of(store)
        val here = before.rifles.size + before.loads.size + before.bullets.size + before.cards.size + before.shots.size
        val limit = o.allowDeletions ?: if (o.automatic) SharedFileSync.autoDeletionLimit(here) else 0
        val gate: (String) -> Unit = { text ->
            val parsed = try { SyncFile.parse(text) } catch (e: IllegalArgumentException) { null }
            if (parsed != null) {
                val r = SharedFileSync.removals(store, parsed)
                if (r.count > limit) throw DeletionsNeedConfirmation(r.count, r.lines)
            }
        }
        val remote = CoriolisBaseFile(http, gate)
        val result = try {
            SyncEngine(o.deviceId).sync(store, remote, o.lastSyncAt)
        } catch (e: DeletionsNeedConfirmation) {
            return SharedFileSync.Outcome.NeedsConfirmation(e.count, e.lines,
                "The base station says ${e.count} record(s) here were deleted on another device:\n" + e.lines.joinToString("\n") { "•  $it" } +
                    "\n\nNothing has been changed yet.")
        }
        if (!result.ok) return SharedFileSync.Outcome.Failed(explain(result.error ?: "The sync didn't finish."))
        val after = SyncContent.of(store)
        val ch = SharedFileSync.changes(before, after)
        return SharedFileSync.Outcome.Done(result, ch, summary(result, ch, remote))
    }

    private fun explain(error: String): String =
        error.replace("The sync file in Google Drive can't be used: ", "The base station's gear file can't be used: ")
            .replace("The sync file kept changing while syncing (another device syncing at the same time).", "The base station's gear kept changing while syncing (another phone saving at the same time).")

    fun summary(r: SyncResult, c: SharedFileSync.Changes, remote: CoriolisBaseFile): String = buildString {
        append("Added ${c.added}, updated ${c.updated}, removed ${c.removed}")
        if (remote.uploaded) append("; sent this device's gear to the base station") else append("; the base station was already up to date")
        append('.')
        if (c.libAdded + c.libUpdated + c.libRemoved > 0)
            append(" Bullet library: ${c.libAdded} added, ${c.libUpdated} updated, ${c.libRemoved} removed.")
        if (remote.uploaded && remote.leftOut > 0) append(" ${remote.leftOut} library bullet(s) didn't fit on the base station and stay on this device.")
        if (r.conflicts > 0) append(" ${r.conflicts} record(s) had been changed on two devices; the newest change was kept.")
        remote.info?.fw?.let { append("\nBase station firmware $it.") }
        r.messages.take(3).forEach { append("\n• ").append(it) }
    }
}
