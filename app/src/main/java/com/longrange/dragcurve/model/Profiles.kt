package com.longrange.dragcurve.model

enum class DragModel { G1, G7, CUSTOM }

enum class TwistDirection { RIGHT, LEFT }

enum class SpinDriftModel {
    MEASURED, LITZ;
}

enum class AngleUnit { MIL, MOA, IPHY }

data class BcPoint(val bc: Double, val minVelFps: Double)

data class CdPoint(val mach: Double, val cd: Double)

data class Provenance(
    val source: String = "",
    val method: String = "",
    val date: String = "",
    val license: String = "",
    val url: String = "",
) {
    val isEmpty: Boolean get() = source.isEmpty() && method.isEmpty() && date.isEmpty() && license.isEmpty() && url.isEmpty()
}

data class Bullet(
    val id: String,
    val maker: String = "",
    val name: String,
    val diameterIn: Double,
    val weightGr: Double,
    val lengthIn: Double? = null,
    val noseLengthIn: Double? = null,
    val dragModel: DragModel = DragModel.G7,
    val bcPoints: List<BcPoint> = emptyList(),
    val dragCurve: List<CdPoint> = emptyList(),
    val seasonalBcPoints: Map<String, List<BcPoint>> = emptyMap(),
    val caliber: String = "",
    val category: String = "",
    val source: String = "user",
    val libraryOrder: Int? = null,
    val notes: String = "",
    val updatedAt: Long = 0,
    val collection: String = "",
    val provenance: Provenance? = null,
    val externalIds: Map<String, String> = emptyMap(),
)

data class Rifle(
    val id: String,
    val name: String,
    val twistIn: Double = 8.0,
    val twistDirection: TwistDirection = TwistDirection.RIGHT,
    val sightHeightIn: Double = 1.5,
    val sightOffsetIn: Double = 0.0,
    val barrelLengthIn: Double? = null,
    val elevationUnit: AngleUnit = AngleUnit.MIL,
    val elevationClick: Double = 0.1,
    val windageUnit: AngleUnit = AngleUnit.MIL,
    val windageClick: Double = 0.1,
    val elevationCorrection: Double = 1.0,
    val windageCorrection: Double = 1.0,
    val notes: String = "",
    val externalRef: String? = null,
    val updatedAt: Long = 0,
    val cartridge: String = "",
    val externalIds: Map<String, String> = emptyMap(),
)

data class Atmosphere(
    val altitudeFt: Double = 0.0,
    val pressureInHg: Double = 29.92,
    val pressureIsAbsolute: Boolean = true,
    val temperatureF: Double = 59.0,
    val humidityPct: Double = 0.0,
)

data class Recipe(
    val cartridge: String = "",
    val bullet: String = "",
    val powder: String = "",
    val chargeGr: Double? = null,
    val primer: String = "",
    val brass: String = "",
    val firings: Int? = null,
    val coalIn: Double? = null,
    val cbtoIn: Double? = null,
    val cbtoComparator: String = "",
    val shoulderBumpIn: Double? = null,
    val shoulderComparator: String = "",
) {
    val isEmpty: Boolean get() = this == Recipe()
}

data class Load(
    val id: String,
    val rifleId: String,
    val name: String,
    val bulletId: String,
    val muzzleVelocityFps: Double,
    val dragModelOverride: DragModel? = null,
    val bcPointsOverride: List<BcPoint>? = null,
    val dragScaleFactor: Double = 1.0,
    val bcSeason: String? = null,
    val powderTempF: Double = 59.0,
    val velocityPerDegFps: Double = 0.0,
    val zeroRangeYd: Double = 100.0,
    val zeroHeightIn: Double = 0.0,
    val zeroOffsetIn: Double = 0.0,
    val zeroAtmosphere: Atmosphere? = null,
    val notes: String = "",
    val externalRef: String? = null,
    val updatedAt: Long = 0,
    val externalIds: Map<String, String> = emptyMap(),
    val recipe: Recipe? = null,
    val zeroConfirmed: Boolean = true,
    val muzzleVelocitySource: String = "",
)

data class ShotInputs(
    val distanceYd: Double = 500.0,
    val lookAngleDeg: Double = 0.0,
    val cantDeg: Double = 0.0,
    val targetSpeedMph: Double = 0.0,
    val coriolis: Boolean = false,
    val latitudeDeg: Double = 0.0,
    val azimuthDeg: Double = 0.0,
    val atmosphere: Atmosphere = Atmosphere(),
    val windSpeed1Mph: Double = 0.0,
    val windSpeed2Mph: Double = 0.0,
    val windAngleDeg: Double = 90.0,
    val spinDrift: Boolean = true,
    val aeroJump: Boolean = true,
    val spinDriftModel: SpinDriftModel = SpinDriftModel.MEASURED,
    val localGravity: Boolean = false,
)
