package com.longrange.dragcurve.store

import com.longrange.dragcurve.model.AppSettings
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CardTarget
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Provenance
import com.longrange.dragcurve.model.Recipe
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotInputs
import com.longrange.dragcurve.model.ShotRecord
import com.longrange.dragcurve.model.TargetCard
import com.longrange.dragcurve.model.Tombstone
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

fun stableId(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()

object JsonCodec {
    fun bcPoint(p: BcPoint): JSONObject = JSONObject().put("bc", p.bc).put("minVelFps", p.minVelFps)
    fun bcPoint(o: JSONObject): BcPoint = BcPoint(o.reqD("bc", "BC row"), o.optD("minVelFps", 0.0))

    fun cdPoint(p: CdPoint): JSONObject = JSONObject().put("mach", p.mach).put("cd", p.cd)
    fun cdPoint(o: JSONObject): CdPoint = CdPoint(o.reqD("mach", "drag point"), o.reqD("cd", "drag point"))

    fun bcPoints(l: List<BcPoint>): JSONArray = JSONArray().also { a -> l.forEach { a.put(bcPoint(it)) } }
    fun bcPoints(a: JSONArray?): List<BcPoint> = a.objects().map { bcPoint(it) }

    fun atmosphere(a: Atmosphere): JSONObject = JSONObject()
        .put("altitudeFt", a.altitudeFt)
        .put("pressureInHg", a.pressureInHg)
        .put("pressureIsAbsolute", a.pressureIsAbsolute)
        .put("temperatureF", a.temperatureF)
        .put("humidityPct", a.humidityPct)

    fun atmosphere(o: JSONObject): Atmosphere {
        val d = Atmosphere()
        return Atmosphere(
            altitudeFt = o.optD("altitudeFt", d.altitudeFt),
            pressureInHg = o.optD("pressureInHg", d.pressureInHg),
            pressureIsAbsolute = o.optB("pressureIsAbsolute", d.pressureIsAbsolute),
            temperatureF = o.optD("temperatureF", d.temperatureF),
            humidityPct = o.optD("humidityPct", d.humidityPct),
        )
    }

    fun provenance(p: Provenance): JSONObject = JSONObject().also { o ->
        if (p.source.isNotEmpty()) o.put("source", p.source)
        if (p.method.isNotEmpty()) o.put("method", p.method)
        if (p.date.isNotEmpty()) o.put("date", p.date)
        if (p.license.isNotEmpty()) o.put("license", p.license)
        if (p.url.isNotEmpty()) o.put("url", p.url)
    }

    fun provenance(o: JSONObject?): Provenance? {
        if (o == null) return null
        val p = Provenance(o.optS("source", ""), o.optS("method", ""), o.optS("date", ""), o.optS("license", ""), o.optS("url", ""))
        return if (p.isEmpty) null else p
    }

    fun externalIds(m: Map<String, String>): JSONObject = JSONObject().also { o -> m.toSortedMap().forEach { (k, v) -> o.put(k, v) } }

    fun externalIds(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = java.util.TreeMap<String, String>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); if (!o.isNull(k)) out[k] = o.get(k).toString() }
        return out
    }

    fun recipe(r: Recipe): JSONObject = JSONObject().also { o ->
        if (r.cartridge.isNotEmpty()) o.put("cartridge", r.cartridge)
        if (r.bullet.isNotEmpty()) o.put("bullet", r.bullet)
        if (r.powder.isNotEmpty()) o.put("powder", r.powder)
        o.putOpt2("chargeGr", r.chargeGr)
        if (r.primer.isNotEmpty()) o.put("primer", r.primer)
        if (r.brass.isNotEmpty()) o.put("brass", r.brass)
        o.putOpt2("firings", r.firings)
        o.putOpt2("coalIn", r.coalIn)
        o.putOpt2("cbtoIn", r.cbtoIn)
        if (r.cbtoComparator.isNotEmpty()) o.put("cbtoComparator", r.cbtoComparator)
        o.putOpt2("shoulderBumpIn", r.shoulderBumpIn)
        if (r.shoulderComparator.isNotEmpty()) o.put("shoulderComparator", r.shoulderComparator)
    }

    fun recipe(o: JSONObject?): Recipe? {
        if (o == null) return null
        val r = Recipe(
            cartridge = o.optS("cartridge", ""), bullet = o.optS("bullet", ""), powder = o.optS("powder", ""),
            chargeGr = o.optDN("chargeGr"), primer = o.optS("primer", ""), brass = o.optS("brass", ""),
            firings = o.optIN("firings"), coalIn = o.optDN("coalIn"), cbtoIn = o.optDN("cbtoIn"),
            cbtoComparator = o.optS("cbtoComparator", ""), shoulderBumpIn = o.optDN("shoulderBumpIn"),
            shoulderComparator = o.optS("shoulderComparator", ""),
        )
        return if (r.isEmpty) null else r
    }

    fun bullet(b: Bullet): JSONObject {
        val o = JSONObject()
            .put("id", b.id)
            .put("maker", b.maker)
            .put("name", b.name)
            .put("diameterIn", b.diameterIn)
            .put("weightGr", b.weightGr)
            .putOpt2("lengthIn", b.lengthIn)
            .putOpt2("noseLengthIn", b.noseLengthIn)
            .put("dragModel", b.dragModel.name)
            .put("bcPoints", bcPoints(b.bcPoints))
            .put("dragCurve", JSONArray().also { a -> b.dragCurve.forEach { a.put(cdPoint(it)) } })
        if (b.seasonalBcPoints.isNotEmpty()) {
            val s = JSONObject()
            b.seasonalBcPoints.forEach { (k, v) -> s.put(k, bcPoints(v)) }
            o.put("seasonalBcPoints", s)
        }
        return o.put("caliber", b.caliber)
            .put("category", b.category)
            .put("source", b.source)
            .putOpt2("libraryOrder", b.libraryOrder)
            .putOpt2("collection", b.collection.ifEmpty { null })
            .putOpt2("provenance", b.provenance?.takeIf { !it.isEmpty }?.let { provenance(it) })
            .putOpt2("externalIds", b.externalIds.takeIf { it.isNotEmpty() }?.let { externalIds(it) })
            .putOpt2("replacedBy", b.replacedBy.ifEmpty { null })
            .put("notes", b.notes)
            .putStamp(b.updatedAt)
    }

    fun bullet(o: JSONObject): Bullet {
        val d = Bullet(id = "", name = "", diameterIn = 0.0, weightGr = 0.0)
        val seasonal = LinkedHashMap<String, List<BcPoint>>()
        o.optJSONObject("seasonalBcPoints")?.let { s ->
            val it = s.keys()
            while (it.hasNext()) { val k = it.next(); seasonal[k] = bcPoints(s.optJSONArray(k)) }
        }
        return Bullet(
            id = o.reqS("id", "bullet"),
            maker = o.optS("maker", d.maker),
            name = o.reqS("name", "bullet"),
            diameterIn = o.reqD("diameterIn", "bullet"),
            weightGr = o.reqD("weightGr", "bullet"),
            lengthIn = o.optDN("lengthIn"),
            noseLengthIn = o.optDN("noseLengthIn"),
            dragModel = o.optEnum("dragModel", d.dragModel),
            bcPoints = bcPoints(o.optJSONArray("bcPoints")),
            dragCurve = o.optJSONArray("dragCurve").objects().map { cdPoint(it) },
            seasonalBcPoints = seasonal,
            caliber = o.optS("caliber", d.caliber),
            category = o.optS("category", d.category),
            source = o.optS("source", d.source),
            libraryOrder = o.optIN("libraryOrder"),
            notes = o.optS("notes", d.notes),
            updatedAt = o.optL("updatedAt"),
            collection = o.optS("collection", d.collection),
            provenance = provenance(o.optJSONObject("provenance")),
            externalIds = externalIds(o.optJSONObject("externalIds")),
            replacedBy = o.optS("replacedBy", d.replacedBy),
        )
    }

    fun rifle(r: Rifle): JSONObject = JSONObject()
        .put("id", r.id)
        .put("name", r.name)
        .put("twistIn", r.twistIn)
        .put("twistDirection", r.twistDirection.name)
        .put("sightHeightIn", r.sightHeightIn)
        .put("sightOffsetIn", r.sightOffsetIn)
        .putOpt2("barrelLengthIn", r.barrelLengthIn)
        .put("elevationUnit", r.elevationUnit.name)
        .put("elevationClick", r.elevationClick)
        .put("windageUnit", r.windageUnit.name)
        .put("windageClick", r.windageClick)
        .put("elevationCorrection", r.elevationCorrection)
        .put("windageCorrection", r.windageCorrection)
        .putOpt2("cartridge", r.cartridge.ifEmpty { null })
        .put("notes", r.notes)
        .putOpt2("externalRef", r.externalRef)
        .putOpt2("externalIds", r.externalIds.takeIf { it.isNotEmpty() }?.let { externalIds(it) })
        .putStamp(r.updatedAt)

    fun rifle(o: JSONObject): Rifle {
        val d = Rifle(id = "", name = "")
        return Rifle(
            id = o.reqS("id", "rifle"),
            name = o.reqS("name", "rifle"),
            twistIn = o.optD("twistIn", d.twistIn),
            twistDirection = o.optEnum("twistDirection", d.twistDirection),
            sightHeightIn = o.optD("sightHeightIn", d.sightHeightIn),
            sightOffsetIn = o.optD("sightOffsetIn", d.sightOffsetIn),
            barrelLengthIn = o.optDN("barrelLengthIn"),
            elevationUnit = o.optEnum("elevationUnit", d.elevationUnit),
            elevationClick = o.optD("elevationClick", d.elevationClick),
            windageUnit = o.optEnum("windageUnit", d.windageUnit),
            windageClick = o.optD("windageClick", d.windageClick),
            elevationCorrection = o.optD("elevationCorrection", d.elevationCorrection),
            windageCorrection = o.optD("windageCorrection", d.windageCorrection),
            notes = o.optS("notes", d.notes),
            externalRef = o.optSN("externalRef"),
            updatedAt = o.optL("updatedAt"),
            cartridge = o.optS("cartridge", d.cartridge),
            externalIds = externalIds(o.optJSONObject("externalIds")),
        )
    }

    fun load(l: Load): JSONObject = JSONObject()
        .put("id", l.id)
        .put("rifleId", l.rifleId)
        .put("name", l.name)
        .put("bulletId", l.bulletId)
        .put("muzzleVelocityFps", l.muzzleVelocityFps)
        .putOpt2("dragModelOverride", l.dragModelOverride?.name)
        .putOpt2("bcPointsOverride", l.bcPointsOverride?.let { bcPoints(it) })
        .putOpt2("bcSeason", l.bcSeason)
        .put("dragScaleFactor", l.dragScaleFactor)
        .put("powderTempF", l.powderTempF)
        .put("velocityPerDegFps", l.velocityPerDegFps)
        .put("zeroRangeYd", l.zeroRangeYd)
        .put("zeroHeightIn", l.zeroHeightIn)
        .put("zeroOffsetIn", l.zeroOffsetIn)
        .putOpt2("zeroAtmosphere", l.zeroAtmosphere?.let { atmosphere(it) })
        .put("notes", l.notes)
        .putOpt2("externalRef", l.externalRef)
        .putOpt2("externalIds", l.externalIds.takeIf { it.isNotEmpty() }?.let { externalIds(it) })
        .putOpt2("recipe", l.recipe?.takeIf { !it.isEmpty }?.let { recipe(it) })
        .putOpt2("zeroConfirmed", if (l.zeroConfirmed) null else false)
        .putOpt2("muzzleVelocitySource", l.muzzleVelocitySource.ifEmpty { null })
        .putStamp(l.updatedAt)

    fun load(o: JSONObject): Load {
        val d = Load(id = "", rifleId = "", name = "", bulletId = "", muzzleVelocityFps = 0.0)
        return Load(
            id = o.reqS("id", "load"),
            rifleId = o.reqS("rifleId", "load"),
            name = o.reqS("name", "load"),
            bulletId = o.reqS("bulletId", "load"),
            muzzleVelocityFps = o.reqD("muzzleVelocityFps", "load"),
            dragModelOverride = o.optSN("dragModelOverride")?.let { n -> DragModel.values().firstOrNull { it.name == n } },
            bcSeason = o.optSN("bcSeason"),
            bcPointsOverride = if (o.has("bcPointsOverride") && !o.isNull("bcPointsOverride")) bcPoints(o.optJSONArray("bcPointsOverride")) else null,
            dragScaleFactor = o.optD("dragScaleFactor", d.dragScaleFactor),
            powderTempF = o.optD("powderTempF", d.powderTempF),
            velocityPerDegFps = o.optD("velocityPerDegFps", d.velocityPerDegFps),
            zeroRangeYd = o.optD("zeroRangeYd", d.zeroRangeYd),
            zeroHeightIn = o.optD("zeroHeightIn", d.zeroHeightIn),
            zeroOffsetIn = o.optD("zeroOffsetIn", d.zeroOffsetIn),
            zeroAtmosphere = o.optJSONObject("zeroAtmosphere")?.let { atmosphere(it) },
            notes = o.optS("notes", d.notes),
            externalRef = o.optSN("externalRef"),
            updatedAt = o.optL("updatedAt"),
            externalIds = externalIds(o.optJSONObject("externalIds")),
            recipe = recipe(o.optJSONObject("recipe")),
            zeroConfirmed = o.optB("zeroConfirmed", true),
            muzzleVelocitySource = o.optS("muzzleVelocitySource", ""),
        )
    }

    fun shotInputs(s: ShotInputs): JSONObject = JSONObject()
        .put("distanceYd", s.distanceYd)
        .put("lookAngleDeg", s.lookAngleDeg)
        .put("cantDeg", s.cantDeg)
        .put("targetSpeedMph", s.targetSpeedMph)
        .put("coriolis", s.coriolis)
        .put("latitudeDeg", s.latitudeDeg)
        .put("azimuthDeg", s.azimuthDeg)
        .put("atmosphere", atmosphere(s.atmosphere))
        .put("windSpeed1Mph", s.windSpeed1Mph)
        .put("windSpeed2Mph", s.windSpeed2Mph)
        .put("windAngleDeg", s.windAngleDeg)
        .put("spinDrift", s.spinDrift)
        .put("aeroJump", s.aeroJump)
        .put("spinDriftModel", s.spinDriftModel.name)

    fun shotInputs(o: JSONObject): ShotInputs {
        val d = ShotInputs()
        return ShotInputs(
            distanceYd = o.optD("distanceYd", d.distanceYd),
            lookAngleDeg = o.optD("lookAngleDeg", d.lookAngleDeg),
            cantDeg = o.optD("cantDeg", d.cantDeg),
            targetSpeedMph = o.optD("targetSpeedMph", d.targetSpeedMph),
            coriolis = o.optB("coriolis", d.coriolis),
            latitudeDeg = o.optD("latitudeDeg", d.latitudeDeg),
            azimuthDeg = o.optD("azimuthDeg", d.azimuthDeg),
            atmosphere = o.optJSONObject("atmosphere")?.let { atmosphere(it) } ?: d.atmosphere,
            windSpeed1Mph = o.optD("windSpeed1Mph", d.windSpeed1Mph),
            windSpeed2Mph = o.optD("windSpeed2Mph", d.windSpeed2Mph),
            windAngleDeg = o.optD("windAngleDeg", d.windAngleDeg),
            spinDrift = o.optB("spinDrift", d.spinDrift),
            aeroJump = o.optB("aeroJump", d.aeroJump),
            spinDriftModel = o.optEnum("spinDriftModel", d.spinDriftModel),
        )
    }

    fun settings(s: AppSettings): JSONObject = JSONObject()
        .put("distanceUnit", s.distanceUnit)
        .put("atmosphereUnits", s.atmosphereUnits)
        .put("otherUnits", s.otherUnits)
        .put("outputPrecision", s.outputPrecision)
        .put("usePlusMinus", s.usePlusMinus)
        .put("solutionStepYd", s.solutionStepYd)
        .put("tableStepYd", s.tableStepYd)
        .put("tableMaxYd", s.tableMaxYd)
        .put("tableSortDescending", s.tableSortDescending)
        .put("useDensityAltitude", s.useDensityAltitude)
        .put("saveLastInputs", s.saveLastInputs)
        .put("keepScreenOn", s.keepScreenOn)
        .put("theme", s.theme)

    fun settings(o: JSONObject): AppSettings {
        val d = AppSettings()
        return AppSettings(
            distanceUnit = o.optS("distanceUnit", d.distanceUnit),
            atmosphereUnits = o.optS("atmosphereUnits", d.atmosphereUnits),
            otherUnits = o.optS("otherUnits", d.otherUnits),
            outputPrecision = o.optS("outputPrecision", d.outputPrecision),
            usePlusMinus = o.optB("usePlusMinus", d.usePlusMinus),
            solutionStepYd = o.optD("solutionStepYd", d.solutionStepYd),
            tableStepYd = o.optD("tableStepYd", d.tableStepYd),
            tableMaxYd = o.optD("tableMaxYd", d.tableMaxYd),
            tableSortDescending = o.optB("tableSortDescending", d.tableSortDescending),
            useDensityAltitude = o.optB("useDensityAltitude", d.useDensityAltitude),
            saveLastInputs = o.optB("saveLastInputs", d.saveLastInputs),
            keepScreenOn = o.optB("keepScreenOn", d.keepScreenOn),
            theme = o.optS("theme", d.theme),
        )
    }

    fun rifles(l: List<Rifle>): JSONArray = JSONArray().also { a -> l.forEach { a.put(rifle(it)) } }
    fun loads(l: List<Load>): JSONArray = JSONArray().also { a -> l.forEach { a.put(load(it)) } }
    fun bullets(l: List<Bullet>): JSONArray = JSONArray().also { a -> l.forEach { a.put(bullet(it)) } }
    fun rifles(a: JSONArray?): List<Rifle> = a.objects().map { rifle(it) }
    fun loads(a: JSONArray?): List<Load> = a.objects().map { load(it) }
    fun bullets(a: JSONArray?): List<Bullet> = a.objects().map { bullet(it) }

    fun cardTarget(t: CardTarget): JSONObject = JSONObject()
        .put("name", t.name)
        .put("distanceYd", t.distanceYd)
        .put("lookAngleDeg", t.lookAngleDeg)
        .putOpt2("windSpeedMph", t.windSpeedMph)
        .putOpt2("windAngleDeg", t.windAngleDeg)
        .put("targetSpeedMph", t.targetSpeedMph)

    fun cardTarget(o: JSONObject): CardTarget = CardTarget(
        name = o.optS("name", ""),
        distanceYd = o.reqD("distanceYd", "card target"),
        lookAngleDeg = o.optD("lookAngleDeg", 0.0),
        windSpeedMph = o.optDN("windSpeedMph"),
        windAngleDeg = o.optDN("windAngleDeg"),
        targetSpeedMph = o.optD("targetSpeedMph", 0.0),
    )

    fun card(c: TargetCard): JSONObject = JSONObject()
        .put("id", c.id)
        .put("name", c.name)
        .put("rifleId", c.rifleId)
        .put("loadId", c.loadId)
        .put("targets", JSONArray().also { a -> c.targets.forEach { a.put(cardTarget(it)) } })
        .putStamp(c.updatedAt)

    fun card(o: JSONObject): TargetCard = TargetCard(
        id = o.reqS("id", "target card"),
        name = o.reqS("name", "target card"),
        rifleId = o.reqS("rifleId", "target card"),
        loadId = o.reqS("loadId", "target card"),
        targets = o.optJSONArray("targets").objects().map { cardTarget(it) },
        updatedAt = o.optL("updatedAt"),
    )

    fun cards(l: List<TargetCard>): JSONArray = JSONArray().also { a -> l.forEach { a.put(card(it)) } }
    fun cards(a: JSONArray?): List<TargetCard> = a.objects().map { card(it) }

    fun shot(s: ShotRecord): JSONObject = JSONObject()
        .put("id", s.id)
        .put("rifleId", s.rifleId)
        .put("count", s.count)
        .put("recordedAt", s.recordedAt)
        .also { if (s.note.isNotEmpty()) it.put("note", s.note) }
        .putStamp(s.updatedAt)

    fun shot(o: JSONObject): ShotRecord = ShotRecord(
        id = o.reqS("id", "shot record"),
        rifleId = o.reqS("rifleId", "shot record"),
        count = o.optInt("count", 0),
        recordedAt = o.optL("recordedAt"),
        note = o.optS("note", ""),
        updatedAt = o.optL("updatedAt"),
    )

    fun shots(l: List<ShotRecord>): JSONArray = JSONArray().also { a -> l.forEach { a.put(shot(it)) } }
    fun shots(a: JSONArray?): List<ShotRecord> = a.objects().map { shot(it) }

    fun tombstone(t: Tombstone): JSONObject = JSONObject().put("kind", t.kind).put("id", t.id).put("deletedAt", t.deletedAt)
    fun tombstone(o: JSONObject): Tombstone =
        Tombstone(o.reqS("kind", "deletion"), o.reqS("id", "deletion"), o.optL("deletedAt"))
    fun tombstones(l: List<Tombstone>): JSONArray = JSONArray().also { a -> l.forEach { a.put(tombstone(it)) } }
    fun tombstones(a: JSONArray?): List<Tombstone> = a.objects().map { tombstone(it) }

    fun libraries(l: Libraries): JSONObject = JSONObject()
        .put("rifles", rifles(l.rifles)).put("loads", loads(l.loads)).put("bullets", bullets(l.bullets))
        .also { if (l.deleted.isNotEmpty()) it.put("deleted", tombstones(l.deleted)) }
        .also { if (l.cards.isNotEmpty()) it.put("cards", cards(l.cards)) }
        .also { if (l.shots.isNotEmpty()) it.put("shots", shots(l.shots)) }

    fun libraries(o: JSONObject): Libraries = Libraries(
        rifles = rifles(o.optJSONArray("rifles")),
        loads = loads(o.optJSONArray("loads")),
        bullets = bullets(o.optJSONArray("bullets")),
        deleted = tombstones(o.optJSONArray("deleted")),
        cards = cards(o.optJSONArray("cards")),
        shots = shots(o.optJSONArray("shots")),
    )

    fun parseObject(text: String, what: String = "file"): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw IllegalArgumentException("The $what is not valid JSON (${e.message}).")
    }
}

