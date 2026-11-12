package com.longrange.dragcurve.exchange

import org.json.JSONArray
import org.json.JSONObject

object EpNames {
    private val MAKERS = mapOf(
        "ACCU" to "Accurate", "ALLI" to "Alliant", "HODG" to "Hodgdon", "IMPR" to "IMR", "IMR" to "IMR", "NORM" to "Norma",
        "RAMS" to "Ramshot", "RELO" to "Reload Swiss", "SHOO" to "Shooters World", "VIHT" to "Vihtavuori", "WINC" to "Winchester",
        "WIN" to "Winchester", "CCI" to "CCI", "FED" to "Federal", "REM" to "Remington", "WRE" to "White River Energetics",
    )

    private fun word(t: String) = if (t.length > 3 && t.all { it.isLetter() }) t.lowercase().replaceFirstChar { it.uppercase() } else t

    fun powderOrPrimer(id: String): String {
        val t = id.substringAfter('_').split('_').filter { it.isNotEmpty() }
        if (t.isEmpty()) return id
        val maker = MAKERS[t[0]]
        val rest = (if (maker != null) t.drop(1) else t).let { if (it.size > 1) it.dropLast(1) else it }.map { word(it) }
        val name = rest.filterIndexed { i, w -> !(i == 0 && maker != null && w.equals(maker, ignoreCase = true)) }.joinToString(" ")
        return listOfNotNull(maker, name.ifEmpty { null }).joinToString(" ").ifEmpty { id }
    }

    fun brass(id: String): String {
        val man = Regex("MAN_[A-Z0-9_]+?_[A-Za-z0-9]{3,4}(?=_CTG_|$)").find(id)?.value
        return man?.let { Ep.MANUFACTURERS[it] }
            ?: id.removePrefix("BRS_").removePrefix("MAN_").substringBefore("_CTG_").split('_').firstOrNull()?.let { word(it) } ?: id
    }

    private val SHORT = mapOf(
        "rem" to "remington", "win" to "winchester", "mag" to "magnum", "wby" to "weatherby", "spr" to "springfield",
        "sprg" to "springfield", "creed" to "creedmoor", "cm" to "creedmoor", "norm" to "norma", "lap" to "lapua",
        "ai" to "ackleyimproved", "wsm" to "winchestershortmagnum", "nato" to "nato",
    )

    fun cartridgeKey(s: String): String =
        s.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            .joinToString("") { t -> SHORT[t.trim('.')] ?: t }.filter { it.isLetterOrDigit() }

    private val CARTRIDGE_BY_KEY: Map<String, List<String>> by lazy {
        EpCartridgeNames.BY_ID.entries.groupBy({ cartridgeKey(it.value) }, { it.key })
    }

    fun cartridgeIdFor(text: String): String? {
        val k = cartridgeKey(text)
        if (k.length < 3) return null
        return CARTRIDGE_BY_KEY[k]?.singleOrNull()
    }

    fun cartridge(id: String): String =
        EpCartridgeNames.BY_ID[id] ?: id.removePrefix("CTG_").substringBeforeLast('_').replace('_', ' ')
}

data class EpCatalog(
    val cartridges: List<Entry> = emptyList(),
    val bullets: List<Entry> = emptyList(),
    val powders: List<Entry> = emptyList(),
    val primers: List<Entry> = emptyList(),
    val brass: List<Entry> = emptyList(),
) {
    data class Entry(val name: String, val id: String)

    fun toJson(): String {
        fun arr(l: List<Entry>) = JSONArray().also { a -> l.forEach { a.put(JSONObject().put("name", it.name).put("id", it.id)) } }
        return JSONObject().put("cartridges", arr(cartridges)).put("bullets", arr(bullets)).put("powders", arr(powders))
            .put("primers", arr(primers)).put("brass", arr(brass)).toString()
    }

    fun plus(other: EpCatalog): EpCatalog {
        fun m(a: List<Entry>, b: List<Entry>) = (a + b).distinctBy { it.id }.sortedBy { it.name.lowercase() }
        return EpCatalog(m(cartridges, other.cartridges), m(bullets, other.bullets), m(powders, other.powders), m(primers, other.primers), m(brass, other.brass))
    }

    companion object {
        fun fromJson(text: String?): EpCatalog {
            if (text.isNullOrBlank()) return EpCatalog()
            return try {
                val o = JSONObject(text)
                fun list(k: String): List<Entry> {
                    val a = o.optJSONArray(k) ?: return emptyList()
                    return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Entry(it.optString("name"), it.optString("id")) } }
                        .filter { it.name.isNotBlank() && it.id.isNotBlank() }
                }
                EpCatalog(list("cartridges"), list("bullets"), list("powders"), list("primers"), list("brass"))
            } catch (e: Exception) { EpCatalog() }
        }
    }
}
