package com.pixel9.signalsurvey.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.pixel9.signalsurvey.model.GeoFix
import com.pixel9.signalsurvey.model.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ties the ARCore world frame to true north.
 *
 * ARCore's world -Z points wherever the camera happened to be aimed at session start, which
 * is fine for everything except GNSS: satellite azimuth/elevation are only meaningful
 * against north. One heading fix per session is enough, since the world frame does not drift
 * in yaw once tracking is established.
 *
 * The high-accuracy alternative is the ARCore Geospatial API (`session.earth`), which gives
 * sub-degree heading — but it needs an API key, a network connection and VPS coverage, so
 * the magnetometer is the dependency-free default. Indoors, near steel, expect 10-20 degrees
 * of error; the app degrades by simply not drawing satellites.
 */
class HeadingResolver(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile private var magneticAzimuthRad: Float? = null
    @Volatile private var accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    private val listener = object : SensorEventListener {
        private val rotation = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            // orientation[0] is azimuth: rotation about -Z, relative to magnetic north.
            magneticAzimuthRad = orientation[0]
        }

        override fun onAccuracyChanged(sensor: Sensor?, acc: Int) { accuracy = acc }
    }

    fun start() {
        rotationVector?.let {
            sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensors?.unregisterListener(listener)
    }

    val isReliable: Boolean
        get() = magneticAzimuthRad != null && accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

    /**
     * Offset to add to an ARCore world yaw to get a true-north bearing, in radians.
     *
     * @param cameraForward the camera's forward direction in the ARCore world frame, taken at
     *   the same instant as the compass reading.
     * @param location used to correct magnetic declination; without it the result is magnetic
     *   north, which is off by up to ~20 degrees depending where you are standing.
     */
    fun worldToTrueNorthYawRad(cameraForward: Vec3, location: GeoFix?): Float? {
        val magnetic = magneticAzimuthRad ?: return null
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return null

        val declinationRad = location?.let {
            Math.toRadians(
                GeomagneticField(
                    it.lat.toFloat(),
                    it.lon.toFloat(),
                    (it.altM ?: 0.0).toFloat(),
                    System.currentTimeMillis(),
                ).declination.toDouble()
            ).toFloat()
        } ?: 0f

        val trueBearing = magnetic + declinationRad
        val worldYaw = atan2(cameraForward.x, -cameraForward.z)
        return trueBearing - worldYaw
    }

    companion object {
        /**
         * Direction, in the ARCore world frame, of a point at the given true-north azimuth and
         * elevation. Used to place GNSS satellites on a shot.
         */
        fun skyDirectionToWorld(
            azimuthDegFromNorth: Float,
            elevationDeg: Float,
            trueNorthYawRad: Float,
        ): Vec3 {
            val trueBearing = Math.toRadians(azimuthDegFromNorth.toDouble()).toFloat()
            val worldYaw = trueBearing - trueNorthYawRad
            val el = Math.toRadians(elevationDeg.toDouble()).toFloat()
            return Vec3(
                x = sin(worldYaw) * cos(el),
                y = sin(el),
                z = -cos(worldYaw) * cos(el),
            ).normalized()
        }

        /** Satellites are effectively at infinity; this is far enough to project cleanly. */
        const val SKY_RANGE_M = 1_000f
    }
}
