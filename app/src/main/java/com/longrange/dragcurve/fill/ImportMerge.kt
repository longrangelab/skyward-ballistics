package com.longrange.dragcurve.fill

import com.longrange.dragcurve.exchange.BulletGroups
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import kotlin.math.abs

object ImportMerge {
    const val PREFIX = "shooter:"
    const val BULLET_KEY = "import"

    fun rifleRef(firearm: String, prefix: String = PREFIX) = prefix + firearm.trim()
    fun loadRef(firearm: String, ammo: String, prefix: String = PREFIX) = prefix + firearm.trim() + "/" + ammo.trim()

    fun ammoKey(a: AppAmmo) = loadRef(a.firearm, a.name)

    data class Plan(
        val libs: Libraries,
        val newRifles: Int,
        val updatedRifles: Int,
        val newLoads: Int,
        val updatedLoads: Int,
        val skipped: List<String>,
    )

    fun existsFirearm(existing: Libraries, f: AppFirearm, prefix: String = PREFIX) = existing.rifles.any { it.externalRef == rifleRef(f.name, prefix) }
    fun existsAmmo(existing: Libraries, a: AppAmmo, prefix: String = PREFIX) = existing.loads.any { it.externalRef == loadRef(a.firearm, a.name, prefix) }

    private fun usualClick(u: com.longrange.dragcurve.model.AngleUnit) = if (u == com.longrange.dragcurve.model.AngleUnit.MIL) 0.1 else 0.25

