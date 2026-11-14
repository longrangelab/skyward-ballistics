package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.store.optSN
import java.security.MessageDigest

object ComponentLibrary {
    const val COLLECTION = EmpiricalLibraryFormat.COLLECTION
    const val URL = EmpiricalLibraryFormat.URL
    const val TITLE = "Empirical Precision component library"
    const val CREDIT = "Component data from Empirical Precision (empiricalprecision.com), published under the Apache License 2.0."

    const val CHECK_EVERY_MS = 7L * 24 * 60 * 60 * 1000

    data class Parsed(val bullets: List<Bullet>, val catalog: EpCatalog, val messages: List<String>) {
        val counts: String get() = counts(bullets.size, catalog)
    }

    fun parse(text: String): Parsed {
        val res = EmpiricalLibraryFormat.importDetailed(text)
        return Parsed(res.libs.bullets, catalog(EpMaster.parse(text), res.libs.bullets), res.messages)
    }

    fun apply(store: ProfileStore, p: Parsed): ImportReport {
        val rep = store.updateLibraryCollection(COLLECTION, p.bullets)
        store.saveComponentCatalog(p.catalog.toJson())
        return rep
    }

    fun remove(store: ProfileStore): Boolean {
        if (!store.removeLibraryCollection(COLLECTION)) return false
        store.saveComponentCatalog(null)
        return true
    }

    fun due(lastCheckAt: Long, now: Long): Boolean = lastCheckAt <= 0 || now - lastCheckAt >= CHECK_EVERY_MS || now < lastCheckAt

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun counts(bullets: Int, c: EpCatalog): String = listOf(
        bullets to "bullets", c.powders.size to "powders", c.primers.size to "primers",
        c.brass.size to "cases", c.cartridges.size to "cartridges",
    ).filter { it.first > 0 }.joinToString(" · ") { "%,d %s".format(java.util.Locale.US, it.first, it.second) }

    internal fun catalog(m: EpMaster, bullets: List<Bullet>): EpCatalog {
        fun maker(id: String?) = id?.let { m.manufacturers[it]?.optSN("name") ?: Ep.MANUFACTURERS[it] } ?: ""
        fun withMaker(maker: String, name: String) = if (maker.isEmpty() || name.startsWith(maker, ignoreCase = true)) name else "$maker $name"
        fun sorted(l: List<EpCatalog.Entry>) = l.filter { it.name.isNotBlank() }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
        return EpCatalog(
            cartridges = sorted(m.cartridges.map { (id, r) -> EpCatalog.Entry(r.optSN("name") ?: EpNames.cartridge(id), id) }),
            bullets = sorted(bullets.mapNotNull { b -> b.externalIds[Ep.KEY]?.let { EpCatalog.Entry(withMaker(b.maker, b.name), it) } }),
            powders = sorted(m.powders.map { (id, r) -> EpCatalog.Entry(withMaker(maker(r.optSN("manufacturerId")), r.optSN("name") ?: id), id) }),
            primers = sorted(m.primers.map { (id, r) -> EpCatalog.Entry(withMaker(maker(r.optSN("manufacturerId")), r.optSN("name") ?: id), id) }),
            brass = sorted(m.brass.map { (id, r) ->
                EpCatalog.Entry(maker(r.optSN("manufacturerId")).ifEmpty { r.optSN("name") ?: EpNames.brass(id) }, id, r.optSN("cartridgeId") ?: "")
            }),
        )
    }
}
