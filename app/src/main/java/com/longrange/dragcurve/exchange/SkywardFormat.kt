package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.store.JsonCodec
import com.longrange.dragcurve.store.objects
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.temporal.ChronoUnit

object SkywardFormat : LibraryFormat, DetailedImport {
    const val FORMAT = "skyward-library"
    val LEGACY_FORMATS = setOf("dragcurve-library", "arcledger-library")
    const val VERSION = 1
    const val APP = "Skyward Ballistics"

    override val id = "dragcurve"
    val ALIASES = setOf("dragcurve", "arcledger", "skyward")
    override val displayName = "Skyward Ballistics library (.json)"
    override val fileExtension = "json"
    override val exportKinds = LibraryKind.values().toSet()
    override val importKinds = LibraryKind.values().toSet()

    override fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String {
        val sel = LibraryFiles.select(kind, libs, resolveBullet)
        val rifleNames = libs.rifles.associate { it.id to it.name }
        val loads = JSONArray()
        sel.loads.forEach { l ->
            val o = JsonCodec.load(l)
            rifleNames[l.rifleId]?.let { o.put("rifleName", it) }
            loads.put(o)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kind", kind.name.lowercase())
            .put("exportedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
            .put("app", APP)
            .put("units", units())
            .put("rifles", JsonCodec.rifles(sel.rifles))
            .put("loads", loads)
            .put("bullets", JsonCodec.bullets(sel.bullets))
            .also { if (kind == LibraryKind.MASTER) it.put("deleted", JsonCodec.tombstones(sel.deleted)).put("cards", JsonCodec.cards(sel.cards)).put("shots", JsonCodec.shots(sel.shots)) }
            .let { LibraryWriter.write(it) }
    }

    fun units(): JSONObject = JSONObject()
        .put("length", "in").put("weight", "gr").put("velocity", "ft/s").put("distance", "yd")
        .put("temperature", "degF").put("pressure", "inHg")

    override fun import(text: String): Libraries = importDetailed(text).libs

    override fun importDetailed(text: String): ImportResult {
        val o = JsonCodec.parseObject(text)
        val format = o.optString("format", "")
        if (format != FORMAT && format !in LEGACY_FORMATS) {
            throw IllegalArgumentException(
                when {
                    format == "dragcurve-bullets" -> "This is an old bullet-list file, not an exported Skyward Ballistics library."
                    format == "epv5-user-data" -> "This is an Empirical Precision backup. Import it with the Empirical Precision backup format."
                    format.isEmpty() -> "This is not a Skyward Ballistics library file (it has no \"format\" tag)."
                    else -> "This is not a Skyward Ballistics library file (format \"$format\")."
                }
            )
        }
        val version = o.optInt("version", -1)
        if (version != VERSION) throw IllegalArgumentException("Skyward Ballistics library version $version is not one this app reads (expected $VERSION).")
        val kind = o.optString("kind", "")
        if (LibraryKind.values().none { it.name.equals(kind, ignoreCase = true) }) {
            throw IllegalArgumentException("Unknown library kind \"$kind\" (expected rifles, loads, bullets or master).")
        }
        val libs = try {
            JsonCodec.libraries(o).let { if (kind.equals("master", ignoreCase = true)) it else it.copy(deleted = emptyList(), cards = emptyList(), shots = emptyList()) }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("The file has a bad record: ${e.message}")
        }
        val hints = LinkedHashMap<String, String>()
        o.optJSONArray("loads").objects().forEach { lo ->
            val n = lo.optString("rifleName", "")
            if (n.isNotEmpty()) hints[lo.optString("id")] = n
        }
        return ImportResult(libs, hints, kind = LibraryKind.values().first { it.name.equals(kind, ignoreCase = true) })
    }
}

@Deprecated("Renamed to SkywardFormat", ReplaceWith("SkywardFormat"))
val DragCurveFormat: SkywardFormat get() = SkywardFormat

@Deprecated("Renamed to SkywardFormat", ReplaceWith("SkywardFormat"))
val ArcLedgerFormat: SkywardFormat get() = SkywardFormat
