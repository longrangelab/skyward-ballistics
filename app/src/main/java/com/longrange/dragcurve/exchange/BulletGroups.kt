package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.DragModel
import kotlin.math.roundToLong

object BulletGroups {
    data class Group(val key: String, val members: List<Bullet>) {
        val default: Bullet get() = members[0]
        val others: List<Bullet> get() = members.drop(1)
    }

    private val TAGS = Regex("\\((?:user|imported|copy(?: \\d+)?)\\)", RegexOption.IGNORE_CASE)
    private val MAKER_WORDS = setOf("bullets", "bullet", "inc", "co", "company", "ammunition", "precision", "the")

    fun key(b: Bullet): String? {
        if (b.diameterIn <= 0 || b.weightGr <= 0) return null
        val name = b.name.replace(TAGS, " ")
        val maker = makerToken(b.maker).ifEmpty { makerToken(name) }
        val words = spelling((EmpiricalLink.tokens("${b.maker} $name") - EmpiricalLink.tokens(b.maker) - maker).filterNot { it[0].isDigit() }.toSet())
        if (maker.isEmpty() || words.isEmpty()) return null
        val dia = (b.diameterIn * 1000).roundToLong()
        val wt = (b.weightGr * 2).roundToLong()
        return "$maker|$dia|$wt|" + words.sorted().joinToString(" ")
    }

    private val SHORT = mapOf("t" to listOf("target"), "tgt" to listOf("target"), "h" to listOf("hunting"), "lr" to listOf("long", "range"))

    private val SAME_BULLET_WORDS = setOf("match", "bt", "bthp", "cn", "otm", "tactical", "sp", "eol", "f", "open")

    private fun spelling(words: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        words.forEach { w -> out += SHORT[w] ?: listOf(w) }
        if ("boat" in out && "tail" in out) { out -= "boat"; out -= "tail"; out += "bt" }
        return out
    }

    private fun makerToken(s: String): String =
        s.lowercase().split(Regex("[^a-z]+")).firstOrNull { it.length > 1 && it !in MAKER_WORDS } ?: ""

    private fun mergeShortNames(byKey: LinkedHashMap<String, MutableList<Bullet>>) {
        fun parts(k: String): Pair<String, Set<String>>? {
            if (k.startsWith("id:")) return null
            val i = k.lastIndexOf('|')
            return k.substring(0, i) to k.substring(i + 1).split(' ').filter { it.isNotEmpty() }.toSet()
        }
        val parsed = byKey.keys.mapNotNull { k -> parts(k)?.let { k to it } }
        val bySize = parsed.groupBy { it.second.first }
        fun sameBullet(a: Set<String>, b: Set<String>): Boolean {
            val (small, big) = if (a.size <= b.size) a to b else b to a
            return big.containsAll(small) && SAME_BULLET_WORDS.containsAll(big - small)
        }
        fun candidates(k: String, words: Set<String>, size: String): List<String> =
            bySize[size].orEmpty().filter { (o, p) -> o != k && sameBullet(words, p.second) }.map { it.first }
        val keys = byKey.keys.toList()
        for (k in keys) {
            if (!byKey.containsKey(k)) continue
            val (size, words) = parts(k) ?: continue
            val c = candidates(k, words, size).filter { byKey.containsKey(it) }.singleOrNull() ?: continue
            val (_, cWords) = parts(c) ?: continue
            if (candidates(c, cWords, size).filter { byKey.containsKey(it) }.singleOrNull() != k) continue
            val (keep, drop) = if (keys.indexOf(k) < keys.indexOf(c)) k to c else c to k
            byKey.getValue(keep) += byKey.remove(drop)!!
        }
    }

    fun rank(b: Bullet): Int = when {
        b.source != "library" -> 50
        b.dragCurve.size >= 2 -> 40
        b.bcPoints.size > 1 || b.seasonalBcPoints.isNotEmpty() -> 30
        b.dragModel == DragModel.G7 || b.dragModel == DragModel.CUSTOM -> 20
        else -> 10
    }

    fun dataKind(b: Bullet): String = when {
        b.dragCurve.size >= 2 -> "drag curve"
        b.bcPoints.size > 1 -> "${b.bcPoints.size} ${modelName(b)} BCs"
        b.bcPoints.size == 1 -> "${modelName(b)} BC ${"%.3f".format(java.util.Locale.US, b.bcPoints[0].bc)}"
        else -> "no BC"
    } + if (b.seasonalBcPoints.isNotEmpty()) ", seasonal" else ""

    private fun modelName(b: Bullet) = if (b.dragModel == DragModel.CUSTOM) "G7" else b.dragModel.name

    fun change(old: Bullet, new: Bullet): String {
        val a = dataKind(old); val b = dataKind(new)
        if (a != b && !(old.dragCurve.size >= 2 && new.dragCurve.size >= 2)) return "$a → $b"
        fun bc(x: Bullet) = x.bcPoints.singleOrNull()?.let { "%.3f".format(java.util.Locale.US, it.bc) }
        val bcA = bc(old); val bcB = bc(new)
        return when {
            bcA != null && bcB != null && bcA != bcB -> "$b, ${modelName(new)} BC $bcA → $bcB"
            else -> "$b, updated"
        }
    }

    fun group(bullets: List<Bullet>, preferred: Map<String, String> = emptyMap()): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<Bullet>>()
        for (b in bullets) {
            if (b.replacedBy.isNotEmpty()) continue
            byKey.getOrPut(key(b) ?: "id:${b.id}") { ArrayList() } += b
        }
        mergeShortNames(byKey)
        val order = compareByDescending<Bullet> { rank(it) }.thenBy { it.collection.isNotEmpty() }.thenBy { it.collection }
        return byKey.map { (k, list) ->
            val sorted = list.sortedWith(order)
            val pick = preferred[k]?.let { id -> sorted.firstOrNull { it.id == id } }
            Group(k, if (pick == null) sorted else listOf(pick) + (sorted - pick))
        }
    }
}
