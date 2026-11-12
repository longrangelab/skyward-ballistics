package com.longrange.dragcurve.exchange

import org.json.JSONArray
import org.json.JSONObject

object LibraryWriter {
    const val WIDTH = 100

    val KEY_ORDER = listOf(
        "format", "version", "kind", "syncVersion", "deviceId", "exportedAt", "app", "description", "license", "units",
        "rifles", "loads", "bullets", "deleted", "cards", "shots",
        "id", "rifleId", "rifleName", "loadId", "bulletId", "maker", "name", "caliber", "category", "cartridge",
        "diameterIn", "weightGr", "lengthIn", "noseLengthIn",
        "twistIn", "twistDirection", "sightHeightIn", "sightOffsetIn", "barrelLengthIn",
        "elevationUnit", "elevationClick", "windageUnit", "windageClick", "elevationCorrection", "windageCorrection",
        "muzzleVelocityFps",
        "dragModel", "bcPoints", "seasonalBcPoints", "dragCurve",
        "dragModelOverride", "bcPointsOverride", "bcSeason", "dragScaleFactor",
        "muzzleVelocitySource",
        "powderTempF", "velocityPerDegFps", "zeroRangeYd", "zeroHeightIn", "zeroOffsetIn", "zeroConfirmed", "zeroAtmosphere",
        "recipe", "bullet", "powder", "chargeGr", "primer", "brass", "firings", "coalIn", "cbtoIn", "cbtoComparator",
        "shoulderBumpIn", "shoulderComparator",
        "altitudeFt", "pressureInHg", "pressureIsAbsolute", "temperatureF", "humidityPct",
        "targets", "distanceYd", "lookAngleDeg", "windSpeedMph", "windAngleDeg", "targetSpeedMph",
        "source", "collection", "libraryOrder", "provenance", "externalRef", "externalIds",
        "count", "recordedAt", "note",
        "notes", "updatedAt", "deletedAt",
        "bc", "minVelFps", "mach", "cd", "cold", "mild", "hot", "method", "date", "url",
        "length", "weight", "velocity", "distance", "temperature", "pressure",
    )
    private val RANK: Map<String, Int> = KEY_ORDER.withIndex().associate { it.value to it.index }
    private val PROVENANCE_RANK: Map<String, Int> = listOf("source", "method", "date", "license", "url").withIndex().associate { it.value to it.index }

    fun write(o: JSONObject): String = StringBuilder().also { value(it, o, 0, null) }.append('\n').toString()

    private fun keys(o: JSONObject, parent: String?): List<String> {
        val rank = if (parent == "provenance") PROVENANCE_RANK else RANK
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out += it.next()
        out.sortWith(compareBy<String>({ rank[it] ?: Int.MAX_VALUE }, { it }))
        return out
    }

    private fun isPlain(v: Any?) = v !is JSONObject && v !is JSONArray

    private fun isSmall(v: Any?): Boolean {
        if (v !is JSONObject) return false
        val n = v.length()
        if (n == 0 || n > 3) return false
        val it = v.keys()
        while (it.hasNext()) if (!isPlain(v.opt(it.next()))) return false
        return true
    }

    private fun plain(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is String -> quote(v)
        is Number -> JSONObject.numberToString(v)
        is Boolean -> v.toString()
        else -> quote(v.toString())
    }

    private fun inline(v: Any?): String = when (v) {
        is JSONObject -> keys(v, null).joinToString(", ", "{", "}") { quote(it) + ": " + inline(v.opt(it)) }
        is JSONArray -> (0 until v.length()).joinToString(", ", "[", "]") { inline(v.opt(it)) }
        else -> plain(v)
    }

    private fun value(sb: StringBuilder, v: Any?, ind: Int, parent: String?) {
        when (v) {
            is JSONObject -> when {
                v.length() == 0 -> sb.append("{}")
                isSmall(v) -> sb.append(inline(v))
                else -> {
                    sb.append("{\n")
                    val ks = keys(v, parent)
                    ks.forEachIndexed { i, k ->
                        pad(sb, ind + 1); sb.append(quote(k)).append(": ")
                        value(sb, v.opt(k), ind + 1, k)
                        sb.append(if (i < ks.size - 1) ",\n" else "\n")
                    }
                    pad(sb, ind); sb.append('}')
                }
            }
            is JSONArray -> {
                val n = v.length()
                val items = (0 until n).map { v.opt(it) }
                when {
                    n == 0 -> sb.append("[]")
                    items.all { isPlain(it) || isSmall(it) } -> {
                        val texts = items.map { inline(it) }
                        val one = texts.joinToString(", ", "[", "]")
                        if (one.length + ind <= WIDTH) { sb.append(one); return }
                        val lines = ArrayList<String>()
                        var cur = ""
                        for (t in texts) {
                            if (cur.isNotEmpty() && cur.length + t.length + 2 + ind + 1 > WIDTH) { lines += cur; cur = t }
                            else cur = if (cur.isEmpty()) t else "$cur, $t"
                        }
                        lines += cur
                        sb.append("[\n")
                        lines.forEachIndexed { i, ln -> pad(sb, ind + 1); sb.append(ln).append(if (i < lines.size - 1) ",\n" else "\n") }
                        pad(sb, ind); sb.append(']')
                    }
                    else -> {
                        sb.append("[\n")
                        items.forEachIndexed { i, it -> pad(sb, ind + 1); value(sb, it, ind + 1, null); sb.append(if (i < n - 1) ",\n" else "\n") }
                        pad(sb, ind); sb.append(']')
                    }
                }
            }
            else -> sb.append(plain(v))
        }
    }

    private fun pad(sb: StringBuilder, n: Int) { repeat(n) { sb.append(' ') } }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
