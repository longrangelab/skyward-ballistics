package com.longrange.dragcurve.sync

import com.longrange.dragcurve.exchange.ProfileBrowser
import com.longrange.dragcurve.exchange.SharedIntake
import com.longrange.dragcurve.model.ProfileSource
import com.longrange.dragcurve.model.ProfileStore
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val modifiedTime: String = "",
    val size: Long = 0,
) {
    val isFolder: Boolean get() = mimeType == DriveBackups.FOLDER_MIME
}

interface DriveReader {
    fun folder(id: String): DriveItem?
    fun folders(nameContains: String? = null): List<DriveItem>
    fun files(folderId: String): List<DriveItem>
    fun download(id: String): ByteArray
}

class DriveRestReader(private val token: () -> String, private val http: Http = UrlConnectionHttp()) : DriveReader {
    override fun folder(id: String): DriveItem? {
        val r = get("$API/files/${enc(id)}?fields=" + enc("$FIELDS,trashed") + "&supportsAllDrives=true", allow404 = true)
        if (r.code == 404) return null
        val o = JSONObject(r.body)
        return if (o.optBoolean("trashed", false)) null else item(o).takeIf { it.isFolder }
    }

    override fun folders(nameContains: String?): List<DriveItem> {
        val q = "mimeType = '${DriveBackups.FOLDER_MIME}' and trashed = false" +
            (nameContains?.trim()?.takeIf { it.isNotEmpty() }?.let { " and name contains '${it.replace("\\", "\\\\").replace("'", "\\'")}'" } ?: "")
        return list(q, "modifiedTime desc", 60)
    }

    override fun files(folderId: String): List<DriveItem> =
        list("'${folderId.replace("'", "\\'")}' in parents and trashed = false and mimeType != '${DriveBackups.FOLDER_MIME}'", "modifiedTime desc", DriveBackups.MAX_LISTED)

    override fun download(id: String): ByteArray = get("$API/files/${enc(id)}?alt=media&supportsAllDrives=true").body.toByteArray(Charsets.UTF_8)

    private fun list(q: String, orderBy: String, max: Int): List<DriveItem> {
        val url = "$API/files?q=" + enc(q) + "&fields=" + enc("files($FIELDS)") + "&orderBy=" + enc(orderBy) +
            "&pageSize=$max&spaces=drive&supportsAllDrives=true&includeItemsFromAllDrives=true"
        val files = JSONObject(get(url).body).optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { item(files.getJSONObject(it)) }
    }

    private fun item(o: JSONObject) = DriveItem(
        id = o.optString("id", ""), name = o.optString("name", ""), mimeType = o.optString("mimeType", ""),
        modifiedTime = o.optString("modifiedTime", ""), size = o.optString("size", "0").toLongOrNull() ?: 0,
    )

