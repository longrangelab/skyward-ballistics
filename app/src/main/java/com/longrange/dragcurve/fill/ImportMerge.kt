package com.longrange.dragcurve.fill

import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle

object ImportMerge {
    const val PREFIX = "shooter:"

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
                val l = lBase.copy(
                    rifleId = rifleId, name = a.name, bulletId = a.libraryBulletId, muzzleVelocityFps = a.muzzleVelocityFps,
                    dragModelOverride = null, bcPointsOverride = null, dragScaleFactor = a.dragScaleFactor, bcSeason = null,
                    zeroRangeYd = a.zeroRangeYd, zeroHeightIn = a.zeroHeightIn, zeroOffsetIn = a.zeroOffsetIn,
                    externalRef = ref, updatedAt = now,
                )
                loads += l
                if (ex == null) newL++ else if (ex.copy(updatedAt = 0) != l.copy(updatedAt = 0)) updL++
                continue
            }
            val exBullet = ex?.let { l -> existing.bullets.firstOrNull { it.id == l.bulletId && it.source == "user" } }
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
                updatedAt = now,
            )
            bullets += b
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
                bulletId = b.id,
                muzzleVelocityFps = a.muzzleVelocityFps,
                dragModelOverride = null,
                bcPointsOverride = null,
                dragScaleFactor = a.dragScaleFactor,
                bcSeason = null,
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
            val bulletChanged = exBullet == null || exBullet.copy(updatedAt = 0) != b.copy(updatedAt = 0)
            if (ex == null) newL++ else if (bulletChanged || ex.copy(updatedAt = 0) != l.copy(updatedAt = 0)) updL++
        }
        return Plan(Libraries(rifles = rifles, loads = loads, bullets = bullets), newR, updR, newL, updL, skipped)
    }
}
