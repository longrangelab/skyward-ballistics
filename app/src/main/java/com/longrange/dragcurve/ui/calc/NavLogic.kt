package com.longrange.dragcurve.ui.calc

object Recents {
    const val MAX = 8

    fun push(list: List<String>, id: String?): List<String> =
        if (id.isNullOrBlank()) list else (listOf(id) + list.filter { it != id }).take(MAX)

    fun order(ids: List<String>, recent: List<String>): List<String> {
        val have = ids.toSet()
        val first = recent.filter { it in have }.distinct()
        return first + ids.filter { it !in first.toSet() }
    }

    fun encode(list: List<String>): String = list.joinToString("\n")
    fun decode(text: String?): List<String> = text.orEmpty().split('\n').filter { it.isNotBlank() }
}

enum class InstallKind {
    FRESH,
    EXISTING,
    RESTORED;

    companion object {
        fun of(hadDeviceId: Boolean, hasStoreFiles: Boolean): InstallKind = when {
            hadDeviceId -> EXISTING
            hasStoreFiles -> RESTORED
            else -> FRESH
        }
    }
}

object BackupReminder {
    const val INTERVAL_MS = 30L * 24 * 3600 * 1000

    fun due(now: Long, firstSeenAt: Long, lastShownAt: Long, hasGear: Boolean, driveSyncOn: Boolean, folderBackupOn: Boolean): Boolean {
        if (!hasGear || driveSyncOn || folderBackupOn) return false
        if (firstSeenAt <= 0L) return false
        val since = maxOf(firstSeenAt, lastShownAt)
        return now - since >= INTERVAL_MS
    }
}

object BackupFiles {
    const val PREFIX = "skyward-backup-"
    const val KEEP = 10

    data class Made(val day: String, val name: String, val uri: String)

    fun name(day: String) = "$PREFIX$day.json"

    fun toDelete(made: List<Made>, keep: Int = KEEP): List<Made> =
        made.sortedByDescending { it.day }.drop(keep)

    fun record(made: List<Made>, m: Made): List<Made> = (made.filter { it.day != m.day } + m).sortedBy { it.day }
}

object DataSafetyText {
    val POINTS = listOf(
        "On this phone: everything is kept in Skyward's private storage until you uninstall the app or clear its storage.",
        "Android backup: your firearms, ammo, bullets, bullet library and settings go to your own Google account's Android backup, and come back when you reinstall or set up a new phone.",
        "Uninstalling: Android offers \"Keep app data\". Tick it, and a reinstall gets everything back.",
        "Safety copies: Skyward keeps the last 10 copies on this phone — one a day, and one before every import, refresh, library removal or sync that deletes something. Restore one from Backup & sync.",
        "Backup folder (optional): Skyward writes a backup file to a folder you choose, such as Documents. It stays even if the app is uninstalled.",
        "Google Drive sync (optional): keeps your devices in step through your own Google Drive.",
    )
}