internal fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) optJSONObject(i)?.let { out.add(it) }
    return out
}

internal fun JSONObject.putOpt2(key: String, v: Any?): JSONObject = if (v == null) this else put(key, v)

internal fun JSONObject.present(k: String) = has(k) && !isNull(k)

internal fun JSONObject.reqS(k: String, what: String): String {
    if (!present(k)) throw IllegalArgumentException("A $what record is missing '$k'.")
    return get(k).toString()
}

internal fun JSONObject.reqD(k: String, what: String): Double {
    if (!present(k)) throw IllegalArgumentException("A $what record is missing '$k'.")
    val v = optDouble(k, Double.NaN)
    if (v.isNaN()) throw IllegalArgumentException("A $what record has a non-numeric '$k'.")
    return v
}

internal fun JSONObject.optS(k: String, def: String): String = if (present(k)) get(k).toString() else def
internal fun JSONObject.optSN(k: String): String? = if (present(k)) get(k).toString() else null
internal fun JSONObject.optD(k: String, def: Double): Double = if (present(k)) optDouble(k, def).let { if (it.isNaN()) def else it } else def
internal fun JSONObject.optDN(k: String): Double? = if (present(k)) optDouble(k, Double.NaN).takeIf { !it.isNaN() } else null
internal fun JSONObject.optIN(k: String): Int? = if (present(k)) optDouble(k, Double.NaN).takeIf { !it.isNaN() }?.toInt() else null
internal fun JSONObject.optB(k: String, def: Boolean): Boolean = if (present(k)) optBoolean(k, def) else def

internal inline fun <reified E : Enum<E>> JSONObject.optEnum(k: String, def: E): E {
    val n = optSN(k) ?: return def
    return enumValues<E>().firstOrNull { it.name == n } ?: enumValues<E>().firstOrNull { it.name.equals(n, true) } ?: def
}

internal fun JSONObject.putStamp(v: Long): JSONObject = if (v == 0L) this else put("updatedAt", v)
internal fun JSONObject.optL(k: String): Long = if (present(k)) optLong(k, 0L) else 0L