    private fun get(url: String, allow404: Boolean = false): HttpResponse {
        val r = http.request("GET", url, mapOf("Authorization" to "Bearer " + token(), "Accept" to "application/json"), null)
        if (r.code in 200..299 || (allow404 && r.code == 404)) return r
        val (reason, message) = runCatching {
            val e = JSONObject(r.body).getJSONObject("error")
            (e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason", "") ?: "").ifEmpty { e.optString("status", "") } to e.optString("message", "")
        }.getOrDefault("" to "")
        if (r.code == 403 && (reason.contains("accessNotConfigured", true) || message.contains("has not been used in project", true) || message.contains("is disabled", true)))
            throw DriveNotSetUpException()
        if (r.code == 401 || (r.code == 403 && (reason.contains("insufficient", true) || reason.contains("SCOPE", true) || message.contains("insufficient", true))))
            throw RemoteAuthException("Google Drive refused access (${r.code}${if (message.isNotEmpty()) ": $message" else ""}).")
        throw RemoteHttpException(r.code, "Google Drive error ${r.code}${if (message.isNotEmpty()) ": $message" else ""}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val FIELDS = "id,name,mimeType,modifiedTime,size"
    }
}

class DriveNotSetUpException : IOException(DriveBackups.NOT_SET_UP)

data class DriveBackupState(
    val folderId: String = "",
    val folderName: String = "",
    val fileId: String = "",
    val fileName: String = "",
    val fileModified: String = "",
    val fileIsEmpirical: Boolean = true,
    val checkedAt: Long = 0,
    val error: String = "",
) {
    val enabled: Boolean get() = folderId.isNotEmpty()

    fun toJson(): String = JSONObject().put("folderId", folderId).put("folderName", folderName).put("fileId", fileId)
        .put("fileName", fileName).put("fileModified", fileModified).put("fileIsEmpirical", fileIsEmpirical)
        .put("checkedAt", checkedAt).put("error", error).toString()

    companion object {
        fun fromJson(text: String?): DriveBackupState {
            if (text.isNullOrBlank()) return DriveBackupState()
            return try {
                val o = JSONObject(text)
                DriveBackupState(o.optString("folderId"), o.optString("folderName"), o.optString("fileId"), o.optString("fileName"),
                    o.optString("fileModified"), o.optBoolean("fileIsEmpirical", true), o.optLong("checkedAt"), o.optString("error"))
            } catch (e: Exception) { DriveBackupState() }
        }
    }
}

object DriveBackups {
    const val SOURCE_ID = "empirical-drive"
    const val SOURCE_NAME = "Empirical backups in Google Drive"
    const val FILE = "drive-backup.json"
    const val FOLDER_MIME = "application/vnd.google-apps.folder"
    const val MAX_LISTED = 50
    const val MAX_OPENED = 12
    const val NOT_SET_UP = "Google Drive isn't set up for Skyward Ballistics yet: the Google Cloud project needs the Drive API switched on and the read-only Drive scope added (see GOOGLE_CLOUD_SETUP.md)."
    const val HELP = "In Empirical Precision: Custom Data → Back Up and Move → Prepare Backup → Share Backup → Drive, and save it in this folder."

    sealed class Result {
        data class Updated(val file: DriveItem, val kind: String) : Result()
        data class Unchanged(val file: DriveItem) : Result()
        data class NoBackup(val looked: Int) : Result()
        object FolderMissing : Result()
        data class SignInNeeded(val message: String) : Result()
        data class Failed(val message: String) : Result()
        object Off : Result()
    }

    fun state(store: ProfileStore): DriveBackupState = DriveBackupState.fromJson(store.driveBackupState())

    fun copyFile(filesDir: File): File = File(File(filesDir, "shared"), FILE)

    fun choose(store: ProfileStore, filesDir: File, folder: DriveItem) {
        val old = state(store)
        if (old.folderId != folder.id) dropCopy(store, filesDir)
        store.saveDriveBackupState(DriveBackupState(folderId = folder.id, folderName = folder.name).toJson())
    }

    fun turnOff(store: ProfileStore, filesDir: File) {
        dropCopy(store, filesDir)
        store.saveDriveBackupState(null)
    }

    private fun dropCopy(store: ProfileStore, filesDir: File) {
        store.saveProfileSources(store.profileSources().filterNot { it.id == SOURCE_ID })
        runCatching { copyFile(filesDir).delete() }
    }

    fun candidates(files: List<DriveItem>): List<DriveItem> = files
        .filter { f ->
            !f.isFolder && !f.mimeType.startsWith("application/vnd.google-apps") &&
                listOf("image/", "video/", "audio/", "application/pdf").none { f.mimeType.startsWith(it) } &&
                (f.size == 0L || f.size <= SharedIntake.MAX_BYTES)
        }
        .sortedByDescending { it.modifiedTime }

    fun refresh(store: ProfileStore, filesDir: File, drive: DriveReader, uri: String, now: Long): Result {
        val st = state(store)
        if (!st.enabled) return Result.Off
        fun done(r: Result, s: DriveBackupState): Result { store.saveDriveBackupState(s.copy(checkedAt = now).toJson()); return r }
        try {
            val folder = drive.folder(st.folderId)
                ?: return done(Result.FolderMissing, st.copy(error = "The Drive folder \"${st.folderName}\" can't be found any more. Choose the folder again."))
            val files = candidates(drive.files(st.folderId))
            val newest = files.firstOrNull()
            val haveCopy = copyFile(filesDir).exists()
            if (newest != null && st.fileIsEmpirical && newest.id == st.fileId && newest.modifiedTime == st.fileModified && haveCopy) {
                return done(Result.Unchanged(newest), st.copy(folderName = folder.name, error = ""))
            }
            var looked = 0
            var fallback: Triple<DriveItem, String, String>? = null
            for (f in files.take(MAX_OPENED)) {
                if (f.id == st.fileId && f.modifiedTime == st.fileModified && haveCopy) {
                    if (st.fileIsEmpirical) return done(Result.Unchanged(f), st.copy(folderName = folder.name, error = ""))
                    if (fallback == null) fallback = Triple(f, "", "Skyward Ballistics library")
                    continue
                }
                looked++
                val bytes = drive.download(f.id)
                when (val got = SharedIntake.sniff(bytes, f.name)) {
                    is SharedIntake.Received.EmpiricalBackup -> return keep(store, filesDir, st, folder, f, got.text, "Empirical Precision backup", uri, now)
                    is SharedIntake.Received.Library -> {
                        val text = com.longrange.dragcurve.exchange.LibraryIngest.decode(bytes)
                        if (fallback == null && runCatching { ProfileBrowser.readerFor(text) }.isSuccess) fallback = Triple(f, text, "Skyward Ballistics library")
                    }
                    is SharedIntake.Received.Refused -> {}
                }
            }
            fallback?.let { (f, text, kind) ->
                return if (text.isEmpty()) done(Result.Unchanged(f), st.copy(folderName = folder.name, error = ""))
                else keep(store, filesDir, st, folder, f, text, kind, uri, now)
            }
            return done(Result.NoBackup(looked), st.copy(folderName = folder.name,
                error = "No Empirical Precision backup in the Drive folder \"${folder.name}\" yet. $HELP"))
        } catch (e: RemoteAuthException) {
            return done(Result.SignInNeeded(e.message ?: "Google Drive needs you to allow access again."), st.copy(error = "Google Drive needs you to allow access again."))
        } catch (e: DriveNotSetUpException) {
            return done(Result.Failed(NOT_SET_UP), st.copy(error = NOT_SET_UP))
        } catch (e: Exception) {
            val why = if (e is IOException && e !is RemoteHttpException) "No connection to Google Drive." else (e.message ?: e.javaClass.simpleName)
            return done(Result.Failed(why), st.copy(error = why))
        }
    }

    private fun keep(store: ProfileStore, filesDir: File, st: DriveBackupState, folder: DriveItem, f: DriveItem, text: String, kind: String, uri: String, now: Long): Result {
        val file = copyFile(filesDir)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        try { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
        catch (e: Exception) { Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        val all = store.profileSources()
        val ex = all.firstOrNull { it.id == SOURCE_ID }
        val src = ProfileSource(SOURCE_ID, uri, ex?.name ?: SOURCE_NAME, lastReadAt = 0, lastError = "", receivedAt = now)
        val shared = all.filter { it.id == SharedIntake.SHARED_EP_ID }
        store.saveProfileSources(shared + src + all.filterNot { it.id == SOURCE_ID || it.id == SharedIntake.SHARED_EP_ID })
        store.saveDriveBackupState(st.copy(folderName = folder.name, fileId = f.id, fileName = f.name, fileModified = f.modifiedTime,
            fileIsEmpirical = kind.startsWith("Empirical"), checkedAt = now, error = "").toJson())
        return Result.Updated(f, kind)
    }

    fun statusLine(st: DriveBackupState, time: (String) -> String): String = when {
        !st.enabled -> "Off"
        st.fileId.isEmpty() -> st.error.ifEmpty { "No backup found yet in \"${st.folderName}\"." }
        else -> "Newest backup: ${st.fileName}, ${time(st.fileModified)}, from Drive" + (if (st.error.isNotEmpty()) "\n⚠ ${st.error} Showing that copy." else "")
    }
}
