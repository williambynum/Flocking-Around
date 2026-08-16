package com.pixel9.signalsurvey.ar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.pixel9.signalsurvey.model.CircularStats
import com.pixel9.signalsurvey.model.GeoFix
import com.pixel9.signalsurvey.model.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Best estimate of where true north is, in the ARCore world frame, with a real error bar.
 *
 * @param yawRad add this to an ARCore world yaw to get a true-north bearing.
 * @param uncertaintyRad 1-sigma, derived from how much the samples actually disagreed.
 */
data class HeadingSolution(
    val yawRad: Float,
    val uncertaintyRad: Float,
    val sampleCount: Int,
    val rejectedCount: Int,
    /** Circular concentration, 0..1. 1 means every sample agreed exactly. */
    val concentration: Float,
) {
    val uncertaintyDeg: Float get() = Math.toDegrees(uncertaintyRad.toDouble()).toFloat()

    val quality: String get() = when {
        uncertaintyDeg < 5f -> "good"
        uncertaintyDeg < 10f -> "fair"
        uncertaintyDeg < 20f -> "poor"
        else -> "unusable"
    }
}

/**
 * Ties the ARCore world frame to true north.
 *
 * ARCore's world -Z points wherever the camera happened to be aimed at session start, so
 * anything expressed against true north — GNSS satellite azimuths, tower bearings — needs
 * this offset. Nothing else in the app does: RTT trilateration, RSSI localisation, visual
 * anchors and every annotation live purely in the world frame.
 *
 * A single magnetometer reading indoors is worth very little; near rebar, motors or a laptop
 * it can be 40 degrees out and perfectly confident about it. Three things make this usable:
 *
 * 1. **Distortion rejection.** [GeomagneticField] says what the field *should* measure at
 *    this location. When the magnitude or the dip angle disagrees, something ferrous is
 *    nearby and the sample is discarded rather than averaged in. The dip check is the
 *    stronger of the two — local distortion tilts the field out of plane long before it
 *    changes the magnitude much.
 *
 * 2. **Averaging over the walk.** ARCore supplies accurate *relative* orientation, so every
 *    sample along the arming sweep is an independent estimate of the same fixed offset.
 *    Distortion is position-dependent and largely cancels across a few metres. Samples are
 *    combined with a circular mean, because these are angles and an arithmetic mean breaks
 *    across the 0/360 wrap.
 *
 * 3. **A measured error bar.** The circular variance of those samples *is* the uncertainty —
 *    no assumed constant. That number is what lets the renderer draw an arc instead of a
 *    point, and lets the app decline to project satellites at all when it does not know
 *    where they are.
 *
 * What this deliberately does not do is reach for ARCore Geospatial. Its sub-degree heading
 * comes from VPS, which is derived from Street View and therefore exists outdoors; indoors,
 * where the magnetometer is worst, it falls back to fusing GPS and this same magnetometer.
 * It is the right answer for outdoor surveys and no answer at all for the case that hurts.
 */
