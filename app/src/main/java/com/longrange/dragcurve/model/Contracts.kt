package com.longrange.dragcurve.model

enum class LibraryKind { RIFLES, LOADS, BULLETS, MASTER }

data class Libraries(
    val rifles: List<Rifle> = emptyList(),
    val loads: List<Load> = emptyList(),
    val bullets: List<Bullet> = emptyList(),
    val deleted: List<Tombstone> = emptyList(),
    val cards: List<TargetCard> = emptyList(),
    val shots: List<ShotRecord> = emptyList(),
)

data class Tombstone(val kind: String, val id: String, val deletedAt: Long)

data class AppSettings(
    val distanceUnit: String = "yd",
    val atmosphereUnits: String = "imperial",
    val otherUnits: String = "imperial",
    val outputPrecision: String = "tenth",
    val usePlusMinus: Boolean = false,
    val solutionStepYd: Double = 5.0,
    val tableStepYd: Double = 50.0,
    val tableMaxYd: Double = 2000.0,
    val tableSortDescending: Boolean = false,
    val useDensityAltitude: Boolean = false,
    val saveLastInputs: Boolean = true,
    val keepScreenOn: Boolean = false,
    val theme: String = "system",
)

data class ProfileSource(
    val id: String,
    val uri: String,
    val name: String,
    val lastReadAt: Long = 0,
    val lastError: String = "",
    val receivedAt: Long = 0,
)

interface ProfileStore {
    fun rifles(): List<Rifle>
    fun loads(rifleId: String? = null): List<Load>
    fun userBullets(): List<Bullet>
    fun libraryBullets(): List<Bullet>
    fun bullet(id: String): Bullet?

    fun saveRifle(r: Rifle)
    fun saveLoad(l: Load)
    fun saveBullet(b: Bullet)
    fun deleteRifle(id: String)
    fun deleteLoad(id: String)
    fun deleteBullet(id: String): Boolean

    fun settings(): AppSettings
    fun saveSettings(s: AppSettings)
    fun lastShot(): ShotInputs
    fun saveLastShot(s: ShotInputs)
    fun selection(): Pair<String?, String?>
    fun saveSelection(rifleId: String?, loadId: String?)

    fun snapshot(): Libraries
    fun merge(incoming: Libraries): ImportReport

    fun saveUserBullet(b: Bullet): Bullet { saveBullet(b); return b }
    fun importBulletLibrary(libs: Libraries): ImportReport { throw UnsupportedOperationException("importBulletLibrary") }
    fun clearBulletLibrary(): Boolean = false
    fun removeLibraryCollection(collection: String): Boolean = false
    fun applyLibraryTombstones(deleted: List<Tombstone>): Int = 0
    fun replaceAll(libs: Libraries) { throw UnsupportedOperationException("replaceAll") }
    fun empiricalLink(): String? = null
    fun saveEmpiricalLink(uri: String?) {}
    fun profileSources(): List<ProfileSource> = emptyList()
    fun saveProfileSources(sources: List<ProfileSource>) {}
    fun driveBackupState(): String? = null
    fun saveDriveBackupState(json: String?) {}
    fun empiricalCatalog(): String? = null
    fun saveEmpiricalCatalog(json: String?) {}
    fun empiricalLastHash(): String? = null
    fun saveEmpiricalLastHash(h: String?) {}

    fun targetCards(rifleId: String? = null, loadId: String? = null): List<TargetCard> = emptyList()
    fun saveTargetCard(c: TargetCard): TargetCard = c
    fun deleteTargetCard(id: String) {}

    fun shotRecords(rifleId: String? = null): List<ShotRecord> = emptyList()
    fun saveShotRecord(s: ShotRecord): ShotRecord = s
    fun deleteShotRecord(id: String) {}

    fun restoreDeleted(libs: Libraries) {
        libs.bullets.filter { it.source != "library" }.forEach { saveBullet(it) }
        libs.rifles.forEach { saveRifle(it) }
        libs.loads.forEach { saveLoad(it) }
        libs.cards.forEach { saveTargetCard(it) }
        libs.shots.forEach { saveShotRecord(it) }
    }

    fun safetySnapshot(reason: String) {}
}

data class ImportReport(
    val added: Int, val updated: Int, val renamed: Int, val skipped: Int, val messages: List<String> = emptyList(),
    val deleted: Int = 0,
    val summary: String = "",
)

interface LibraryFormat {
    val id: String
    val displayName: String
    val fileExtension: String
    val mimeType: String get() = "application/json"
    val exportKinds: Set<LibraryKind>
    val importKinds: Set<LibraryKind>
    fun export(kind: LibraryKind, libs: Libraries, resolveBullet: (String) -> Bullet?): String
    fun import(text: String): Libraries
}
