package com.longrange.dragcurve.exchange

import com.longrange.dragcurve.model.ImportReport
import com.longrange.dragcurve.model.LibraryFormat
import com.longrange.dragcurve.model.ProfileStore
import com.longrange.dragcurve.model.Provenance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object LibraryIngest {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 5000
    private val TEXT_EXTENSIONS = setOf("json", "csv", "drg", "txt", "tsv")

    data class Item(val name: String, val format: LibraryFormat, val result: ImportResult)

    data class Prepared(val items: List<Item>, val skipped: List<String>) {
        val rifles: Int get() = items.sumOf { it.result.libs.rifles.size }
        val loads: Int get() = items.sumOf { it.result.libs.loads.size }
        val bullets: Int get() = items.sumOf { it.result.libs.bullets.size }
        val formatNames: List<String> get() = items.map { it.format.displayName }.distinct()
    }

    fun prepare(bytes: ByteArray, name: String, url: String? = null, today: String? = null): Prepared {
        val items = ArrayList<Item>()
        val skipped = ArrayList<String>()
        if (isZip(bytes)) {
            var seen = 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (++seen > MAX_ENTRIES) { skipped += "…and the rest of the archive (more than $MAX_ENTRIES files)."; break }
                    val entryName = e.name.substringAfterLast('/').substringAfterLast('\\')
                    if (entryName.substringAfterLast('.', "").lowercase() !in TEXT_EXTENSIONS) continue
                    val data = readLimited(zip, MAX_ENTRY_BYTES)
                    if (data == null) { skipped += "$entryName: larger than ${MAX_ENTRY_BYTES / (1024 * 1024)} MB."; continue }
                    try {
                        val d = FormatRegistry.detect(decode(data))
                        items += Item(entryName, d.format, d.result)
                    } catch (ex: IllegalArgumentException) {
                        skipped += "$entryName: not a format this app reads."
                    }
                }
            }
            if (items.isEmpty()) throw IllegalArgumentException("Nothing in this zip file is in a format this app reads (it looked at .json, .csv, .drg and .txt files).")
        } else {
            val d = FormatRegistry.detect(decode(bytes))
            items += Item(name, d.format, d.result)
        }
        return Prepared(if (url == null) items else items.map { it.copy(result = stamp(it.result, url, today)) }, skipped)
    }

    fun commit(store: ProfileStore, p: Prepared): ImportReport {
        var added = 0; var updated = 0; var renamed = 0; var skipped = 0; var deleted = 0
        val msgs = ArrayList<String>()
        val many = p.items.size > 1
        for (it in p.items) {
            val r = LibraryFiles.importResult(store, it.result)
            added += r.added; updated += r.updated; renamed += r.renamed; skipped += r.skipped; deleted += r.deleted
            msgs += if (many) r.messages.filterNot { m -> m.contains("already up to date") || m.contains("already here unchanged") } else r.messages
        }
        if (p.skipped.isNotEmpty()) {
            msgs += "${p.skipped.size} file(s) in the archive were not imported:"
            msgs += p.skipped.take(8)
            if (p.skipped.size > 8) msgs += "…and ${p.skipped.size - 8} more."
        }
        return ImportReport(added, updated, renamed, skipped, msgs, deleted)
    }

    private fun stamp(res: ImportResult, url: String, today: String?): ImportResult =
        res.copy(libs = res.libs.copy(bullets = res.libs.bullets.map { b ->
            val p = b.provenance ?: Provenance()
            if (b.source != "library" || p.url.isNotEmpty()) b
            else b.copy(provenance = p.copy(url = url, date = p.date.ifEmpty { today ?: "" }))
        }))

    fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2].toInt() == 3 && b[3].toInt() == 4

    fun decode(b: ByteArray): String = String(b, Charsets.UTF_8).removePrefix("﻿").replace("\u0000", "")

    fun readLimited(input: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun checkUrl(typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) throw IllegalArgumentException("Type the web address of the file.")
        val u = if (t.contains("://")) t else "https://$t"
        val parsed = try { URL(u) } catch (e: Exception) { throw IllegalArgumentException("That is not a web address.") }
        if (!parsed.protocol.equals("https", ignoreCase = true)) throw IllegalArgumentException("Only https:// addresses can be downloaded.")
        if (parsed.host.isNullOrBlank() || !parsed.host.contains('.')) throw IllegalArgumentException("That is not a web address.")
        return u
    }

    fun download(url: String): ByteArray = fetch(url).bytes ?: throw IOException("The server sent nothing.")

    class Fetched(val bytes: ByteArray?, val etag: String?, val lastModified: String?)

    fun fetch(url: String, etag: String? = null, lastModified: String? = null): Fetched {
        var current = checkUrl(url)
        repeat(5) {
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.requestMethod = "GET"
                c.setRequestProperty("User-Agent", "SkywardBallistics")
                c.setRequestProperty("Accept", "*/*")
                etag?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("If-None-Match", it) }
                lastModified?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("If-Modified-Since", it) }
                val code = c.responseCode
                if (code == 304) return Fetched(null, etag, lastModified)
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("The server redirected without saying where to.")
                    current = try { checkUrl(URL(URL(current), next).toString()) } catch (e: IllegalArgumentException) { throw IOException("The download was redirected to an address that is not https.") }
                    return@repeat
                }
                if (code != 200) throw IOException("The server answered $code for that address.")
                val bytes = c.inputStream.use { readLimited(it, MAX_BYTES) } ?: throw IOException("That file is larger than ${MAX_BYTES / (1024 * 1024)} MB.")
                return Fetched(bytes, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"))
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects.")
    }
}