class HeadingResolver(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    @Volatile private var magneticAzimuthRad: Float? = null
    @Volatile private var rotationAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE
    @Volatile private var magneticVector: FloatArray? = null
    @Volatile private var magneticAccuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE

    /** Offsets in radians, one per accepted sample. */
    private val offsets = ArrayList<Float>()
    private val samplesLock = Any()
    private var rejected = 0
    private var lastSamplePosition: Vec3? = null
    private var lastSampleYaw: Float? = null

    @Volatile private var cached: HeadingSolution? = null
    @Volatile private var cacheDirty = true

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    // orientation[0] is azimuth about -Z, relative to MAGNETIC north.
                    magneticAzimuthRad = orientation[0]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magneticVector = event.values.copyOf(3)
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            when (sensor?.type) {
                Sensor.TYPE_ROTATION_VECTOR -> rotationAccuracy = accuracy
                Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = accuracy
            }
        }
    }

    fun start() {
        rotationVector?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensors?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensors?.unregisterListener(listener)
    }

    fun reset() {
        synchronized(samplesLock) {
            offsets.clear()
            rejected = 0
            lastSamplePosition = null
            lastSampleYaw = null
        }
        cached = null
        cacheDirty = true
    }

    /** True when the magnetometer reports it needs the figure-of-eight calibration gesture. */
    val needsCalibration: Boolean
        get() = magneticAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
            magneticAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW

    /**
     * Offer one observation. Call from the AR frame loop; cheap, and self-throttling.
     *
     * @param cameraForward camera forward direction in the ARCore world frame.
     * @param cameraPosition camera world position, used to space samples out.
     * @param location for magnetic declination and for the expected field. Without it the
     *   result is magnetic north, which is up to ~20 degrees off depending where you stand.
     * @return true if the sample was accepted.
     */
    fun recordSample(cameraForward: Vec3, cameraPosition: Vec3, location: GeoFix?): Boolean {
        val azimuth = magneticAzimuthRad ?: return false
        if (rotationAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return false

        val worldYaw = atan2(cameraForward.x, -cameraForward.z)

        // Stationary sampling stacks near-identical readings, which shrinks the variance
        // without adding information and produces a confidently wrong error bar. Require
        // either translation or a decent change of aim.
        synchronized(samplesLock) {
            val movedEnough = lastSamplePosition?.let {
                it.distanceTo(cameraPosition) > MIN_SPACING_M
            } ?: true
            val turnedEnough = lastSampleYaw?.let {
                abs(angleDifference(worldYaw, it)) > MIN_YAW_CHANGE_RAD
            } ?: true
            if (!movedEnough && !turnedEnough) return false
        }

        val field = location?.let {
            GeomagneticField(
                it.lat.toFloat(),
                it.lon.toFloat(),
                (it.altM ?: 0.0).toFloat(),
                System.currentTimeMillis(),
            )
        }

        if (!passesDistortionGate(field)) {
            synchronized(samplesLock) {
                rejected++
                lastSamplePosition = cameraPosition
                lastSampleYaw = worldYaw
            }
            return false
        }

        val declinationRad = field?.let {
            Math.toRadians(it.declination.toDouble()).toFloat()
        } ?: 0f

        val trueBearing = azimuth + declinationRad
        val offset = normalize(trueBearing - worldYaw)

        synchronized(samplesLock) {
            offsets += offset
            if (offsets.size > MAX_SAMPLES) offsets.removeAt(0)
            lastSamplePosition = cameraPosition
            lastSampleYaw = worldYaw
        }
        cacheDirty = true
        return true
    }

    /**
     * Reject readings taken inside a distorted field.
     *
     * Both tests compare against what the Earth's field ought to be here. A steel beam, a
     * motor or a laptop changes the dip angle noticeably before it changes the magnitude
     * much, which is why the inclination test carries the tighter tolerance.
     */
    private fun passesDistortionGate(field: GeomagneticField?): Boolean {
        val magnetic = magneticVector ?: return false
        if (magneticAccuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return false

        val magnitudeUt = sqrt(
            magnetic[0] * magnetic[0] + magnetic[1] * magnetic[1] + magnetic[2] * magnetic[2]
        )

        // Sanity floor and ceiling for anywhere on Earth (roughly 25-65 uT).
        if (magnitudeUt < ABSOLUTE_MIN_UT || magnitudeUt > ABSOLUTE_MAX_UT) return false

        if (field == null) return true   // no location: the gates below cannot be evaluated

        val expectedUt = field.fieldStrength / 1000f     // nT -> uT
        if (expectedUt <= 0f) return true
        if (abs(magnitudeUt - expectedUt) / expectedUt > MAX_MAGNITUDE_DEVIATION) return false

        // Dip angle: rotate the measured field into the world frame and measure how far it
        // points below horizontal. rotationMatrix maps device -> world (ENU, +Z up).
        val r = rotationMatrix
        val worldX = r[0] * magnetic[0] + r[1] * magnetic[1] + r[2] * magnetic[2]
        val worldY = r[3] * magnetic[0] + r[4] * magnetic[1] + r[5] * magnetic[2]
        val worldZ = r[6] * magnetic[0] + r[7] * magnetic[1] + r[8] * magnetic[2]

        val measuredDipDeg = Math.toDegrees(
            -atan2(worldZ.toDouble(), hypot(worldX.toDouble(), worldY.toDouble()))
        ).toFloat()

        return abs(measuredDipDeg - field.inclination) <= MAX_INCLINATION_DEVIATION_DEG
    }

    /**
     * Combine the accepted samples.
     *
     * Circular statistics throughout: the mean is the direction of the resultant vector, and
     * the length of that resultant is how much the samples agreed, which converts directly
     * into a standard deviation. One trimming pass drops samples beyond 2.5 sigma, which
     * removes the occasional reading taken while walking past a filing cabinet.
     */
    fun solution(): HeadingSolution? {
        cached?.let { if (!cacheDirty) return it }

        val (working, rejectedCount) = synchronized(samplesLock) {
            offsets.toList() to rejected
        }
        if (working.size < MIN_SAMPLES) return null

        val estimate = CircularStats.trimmedEstimate(
            angles = working,
            sigmas = TRIM_SIGMA,
            minRetained = MIN_SAMPLES,
        ) ?: return null

        val solution = HeadingSolution(
            yawRad = estimate.meanRad,
            uncertaintyRad = CircularStats.standardError(
                estimate, MIN_REPORTABLE_UNCERTAINTY_RAD
            ),
            sampleCount = estimate.count,
            rejectedCount = rejectedCount,
            concentration = estimate.concentration,
        )
        cached = solution
        cacheDirty = false
        return solution
    }

    private fun angleDifference(a: Float, b: Float): Float = CircularStats.difference(a, b)

    private fun normalize(angle: Float): Float = CircularStats.normalize(angle)

    private companion object {
        const val MIN_SAMPLES = 8
        const val MAX_SAMPLES = 240
        const val MIN_SPACING_M = 0.30f
        const val MIN_YAW_CHANGE_RAD = 0.35f            // ~20 degrees
        const val TRIM_SIGMA = 2.5f

        /** Beyond 20% off the expected magnitude, something ferrous is close. */
        const val MAX_MAGNITUDE_DEVIATION = 0.20f
        /** The sharper test: distortion tilts the field out of plane. */
        const val MAX_INCLINATION_DEVIATION_DEG = 15f
        const val ABSOLUTE_MIN_UT = 20f
        const val ABSOLUTE_MAX_UT = 75f

        /** ~2 degrees. Correlated samples do not average down forever. */
        const val MIN_REPORTABLE_UNCERTAINTY_RAD = 0.035f
    }
}
