package com.frozenpull.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Tells whether the phone is held straight on (not tilted) and steady.
 * Works whether the phone is held sideways or upright.
 */
class Motion(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Forward/back tilt in degrees; 0 = camera pointing straight at the shelf. */
    @Volatile var pitch = 0f; private set
    /** Side-to-side tilt in degrees; 0 = level. */
    @Volatile var roll = 0f; private set
    @Volatile private var lastShakeAt = 0L

    val isLevel: Boolean get() = abs(pitch) < 6f && abs(roll) < 4f
    /** No noticeable movement for the last half second. */
    val isSteady: Boolean get() = SystemClock.elapsedRealtime() - lastShakeAt > 500

    fun start() {
        sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                val (gx, gy, gz) = Triple(e.values[0], e.values[1], e.values[2])
                // "Up" is whichever screen axis gravity mostly lies along.
                val sideways = abs(gx) > abs(gy)
                val up = if (sideways) abs(gx) else abs(gy)
                val across = if (sideways) gy else gx
                pitch = Math.toDegrees(atan2(gz.toDouble(), up.toDouble())).toFloat()
                roll = Math.toDegrees(atan2(across.toDouble(), up.toDouble())).toFloat()
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                if (w > 0.15f) lastShakeAt = SystemClock.elapsedRealtime()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
