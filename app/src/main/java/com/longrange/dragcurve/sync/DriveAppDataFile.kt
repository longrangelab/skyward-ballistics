package com.longrange.dragcurve.sync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

fun interface Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse
}

data class HttpResponse(val code: Int, val body: String)

class UrlConnectionHttp(private val timeoutMs: Int = 20_000) : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            try { c.requestMethod = method } catch (e: ProtocolException) {
                c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", method)
            }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            c.disconnect()
        }
    }
}

class DriveAppDataFile(
    private val token: () -> String,
    private val http: Http = UrlConnectionHttp(),
    private val fileName: String = SyncFile.FILE_NAME,
) : RemoteFile {
    override fun list(): List<RemoteMeta> {
        val out = ArrayList<RemoteMeta>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder" +
                "&q=" + enc("name = '${fileName.replace("'", "\\'")}' and trashed = false") +
                "&fields=" + enc("nextPageToken,files($FIELDS)") +
                "&orderBy=" + enc("createdTime") +
                "&pageSize=100" + (page?.let { "&pageToken=" + enc(it) } ?: "")
            val o = JSONObject(call("GET", url).body)
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) out += meta(files.getJSONObject(i))
            page = o.optString("nextPageToken", "").ifEmpty { null }
        } while (page != null)
        return out
    }

    override fun meta(id: String): RemoteMeta? {
        val r = call("GET", "$API/files/${enc(id)}?fields=" + enc("$FIELDS,trashed"), allow404 = true)
        if (r.code == 404) return null
        val o = JSONObject(r.body)
        return if (o.optBoolean("trashed", false)) null else meta(o)
    }

    override fun download(id: String): String = call("GET", "$API/files/${enc(id)}?alt=media").body

    override fun create(content: String): RemoteMeta {
        val boundary = "skyward-" + UUID.randomUUID().toString()
        val metadata = JSONObject().put("name", fileName).put("mimeType", SyncFile.MIME)
            .put("parents", org.json.JSONArray().put("appDataFolder"))
        val body = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString()).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Type: ").append(SyncFile.MIME).append("; charset=UTF-8\r\n\r\n")
            append(content).append("\r\n")
            append("--").append(boundary).append("--\r\n")
        }.toByteArray(Charsets.UTF_8)
        val r = call("POST", "$UPLOAD/files?uploadType=multipart&fields=" + enc(FIELDS), body, "multipart/related; boundary=$boundary")
        return meta(JSONObject(r.body))
    }

    override fun update(id: String, content: String): RemoteMeta {
        val r = call("PATCH", "$UPLOAD/files/${enc(id)}?uploadType=media&fields=" + enc(FIELDS),
            content.toByteArray(Charsets.UTF_8), "${SyncFile.MIME}; charset=UTF-8")
        return meta(JSONObject(r.body))
    }

    override fun delete(id: String) { call("DELETE", "$API/files/${enc(id)}", allow404 = true) }

    fun accountEmail(): String? = runCatching {
        JSONObject(call("GET", "$API/about?fields=" + enc("user(emailAddress)")).body)
            .optJSONObject("user")?.optString("emailAddress", "")?.ifEmpty { null }
    }.getOrNull()

    private fun call(method: String, url: String, body: ByteArray? = null, contentType: String? = null, allow404: Boolean = false): HttpResponse {
        val headers = LinkedHashMap<String, String>()
        headers["Authorization"] = "Bearer " + token()
        headers["Accept"] = "application/json"
        if (contentType != null) headers["Content-Type"] = contentType
        val r = http.request(method, url, headers, body)
        if (r.code in 200..299 || (allow404 && r.code == 404)) return r
        val (reason, message) = errorOf(r.body)
        if (r.code == 401 || (r.code == 403 && (reason.contains("insufficient", true) || reason.contains("SCOPE", true) || message.contains("insufficient", true))))
            throw RemoteAuthException("Google Drive refused the sign-in (${r.code}${if (message.isNotEmpty()) ": $message" else ""}).")
        throw RemoteHttpException(r.code, "Google Drive error ${r.code}${if (message.isNotEmpty()) ": $message" else ""}")
    }

    private fun errorOf(body: String): Pair<String, String> = runCatching {
        val e = JSONObject(body).getJSONObject("error")
        val reason = e.optJSONArray("errors")?.optJSONObject(0)?.optString("reason", "") ?: e.optString("status", "")
        (reason.ifEmpty { e.optString("status", "") }) to e.optString("message", "")
    }.getOrDefault("" to "")

    private fun meta(o: JSONObject): RemoteMeta {
        val id = o.optString("id", "")
        if (id.isEmpty()) throw IOException("Google Drive returned no file id.")
        val version = o.optString("version", "").ifEmpty { o.optString("headRevisionId", "") }.ifEmpty { o.optString("modifiedTime", "") }
        return RemoteMeta(id, version, o.optString("createdTime", ""))
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FIELDS = "id,name,version,headRevisionId,createdTime,modifiedTime"
    }
}
