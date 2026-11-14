package com.longrange.dragcurve.sync

import com.longrange.dragcurve.model.BcPoint
import com.longrange.dragcurve.model.Bullet
import com.longrange.dragcurve.model.CdPoint
import com.longrange.dragcurve.model.Libraries
import com.longrange.dragcurve.model.Load
import com.longrange.dragcurve.model.Rifle
import com.longrange.dragcurve.store.JsonProfileStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

/** A base station that behaves like Coriolis WebUI.h: one file, a version, 409 on a stale base, a size limit. */
class FakeBase(var max: Long = 384L * 1024L, var formats: List<Int>? = listOf(1), var api: Int? = 1) : BaseHttp {
    var file: ByteArray = ByteArray(0)
    var gz = false
    var ver = 3L
    var posts = 0
    var conflictNext = false

    /** What a phone on the Coriolis page would read. */
    fun text(): String = CoriolisBase.decode(file)

    /** A phone on the Coriolis page saved this (plain JSON). */
    fun phoneSaves(text: String) { file = text.toByteArray(); gz = false; ver++ }

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BaseResponse {
        val p = path.substringBefore('?')
        val q = path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return when {
            method == "GET" && p == "/gear/info" -> {
                val o = JSONObject().put("ok", true).put("ver", ver).put("bytes", file.size).put("gz", gz).put("max", max)
                if (api != null) o.put("api", api).put("fw", "test")
                if (formats != null) o.put("formats", JSONArray(formats))
                ok(o)
            }
            method == "GET" && p == "/gear" ->
                if (file.isEmpty()) BaseResponse(204, emptyMap(), ByteArray(0))
                else BaseResponse(200, if (gz) mapOf("Content-Encoding" to "gzip", "X-Gear-Ver" to "$ver") else mapOf("X-Gear-Ver" to "$ver"), file)
            method == "POST" && p == "/gear" -> {
                posts++
                if (conflictNext) { conflictNext = false; ver++; return BaseResponse(409, emptyMap(), JSONObject().put("ok", false).put("err", "conflict").toString().toByteArray()) }
                if (q["base"]?.toLongOrNull() != ver) return BaseResponse(409, emptyMap(), JSONObject().put("ok", false).put("err", "conflict").toString().toByteArray())
                val part = filePart(headers["Content-Type"]!!, body!!)
                if (part.size > max) return BaseResponse(413, emptyMap(), JSONObject().put("ok", false).put("err", "too_big").toString().toByteArray())
                file = part; gz = q["gz"] == "1"; ver++
                ok(JSONObject().put("ok", true).put("ver", ver).put("bytes", part.size))
            }
            else -> BaseResponse(404, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(o: JSONObject) = BaseResponse(200, emptyMap(), o.toString().toByteArray())

    private fun filePart(type: String, body: ByteArray): ByteArray {
        val boundary = "--" + type.substringAfter("boundary=")
        val s = String(body, Charsets.ISO_8859_1)
        val start = s.indexOf("\r\n\r\n", s.indexOf(boundary)) + 4
        val end = s.indexOf("\r\n$boundary--")
        return s.substring(start, end).toByteArray(Charsets.ISO_8859_1)
    }
}

class CoriolisBaseTest {
    private fun store(): JsonProfileStore = JsonProfileStore(Files.createTempDirectory("sky").toFile())

    private fun gear(s: JsonProfileStore) {
        s.saveBullet(Bullet(id = "b-own", name = "175 SMK", diameterIn = 0.308, weightGr = 175.0, bcPoints = listOf(BcPoint(0.243, 0.0)), updatedAt = 1000))
        s.saveRifle(Rifle(id = "r1", name = "AI .308", twistIn = 10.0, sightHeightIn = 1.9, updatedAt = 1000))
        s.saveLoad(Load(id = "l1", rifleId = "r1", name = "175 SMK 2650", bulletId = "b-own", muzzleVelocityFps = 2650.0, updatedAt = 1000))
    }

    private fun library(n: Int, curves: Boolean = true): List<Bullet> = (0 until n).map { i ->
        Bullet(id = "lib-$i", maker = "Maker ${i % 17}", name = "Bullet $i ${"x".repeat(i % 40)}", diameterIn = listOf(0.224, 0.264, 0.284, 0.308, 0.338)[i % 5],
            weightGr = 100.0 + (i * 7) % 150, source = "library", libraryOrder = i, collection = "test",
            bcPoints = listOf(BcPoint(0.2 + (i % 97) / 1000.0, 0.0)),
            dragCurve = if (curves && i % 3 == 0) (0 until 90).map { k -> CdPoint(k * 0.05, 0.12 + ((i * 31 + k * 17) % 1000) / 10000.0) } else emptyList())
    }

    private fun run(s: JsonProfileStore, b: FakeBase, automatic: Boolean = false, last: Long = 0L) =
        CoriolisBaseSync.run(s, b, SharedFileSync.Options("dev-test", last, automatic = automatic))

    @Test fun firstSyncWritesGearToAnEmptyBase() {
        val s = store(); gear(s); val b = FakeBase()
        val o = run(s, b)
        assertTrue(o.text, o.ok)
        assertTrue("written as gzip", b.gz)
        val f = JSONObject(b.text())
        assertEquals("skyward-library", f.getString("format"))
        assertEquals("master", f.getString("kind"))
        assertEquals("AI .308", f.getJSONArray("rifles").getJSONObject(0).getString("name"))
        assertEquals(1, b.posts)
    }

    @Test fun pullsAPhoneEditAndDoesNotResendWhenNothingChanged() {
        val s = store(); gear(s); val b = FakeBase()
        assertTrue(run(s, b).ok)
        // a phone on the Coriolis page changes the muzzle velocity
        val f = JSONObject(b.text())
        f.getJSONArray("loads").getJSONObject(0).put("muzzleVelocityFps", 2701.0).put("updatedAt", System.currentTimeMillis() + 60_000L)
        b.phoneSaves(f.toString())
        val posts = b.posts
        val o = run(s, b)
        assertTrue(o.text, o.ok)
        assertEquals(2701.0, s.loads(null).single().muzzleVelocityFps, 1e-9)
        assertEquals("nothing new to send", posts, b.posts)
        val again = run(s, b)
        assertTrue(again.ok)
        assertEquals("still nothing to send", posts, b.posts)
    }

    @Test fun pushesALocalEdit() {
        val s = store(); gear(s); val b = FakeBase()
        assertTrue(run(s, b).ok)
        s.saveRifle(s.rifles().single().copy(twistIn = 8.0, updatedAt = 9000))
        val o = run(s, b)
        assertTrue(o.text, o.ok)
        assertEquals(8.0, JSONObject(b.text()).getJSONArray("rifles").getJSONObject(0).getDouble("twistIn"), 1e-9)
    }

    @Test fun trimsTheLibraryToFitAndKeepsWhatAmmoUses() {
        val s = store()
        val lib = library(3000)
        s.importBulletLibrary(Libraries(bullets = lib))
        s.saveRifle(Rifle(id = "r1", name = "6.5", updatedAt = 1000))
        s.saveLoad(Load(id = "l1", rifleId = "r1", name = "lib load", bulletId = "lib-2999", muzzleVelocityFps = 2800.0, updatedAt = 1000))
        val b = FakeBase(max = 120L * 1024L)
        val o = run(s, b)
        assertTrue(o.text, o.ok)
        assertTrue("fits: ${b.file.size}", b.file.size <= b.max)
        val f = JSONObject(b.text())
        val ids = (0 until f.getJSONArray("bullets").length()).map { f.getJSONArray("bullets").getJSONObject(it).getString("id") }.toSet()
        assertTrue("the bullet the ammo uses is there", "lib-2999" in ids)
        assertTrue("some library left out", ids.size < 3000)
        assertTrue(o.text, o.text.contains("didn't fit"))
        // same caliber as the load's bullet (index 2999 % 5 = 4 -> .338) comes first
        val kept = ids.filter { it != "lib-2999" }.map { it.removePrefix("lib-").toInt() }
        assertTrue("calibre first", kept.count { it % 5 == 4 } >= kept.size / 2)
        // nothing on this device was removed, and a second sync sends nothing
        assertEquals(3000, s.libraryBullets().size)
        val posts = b.posts
        assertTrue(run(s, b).ok)
        assertEquals(posts, b.posts)
    }

    @Test fun conflictIsReportedAndTheNextSyncSucceeds() {
        val s = store(); gear(s); val b = FakeBase()
        assertTrue(run(s, b).ok)
        s.saveLoad(s.loads(null).single().copy(muzzleVelocityFps = 2600.0, updatedAt = 9000))
        b.conflictNext = true
        val o = run(s, b)
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("same moment"))
        assertTrue(run(s, b).ok)
        assertEquals(2600.0, JSONObject(b.text()).getJSONArray("loads").getJSONObject(0).getDouble("muzzleVelocityFps"), 1e-9)
    }

    @Test fun refusesABaseThatCannotReadThisFormat() {
        val s = store(); gear(s); val b = FakeBase(formats = listOf(2))
        val o = run(s, b)
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("Update the base station firmware"))
        assertEquals(0, b.posts)
    }

    @Test fun olderFirmwareWithoutVersionFieldsStillSyncs() {
        val s = store(); gear(s); val b = FakeBase(formats = null, api = null)
        assertTrue(run(s, b).ok)
        assertEquals(1, b.posts)
    }

    @Test fun manyDeletionsFromTheBaseWaitForTheUser() {
        val s = store()
        (1..9).forEach { i ->
            s.saveRifle(Rifle(id = "r$i", name = "Rifle $i", updatedAt = 1000))
        }
        val b = FakeBase()
        assertTrue(run(s, b).ok)
        val f = JSONObject(b.text())
        val dead = JSONArray()
        (1..9).forEach { dead.put(JSONObject().put("kind", "rifle").put("id", "r$it").put("deletedAt", System.currentTimeMillis() + 60_000L)) }
        f.put("rifles", JSONArray()).put("deleted", dead)
        b.phoneSaves(f.toString())
        val o = run(s, b, automatic = true, last = 2000L)
        assertTrue(o.text, o is SharedFileSync.Outcome.NeedsConfirmation)
        assertEquals(9, s.rifles().size)
        val yes = CoriolisBaseSync.run(s, b, SharedFileSync.Options("dev-test", 2000L, allowDeletions = 9))
        assertTrue(yes.text, yes.ok)
        assertEquals(0, s.rifles().size)
    }

    @Test fun readsACoriolisWrittenFile() {
        // what the Coriolis page writes: no syncVersion/deviceId, nameless-made "Unnamed", a null lengthIn
        val text = """{"format":"skyward-library","version":1,"kind":"master","app":"Coriolis",
          "rifles":[{"id":"cr","name":"Unnamed","twistIn":8,"twistDirection":"RIGHT","sightHeightIn":1.5,"updatedAt":4000}],
          "loads":[{"id":"cl","rifleId":"cr","name":"c load","bulletId":"cb","muzzleVelocityFps":2700,"zeroRangeYd":100,"updatedAt":4000}],
          "bullets":[{"id":"cb","maker":"","name":"175 gr G7 0.243","diameterIn":0.308,"weightGr":175,"lengthIn":null,"dragModel":"G7",
                      "bcPoints":[{"bc":0.243,"minVelFps":0}],"dragCurve":[],"source":"user","updatedAt":4000}],
          "deleted":[],"cards":[],"shots":[]}"""
        val b = FakeBase(); b.phoneSaves(text)
        val s = store()
        val o = run(s, b)
        assertTrue(o.text, o.ok)
        assertNotNull(s.loads(null).firstOrNull { it.id == "cl" })
        assertEquals("Unnamed", s.rifles().single().name)
    }

    @Test fun gzipRoundTrip() {
        val t = "{\"a\":1}"
        assertEquals(t, CoriolisBase.decode(CoriolisBase.gzip(t.toByteArray())))
        assertEquals(t, CoriolisBase.decode(("﻿" + t).toByteArray()))
        ByteArrayOutputStream().close()
    }
}
