package com.longrange.dragcurve.model

object BarrelCare {
    const val ID_PREFIX = "clean-"

    data class Status(
        val rounds: Int,
        val cleanings: Int,
        val lastCleaned: ShotRecord?,
        val sinceCleaning: Int,
        val every: Int?,
    ) {
        val due: Boolean get() = every != null && sinceCleaning >= every
        val left: Int? get() = every?.let { (it - sinceCleaning).coerceAtLeast(0) }
    }

    fun status(records: List<ShotRecord>, every: Int? = null): Status {
        val cleans = records.filter { it.isCleaning }
        val last = cleans.maxByOrNull { it.recordedAt }
        val rounds = records.filter { !it.isCleaning }
        val since = rounds.filter { last == null || it.recordedAt > last.recordedAt }.sumOf { it.count }
        return Status(rounds.sumOf { it.count }, cleans.size, last, since, every?.takeIf { it > 0 })
    }

    fun roundsBefore(records: List<ShotRecord>, c: ShotRecord): Int {
        val prev = records.filter { it.isCleaning && it.id != c.id && it.recordedAt < c.recordedAt }.maxOfOrNull { it.recordedAt }
        return records.filter { !it.isCleaning && it.recordedAt <= c.recordedAt && (prev == null || it.recordedAt > prev) }.sumOf { it.count }
    }

    fun cleaning(rifleId: String, at: Long, note: String = ""): ShotRecord =
        ShotRecord(id = "", rifleId = rifleId, count = 0, recordedAt = at, note = note.trim(), kind = ShotRecord.CLEANING)

    fun rounds(records: List<ShotRecord>): List<ShotRecord> = records.filter { !it.isCleaning }

    fun cleanings(records: List<ShotRecord>): List<ShotRecord> = records.filter { it.isCleaning }

    fun interval(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..100_000 }
}