    fun build(
        data: AppImport,
        firearms: Set<String>,
        ammo: Set<String>,
        existing: Libraries,
        now: Long,
        newId: (String) -> String,
        library: List<Bullet> = emptyList(),
    ): Plan {
        val rifles = ArrayList<Rifle>()
        val loads = ArrayList<Load>()
        val bullets = ArrayList<Bullet>()
        val skipped = ArrayList<String>()
        var newR = 0; var updR = 0; var newL = 0; var updL = 0
        val rifleIdByName = HashMap<String, String>()

        for (f in data.firearms) {
            val ref = rifleRef(f.name, data.refPrefix)
            val ex = existing.rifles.firstOrNull { it.externalRef == ref }
            if (f.name !in firearms) {
                ex?.let { rifleIdByName[f.name] = it.id }
                continue
            }
            val base = ex ?: Rifle(id = newId("rifle"), name = f.name)
            val eu = if (f.scopeRead) f.elevationUnit else base.elevationUnit
            val wu = if (f.scopeRead) f.windageUnit else base.windageUnit
            val r = base.copy(
                name = f.name,
                twistIn = f.twistIn ?: base.twistIn,
                twistDirection = f.twistDirection,
                sightHeightIn = f.sightHeightIn ?: base.sightHeightIn,
                sightOffsetIn = if (f.scopeRead) f.sightOffsetIn else base.sightOffsetIn,
                elevationUnit = eu,
                elevationClick = if (f.clicksRead) f.elevationClick else if (eu == base.elevationUnit) base.elevationClick else usualClick(eu),
                windageUnit = wu,
                windageClick = if (f.clicksRead) f.windageClick else if (wu == base.windageUnit) base.windageClick else usualClick(wu),
                elevationCorrection = if (f.scopeRead) f.elevationCorrection else base.elevationCorrection,
                windageCorrection = if (f.scopeRead) f.windageCorrection else base.windageCorrection,
                externalRef = ref,
                updatedAt = now,
            )
            rifles += r
            rifleIdByName[f.name] = r.id
            if (ex == null) newR++ else if (ex.copy(updatedAt = 0) != r.copy(updatedAt = 0)) updR++
        }

        for (a in data.ammo) {
            if (ammoKey(a) !in ammo) continue
            if (a.problem != null) { skipped += "${a.firearm} / ${a.name}: ${a.problem}"; continue }
            val dm = a.dragModel ?: continue
            val rifleId = rifleIdByName[a.firearm]
            if (rifleId == null) {
                skipped += "${a.firearm} / ${a.name}: its firearm isn't selected for import and isn't in Skyward yet."
                continue
            }
            val ref = loadRef(a.firearm, a.name, data.refPrefix)
            val ex = existing.loads.firstOrNull { it.externalRef == ref }
            if (a.libraryBulletId != null) {
                val lBase = ex ?: Load(id = newId("load"), rifleId = rifleId, name = a.name, bulletId = a.libraryBulletId, muzzleVelocityFps = a.muzzleVelocityFps)
                val libBullet = library.firstOrNull { it.id == a.libraryBulletId }
                val picked = ex?.bulletId?.takeIf { it != a.libraryBulletId }?.let { id -> (existing.bullets + library).firstOrNull { it.id == id } }
                val bulletId = if (picked != null && libBullet != null && BulletGroups.group(listOf(libBullet, picked)).size == 1) picked.id else a.libraryBulletId
                val keepDrag = ex != null && ex.bulletId == bulletId
                val l = lBase.copy(
                    rifleId = rifleId, name = a.name, bulletId = bulletId, muzzleVelocityFps = a.muzzleVelocityFps,
                    dragModelOverride = if (keepDrag) lBase.dragModelOverride else null,
                    bcPointsOverride = if (keepDrag) lBase.bcPointsOverride else null,
                    bcSeason = if (keepDrag) lBase.bcSeason else null,
                    dragScaleFactor = a.dragScaleFactor,
                    zeroRangeYd = a.zeroRangeYd, zeroHeightIn = a.zeroHeightIn, zeroOffsetIn = a.zeroOffsetIn,
                    externalRef = ref, updatedAt = now,
                )
                loads += l
                if (ex == null) newL++ else if (ex.copy(updatedAt = 0) != l.copy(updatedAt = 0)) updL++
                continue
            }
            val exBullet = existing.bullets.firstOrNull { it.source == "user" && it.externalIds[BULLET_KEY] == ref }
                ?: untaggedBullet(existing, ex, a)
            val bBase = exBullet ?: Bullet(id = newId("bullet"), name = a.name, diameterIn = a.diameterIn, weightGr = a.weightGr)
            val b = bBase.copy(
                name = a.name,
                diameterIn = a.diameterIn,
                weightGr = a.weightGr,
                lengthIn = a.lengthIn,
                noseLengthIn = a.noseLengthIn,
                dragModel = dm,
                bcPoints = a.bcPoints,
                dragCurve = emptyList(),
                seasonalBcPoints = emptyMap(),
                source = "user",
                libraryOrder = null,
                externalIds = bBase.externalIds + (BULLET_KEY to ref),
                updatedAt = now,
            )
            bullets += b
            val picked = ex?.bulletId?.takeIf { it != b.id }?.let { id -> (existing.bullets + library).firstOrNull { it.id == id } }
            val keepPicked = picked != null && (BulletGroups.group(listOf(b, picked)).size == 1 ||
                (exBullet != null && sameImportData(exBullet, b)))
            val bulletId = if (keepPicked) picked!!.id else b.id
            val notes = buildString {
                append(a.notes)
                if (!a.atmosphereStandard.equals("ICAO", true) && a.atmosphereStandard.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("BCs are referenced to the ${a.atmosphereStandard} standard atmosphere (Skyward uses ICAO).")
                }
            }
            val lBase = ex ?: Load(id = newId("load"), rifleId = rifleId, name = a.name, bulletId = b.id, muzzleVelocityFps = a.muzzleVelocityFps)
            val l = lBase.copy(
                rifleId = rifleId,
                name = a.name,
                bulletId = bulletId,
                muzzleVelocityFps = a.muzzleVelocityFps,
                dragModelOverride = if (keepPicked) lBase.dragModelOverride else null,
                bcPointsOverride = if (keepPicked) lBase.bcPointsOverride else null,
                dragScaleFactor = a.dragScaleFactor,
                bcSeason = if (keepPicked) lBase.bcSeason else null,
                powderTempF = a.powderTempF ?: lBase.powderTempF,
                velocityPerDegFps = a.velocityPerDegFps,
                zeroRangeYd = a.zeroRangeYd,
                zeroHeightIn = a.zeroHeightIn,
                zeroOffsetIn = a.zeroOffsetIn,
                zeroAtmosphere = a.zeroAtmosphere,
                notes = notes,
                externalRef = ref,
                updatedAt = now,
            )
            loads += l
            val bulletChanged = exBullet == null || !sameImportData(exBullet, b)
            if (ex == null) newL++ else if (bulletChanged || ex.copy(updatedAt = 0) != l.copy(updatedAt = 0)) updL++
        }
        return Plan(Libraries(rifles = rifles, loads = loads, bullets = bullets), newR, updR, newL, updL, skipped)
    }

    private fun sameImportData(a: Bullet, b: Bullet) =
        a.copy(updatedAt = 0, externalIds = emptyMap()) == b.copy(updatedAt = 0, externalIds = emptyMap())

    private fun untaggedBullet(existing: Libraries, ex: Load?, a: AppAmmo): Bullet? {
        fun fits(b: Bullet) = b.source == "user" && BULLET_KEY !in b.externalIds && b.name == a.name
        ex?.let { l -> existing.bullets.firstOrNull { it.id == l.bulletId && fits(it) } }?.let { return it }
        val used = existing.loads.mapTo(HashSet()) { it.bulletId }
        return existing.bullets.firstOrNull { fits(it) && it.id !in used &&
            abs(it.diameterIn - a.diameterIn) < 0.0005 && abs(it.weightGr - a.weightGr) < 0.25 }
    }
}
