package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.ProfileSource
import com.longrange.dragcurve.model.ProfileStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object SharedIntake {
    const val SHARED_EP_ID = "empirical-shared"
    const val SHARED_EP_NAME = "Empirical Precision (shared)"
    const val SHARED_FILE = "empirical-shared.json"
    const val MAX_BYTES = 24 * 1024 * 1024
    const val HELP = "In Empirical Precision: Custom Data → Back Up and Move → Prepare Backup → Share Backup → Skyward Ballistics."

    sealed class Received {
        data class EmpiricalBackup(val text: String) : Received()
        data class Library(val prepared: LibraryIngest.Prepared) : Received()
        data class Refused(val message: String) : Received()
    }

    fun sniff(bytes: ByteArray, name: String = "shared file"): Received {
        if (bytes.isEmpty()) return Received.Refused("That file is empty.")
        if (bytes.size > MAX_BYTES) return Received.Refused("That file is larger than ${MAX_BYTES / (1024 * 1024)} MB, which is more than a backup or library should be.")
        if (!LibraryIngest.isZip(bytes)) {
            val text = LibraryIngest.decode(bytes)
            if (text.take(4000).contains("\"${Ep.BACKUP_FORMAT}\"")) {
                return try { EpBackup.parse(text); Received.EmpiricalBackup(text) }
                catch (e: IllegalArgumentException) { Received.Refused(e.message ?: "That Empirical Precision backup can't be read.") }
            }
        }
        return try {
            Received.Library(LibraryIngest.prepare(bytes, name))
        } catch (e: Exception) {
            Received.Refused(
                "Skyward Ballistics can't use that file.\n\nIt reads an Empirical Precision backup (in Empirical Precision: Custom Data → Back Up and Move → Prepare Backup → Share Backup), " +
                    "a Skyward Ballistics library or backup file, a bullet table (.csv) or a drag file (.drg)."
            )
        }
    }

    fun sharedFile(filesDir: File): File = File(File(filesDir, "shared"), SHARED_FILE)

    fun store(store: ProfileStore, filesDir: File, text: String, uri: String, now: Long): ProfileSource {
        val f = sharedFile(filesDir)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        val all = store.profileSources()
        val ex = all.firstOrNull { it.id == SHARED_EP_ID }
        val src = ProfileSource(SHARED_EP_ID, uri, ex?.name ?: SHARED_EP_NAME, lastReadAt = 0, lastError = "", receivedAt = now)
        store.saveProfileSources(listOf(src) + all.filterNot { it.id == SHARED_EP_ID })
        return src
    }

    fun remove(store: ProfileStore, filesDir: File) {
        store.saveProfileSources(store.profileSources().filterNot { it.id == SHARED_EP_ID })
        runCatching { sharedFile(filesDir).delete() }
    }

    data class Counts(val firearms: Int, val ammo: Int, val new: Int, val changed: Int)

    fun counts(entries: List<BrowseEntry>, sourceId: String): Counts {
        val mine = entries.filter { it.sourceId == sourceId }
        return Counts(
            firearms = mine.count { it.kind == BrowseKind.FIREARM }, ammo = mine.count { it.kind == BrowseKind.AMMO },
            new = mine.count { it.status == BrowseStatus.NEW }, changed = mine.count { it.status == BrowseStatus.UPDATE },
        )
    }

    fun countsText(c: Counts): String {
        val firearms = if (c.firearms == 1) "1 firearm" else "${c.firearms} firearms"
        val rest = if (c.new == 0 && c.changed == 0) "nothing new or changed" else "${c.new} new, ${c.changed} changed"
        return "$firearms, ${c.ammo} ammo; $rest"
    }

    fun banner(c: Counts, receivedText: String): String = "Empirical backup received $receivedText: ${countsText(c)}."
}
