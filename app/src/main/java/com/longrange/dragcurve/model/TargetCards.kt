package com.longrange.dragcurve.model

data class CardTarget(
    val name: String,
    val distanceYd: Double,
    val lookAngleDeg: Double = 0.0,
    val windSpeedMph: Double? = null,
    val windAngleDeg: Double? = null,
    val targetSpeedMph: Double = 0.0,
)

data class TargetCard(
    val id: String,
    val name: String,
    val rifleId: String,
    val loadId: String,
    val targets: List<CardTarget> = emptyList(),
    val updatedAt: Long = 0L,
)

data class ShotRecord(
    val id: String,
    val rifleId: String,
    val count: Int,
    val recordedAt: Long,
    val note: String = "",
    val updatedAt: Long = 0L,
)
