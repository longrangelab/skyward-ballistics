package com.longrange.dragcurve.sync

import com.longrange.dragcurve.exchange.SkywardFormat
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.LibraryKind
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.model.ShotRecord
import com.longrange.dragcurve.model.TargetCard
import com.longrange.dragcurve.model.Tombstone
import com.longrange.dragcurve.store.JsonCodec
import org.json.JSONObject

object SyncFile {
    const val FILE_NAME = "skyward-sync.json"
    const val SYNC_VERSION = 1
    const val MIME = "application/json"

    data class Parsed(
        val libs: Libraries,
        val library: List<Bullet>,
        val rifleNameHints: Map<String, String>,
        val syncVersion: Int,
        val deviceId: String?,
    ) {
        val content: SyncContent get() = SyncContent.of(libs, library)
    }

    fun build(store: ProfileStore, deviceId: String): String {
        val o = JSONObject(com.longrange.dragcurve.exchange.Parts.masterText(store))
        o.put("syncVersion", SYNC_VERSION)
        o.put("deviceId", deviceId)
        return com.longrange.dragcurve.exchange.LibraryWriter.write(o)
    }

    fun parse(text: String): Parsed {
        val o = JsonCodec.parseObject(text, "sync file")
        val sv = if (o.has("syncVersion")) o.optInt("syncVersion", -1) else SYNC_VERSION
        if (sv > SYNC_VERSION) throw NewerSyncFormatException(sv)
        if (sv < 1) throw IllegalArgumentException("The sync file has a bad syncVersion.")
        val res = SkywardFormat.importDetailed(text)
        if (res.kind != LibraryKind.MASTER) throw IllegalArgumentException("The sync file is a \"${res.kind?.name?.lowercase()}\" library, not a master library.")
        val (library, own) = res.libs.bullets.partition { it.source == "library" }
        return Parsed(
            libs = res.libs.copy(bullets = own),
            library = library,
            rifleNameHints = res.rifleNameHints,
            syncVersion = sv,
            deviceId = if (o.has("deviceId") && !o.isNull("deviceId")) o.optString("deviceId") else null,
        )
    }
}

class NewerSyncFormatException(val version: Int) :
    IllegalArgumentException("The Google Drive sync file was written by a newer app (sync version $version). Update Skyward Ballistics; nothing was changed.")

data class SyncContent(
    val rifles: Map<String, Rifle>,
    val loads: Map<String, Load>,
    val bullets: Map<String, Bullet>,
    val library: Map<String, Bullet>,
    val cards: Map<String, TargetCard>,
    val deleted: Set<Tombstone>,
    val shots: Map<String, ShotRecord> = emptyMap(),
) {
    fun changesFrom(other: SyncContent): Int {
        var n = 0
        fun <T> keys(a: Map<String, T>, b: Map<String, T>): Set<String> = (a.keys + b.keys).filter { a[it] != b[it] }.toSet()
        val diff = mapOf(
            "rifle" to keys(rifles, other.rifles), "load" to keys(loads, other.loads),
            "bullet" to keys(bullets, other.bullets), "library" to keys(library, other.library),
            "card" to keys(cards, other.cards), "shot" to keys(shots, other.shots),
        )
        diff.values.forEach { n += it.size }
        n += (deleted - other.deleted).count { t -> diff[t.kind]?.contains(t.id) != true }
        return n
    }

    val recordCount: Int get() = rifles.size + loads.size + bullets.size + library.size + cards.size + shots.size

    companion object {
        val EMPTY = SyncContent(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptySet())

        fun of(libs: Libraries, library: List<Bullet>) = SyncContent(
            rifles = libs.rifles.associate { it.id to it.copy(updatedAt = 0) },
            loads = libs.loads.associate { it.id to it.copy(updatedAt = 0) },
            bullets = libs.bullets.associate { it.id to it.copy(updatedAt = 0, source = "user", libraryOrder = null) },
            library = library.associate { it.id to it.copy(updatedAt = 0, source = "library") },
            cards = libs.cards.associate { it.id to it.copy(updatedAt = 0) },
            shots = libs.shots.associate { it.id to it.copy(updatedAt = 0) },
            deleted = libs.deleted.groupBy { it.kind to it.id }.values.map { g -> g.maxByOrNull { it.deletedAt }!! }.toSet(),
        )

        fun of(store: ProfileStore): SyncContent = of(store.snapshot(), store.libraryBullets())
    }
}
