package com.longrange.dragcurve.ui.calc

import com.longrange.dragcurve.model.AngleUnit
import com.longrange.dragcurve.model.Atmosphere
import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.DragModel
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.TwistDirection
import com.longrange.dragcurve.store.JsonCodec
import org.json.JSONArray
import org.json.JSONObject

object DraftCodec {
    private fun JSONObject.optD(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null
    private fun JSONObject.optS(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

    private fun rows(a: List<BcPoint>) = JSONArray().apply { a.forEach { put(JSONObject().put("bc", it.bc).put("v", it.minVelFps)) } }
    private fun rows(a: JSONArray?): List<BcPoint> = if (a == null) emptyList() else
        (0 until a.length()).map { a.getJSONObject(it).let { o -> BcPoint(o.getDouble("bc"), o.getDouble("v")) } }

    private fun atmo(a: Atmosphere) = JSONObject().put("alt", a.altitudeFt).put("p", a.pressureInHg)
        .put("abs", a.pressureIsAbsolute).put("t", a.temperatureF).put("h", a.humidityPct)
    private fun atmo(o: JSONObject) = Atmosphere(o.getDouble("alt"), o.getDouble("p"), o.getBoolean("abs"), o.getDouble("t"), o.getDouble("h"))

    fun rifle(r: Rifle): String = JSONObject().apply {
        put("id", r.id); put("name", r.name); put("twist", r.twistIn); put("dir", r.twistDirection.name)
        put("sh", r.sightHeightIn); put("so", r.sightOffsetIn); r.barrelLengthIn?.let { put("bl", it) }
        put("eu", r.elevationUnit.name); put("ec", r.elevationClick); put("wu", r.windageUnit.name); put("wc", r.windageClick)
        put("ecf", r.elevationCorrection); put("wcf", r.windageCorrection); put("notes", r.notes)
        r.externalRef?.let { put("ext", it) }
        put("cart", r.cartridge); put("xids", JsonCodec.externalIds(r.externalIds))
    }.toString()

    fun rifle(s: String): Rifle = JSONObject(s).let { o ->
        Rifle(id = o.getString("id"), name = o.getString("name"), twistIn = o.getDouble("twist"),
            twistDirection = TwistDirection.valueOf(o.getString("dir")), sightHeightIn = o.getDouble("sh"),
            sightOffsetIn = o.getDouble("so"), barrelLengthIn = o.optD("bl"),
            elevationUnit = AngleUnit.valueOf(o.getString("eu")), elevationClick = o.getDouble("ec"),
            windageUnit = AngleUnit.valueOf(o.getString("wu")), windageClick = o.getDouble("wc"),
            elevationCorrection = o.getDouble("ecf"), windageCorrection = o.getDouble("wcf"),
            notes = o.optString("notes"), externalRef = o.optS("ext"),
            cartridge = o.optString("cart"), externalIds = JsonCodec.externalIds(o.optJSONObject("xids")))
    }

    fun load(l: Load): String = JSONObject().apply {
        put("id", l.id); put("rifle", l.rifleId); put("name", l.name); put("bullet", l.bulletId); put("mv", l.muzzleVelocityFps)
        l.dragModelOverride?.let { put("dm", it.name) }; l.bcPointsOverride?.let { put("rows", rows(it)) }
        l.bcSeason?.let { put("season", it) }
        put("dsf", l.dragScaleFactor); put("pt", l.powderTempF); put("vpd", l.velocityPerDegFps)
        put("zr", l.zeroRangeYd); put("zh", l.zeroHeightIn); put("zo", l.zeroOffsetIn)
        l.zeroAtmosphere?.let { put("za", atmo(it)) }; put("notes", l.notes)
        l.externalRef?.let { put("ext", it) }
        put("xids", JsonCodec.externalIds(l.externalIds))
        l.recipe?.let { put("recipe", JsonCodec.recipe(it)) }
        put("zc", l.zeroConfirmed); put("mvs", l.muzzleVelocitySource)
    }.toString()

    fun load(s: String): Load = JSONObject(s).let { o ->
        Load(id = o.getString("id"), rifleId = o.getString("rifle"), name = o.getString("name"), bulletId = o.getString("bullet"),
            muzzleVelocityFps = o.getDouble("mv"), dragModelOverride = o.optS("dm")?.let { DragModel.valueOf(it) },
            bcPointsOverride = if (o.has("rows")) rows(o.getJSONArray("rows")) else null, bcSeason = o.optS("season"),
            dragScaleFactor = o.getDouble("dsf"), powderTempF = o.getDouble("pt"), velocityPerDegFps = o.getDouble("vpd"),
            zeroRangeYd = o.getDouble("zr"), zeroHeightIn = o.getDouble("zh"), zeroOffsetIn = o.getDouble("zo"),
            zeroAtmosphere = if (o.has("za")) atmo(o.getJSONObject("za")) else null, notes = o.optString("notes"),
            externalRef = o.optS("ext"), externalIds = JsonCodec.externalIds(o.optJSONObject("xids")),
            recipe = JsonCodec.recipe(o.optJSONObject("recipe")), zeroConfirmed = o.optBoolean("zc", true),
            muzzleVelocitySource = o.optString("mvs"))
    }

    fun bullet(b: Bullet): String = JSONObject().apply {
        put("id", b.id); put("maker", b.maker); put("name", b.name); put("d", b.diameterIn); put("w", b.weightGr)
        b.lengthIn?.let { put("len", it) }; b.noseLengthIn?.let { put("nose", it) }; put("dm", b.dragModel.name)
        put("rows", rows(b.bcPoints))
        put("curve", JSONArray().apply { b.dragCurve.forEach { put(JSONArray().put(it.mach).put(it.cd)) } })
        put("seasonal", JSONObject().apply { b.seasonalBcPoints.forEach { (k, v) -> put(k, rows(v)) } })
        put("cal", b.caliber); put("cat", b.category); put("src", b.source); b.libraryOrder?.let { put("order", it) }
        put("notes", b.notes)
        put("coll", b.collection); b.provenance?.let { put("prov", JsonCodec.provenance(it)) }
        put("xids", JsonCodec.externalIds(b.externalIds))
    }.toString()

    fun bullet(s: String): Bullet = JSONObject(s).let { o ->
        val curve = o.getJSONArray("curve").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> CdPoint(p.getDouble(0), p.getDouble(1)) } } }
        val seasonal = o.getJSONObject("seasonal").let { so -> so.keys().asSequence().associateWith { rows(so.getJSONArray(it)) } }
        Bullet(id = o.getString("id"), maker = o.optString("maker"), name = o.getString("name"), diameterIn = o.getDouble("d"),
            weightGr = o.getDouble("w"), lengthIn = o.optD("len"), noseLengthIn = o.optD("nose"),
            dragModel = DragModel.valueOf(o.getString("dm")), bcPoints = rows(o.getJSONArray("rows")), dragCurve = curve,
            seasonalBcPoints = seasonal, caliber = o.optString("cal"), category = o.optString("cat"),
            source = o.optString("src", "user"), libraryOrder = if (o.has("order")) o.getInt("order") else null,
            notes = o.optString("notes"), collection = o.optString("coll"),
            provenance = JsonCodec.provenance(o.optJSONObject("prov")), externalIds = JsonCodec.externalIds(o.optJSONObject("xids")))
    }
}
