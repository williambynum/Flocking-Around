package com.pixel9.signalsurvey.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Statistics for angles.
 *
 * Extracted from the heading resolver so it can be tested without a device, because this is
 * where the subtle bugs live. The arithmetic mean of 350 degrees and 10 degrees is 180 —
 * pointing exactly backwards from the correct answer of 0 — and that mistake compiles, runs,
 * and produces a plausible-looking number forever.
 *
 * Angles are radians throughout, normalised to (-pi, pi].
 */
object CircularStats {

    data class Estimate(
        val meanRad: Float,
        /** Circular standard deviation of the samples themselves. */
        val stdDevRad: Float,
        /** Resultant length, 0..1. 1 means perfect agreement, 0 means uniformly scattered. */
        val concentration: Float,
        val count: Int,
    )

    /** Signed smallest angle from [b] to [a], in (-pi, pi]. */
    fun difference(a: Float, b: Float): Float = normalize(a - b)

    fun normalize(angle: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var x = angle % twoPi
        if (x > Math.PI) x -= twoPi
        if (x <= -Math.PI) x += twoPi
        return x
    }

    /**
     * Resultant length: the magnitude of the mean unit vector. This is the honest measure of
     * agreement — it falls to zero for scattered samples no matter how many there are, which
     * is what stops a long walk through a distorted building from looking confident.
     */
    fun concentration(angles: List<Float>): Float {
        if (angles.isEmpty()) return 0f
        var sumSin = 0.0
        var sumCos = 0.0
        angles.forEach { sumSin += sin(it.toDouble()); sumCos += cos(it.toDouble()) }
        return (hypot(sumSin, sumCos) / angles.size).toFloat().coerceIn(0f, 1f)
    }

    fun mean(angles: List<Float>): Float {
        var sumSin = 0.0
        var sumCos = 0.0
        angles.forEach { sumSin += sin(it.toDouble()); sumCos += cos(it.toDouble()) }
        return atan2(sumSin, sumCos).toFloat()
    }

    /** Circular standard deviation, sqrt(-2 ln R). Diverges as agreement vanishes. */
    fun stdDev(angles: List<Float>): Float {
        val r = concentration(angles).coerceIn(1e-6f, 1f)
        return sqrt(-2.0 * ln(r.toDouble())).toFloat()
    }

    fun estimate(angles: List<Float>): Estimate? {
        if (angles.isEmpty()) return null
        return Estimate(
            meanRad = mean(angles),
            stdDevRad = stdDev(angles),
            concentration = concentration(angles),
            count = angles.size,
        )
    }

    /**
     * One trimming pass: drop samples further than [sigmas] from the mean and recompute.
     *
     * Removes the reading taken while walking past a filing cabinet without throwing away
     * the genuine spread. Returns the original estimate when trimming would leave too few
     * samples to be meaningful.
     */
    fun trimmedEstimate(
        angles: List<Float>,
        sigmas: Float = 2.5f,
        minRetained: Int = 8,
    ): Estimate? {
        val initial = estimate(angles) ?: return null
        if (angles.size < minRetained * 2 || initial.stdDevRad <= 0f) return initial

        val kept = angles.filter {
            abs(difference(it, initial.meanRad)) <= sigmas * initial.stdDevRad
        }
        if (kept.size < minRetained) return initial
        return estimate(kept)
    }

    /**
     * Standard error of the mean, floored.
     *
     * Magnetometer samples taken metres apart in the same building share whatever is
     * distorting the field, so they do not average down the way independent samples would.
     * The floor stops a long walk from claiming a precision it has not earned.
     */
    fun standardError(estimate: Estimate, floorRad: Float): Float =
        (estimate.stdDevRad / sqrt(estimate.count.toFloat())).coerceAtLeast(floorRad)
}
