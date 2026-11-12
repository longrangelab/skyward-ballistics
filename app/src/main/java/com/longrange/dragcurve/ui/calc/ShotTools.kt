package com.longrange.dragcurve.ui.calc

import android.annotation.SuppressLint
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.asin
import kotlin.math.sqrt

private abstract class SensorDialog(val ctx: Context, val title: String, val note: String) : SensorEventListener {
    protected val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private lateinit var valueView: TextView
    protected var value: Double? = null

    abstract fun sensor(): Sensor?
    abstract fun format(v: Double): String
    open val valueSizeSp = 40f

    fun show(onUse: (Double) -> Unit) {
        val s = sensor()
        if (s == null) {
            MaterialAlertDialogBuilder(ctx).setTitle(title).setMessage("This phone has no suitable sensor.")
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(24), ctx.dp(8), ctx.dp(24), 0) }
        valueView = TextView(ctx).apply { text = "—"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSizeSp) }
        box.addView(valueView)
        box.addView(TextView(ctx).apply { text = note; alpha = 0.9f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPadding(0, ctx.dp(8), 0, 0) })
        val d = MaterialAlertDialogBuilder(ctx).setTitle(title).setView(box)
            .setPositiveButton("Use this value") { _, _ -> value?.let(onUse) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        d.setOnDismissListener { sm.unregisterListener(this) }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        d.show()
    }

    protected fun publish(v: Double) {
        value = v
        valueView.text = format(v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

object TiltTool {
    fun show(ctx: Context, onUse: (Double) -> Unit) = object : SensorDialog(ctx, "Uphill / downhill angle",
        "Lay the phone on the firearm (scope or rail) with its top edge pointing at the target. + = uphill.") {
        private var smooth: Double? = null
        override fun sensor(): Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        override fun format(v: Double) = Num.fixed(v, 1) + "°"
        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            val g = sqrt(x * x + y * y + z * z).takeIf { it > 1e-3 } ?: return
            val deg = Math.toDegrees(asin((y / g).coerceIn(-1.0, 1.0)))
            val s = smooth?.let { it + 0.2 * (deg - it) } ?: deg
            smooth = s
            publish(Math.rint(s * 10) / 10)
        }
    }.show(onUse)
}

object NorthText {
    fun toTrue(magneticDeg: Double, declinationDeg: Double): Double = WindText.normalize(magneticDeg + declinationDeg)

    fun describe(magneticDeg: Double, declinationDeg: Double?): String {
        val mag = Num.fixed(WindText.normalize(magneticDeg), 0) + "° magnetic"
        if (declinationDeg == null) return mag
        val d = Math.rint(declinationDeg * 10) / 10
        val sign = if (d < 0) "−" else if (d > 0) "+" else ""
        return mag + " → " + Num.fixed(toTrue(magneticDeg, declinationDeg), 0) + "° true (declination " + sign + Num.trim(kotlin.math.abs(d), 1) + "°)"
    }
}

object CompassTool {
    fun show(ctx: Context, onUse: (Double) -> Unit) {
        val loc = LatitudeTool.knownLocation(ctx)
        val decl: Double? = loc?.let {
            runCatching { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble() }.getOrNull()
        }
        val note = if (decl != null)
            "Hold the phone flat with its top edge pointing at the target; keep away from steel. True north uses the declination at your location; \"Use\" takes the true value."
        else "Hold the phone flat with its top edge pointing at the target; keep away from steel. Magnetic — set latitude/location (\"Get\" on Latitude) for true north."
        object : SensorDialog(ctx, "Direction of fire", note) {
            private val rot = FloatArray(9)
            private val ori = FloatArray(3)
            private var magnetic = 0.0
            override val valueSizeSp = if (decl != null) 22f else 40f
            override fun sensor(): Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            override fun format(v: Double) = NorthText.describe(magnetic, decl)
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                magnetic = WindText.normalize(Math.rint(Math.toDegrees(ori[0].toDouble())))
                publish(if (decl != null) Math.rint(NorthText.toTrue(magnetic, decl)) else magnetic)
            }
        }.show(onUse)
    }
}

object LatitudeTool {
    @Volatile var lastFix: Location? = null

    @SuppressLint("MissingPermission")
    fun knownLocation(ctx: Context): Location? {
        lastFix?.let { return it }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    fun fetch(ctx: Context, onResult0: (Double?) -> Unit) {
        val onResult: (Location?) -> Unit = { l -> if (l != null) lastFix = l; onResult0(l?.latitude) }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = runCatching { lm.getProviders(true) }.getOrDefault(emptyList())
        val last = providers.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 7L * 24 * 3600 * 1000) { onResult(last); return }
        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull { it in providers }
        if (provider == null) { onResult(last); return }
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (done) return
                done = true; lm.removeUpdates(this); onResult(l)
            }
            @Deprecated("API < 29") override fun onStatusChanged(p: String?, s: Int, b: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (e: Exception) { onResult(last); return }
        handler.postDelayed({
            if (!done) { done = true; lm.removeUpdates(listener); onResult(last) }
        }, 20_000L)
    }
}
