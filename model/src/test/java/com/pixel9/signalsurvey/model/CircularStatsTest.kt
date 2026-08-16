package com.pixel9.signalsurvey.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The heading estimate is only as trustworthy as this arithmetic, and angle wrap-around is
 * where it goes wrong silently: the arithmetic mean of 350 and 10 degrees is 180, which is
 * a perfectly plausible number pointing exactly the wrong way.
 */
class CircularStatsTest {

    private fun deg(vararg values: Float) = values.map { Math.toRadians(it.toDouble()).toFloat() }
    private fun asDeg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

    @Test
    fun `mean survives the wrap at north`() {
        // The case a naive average gets exactly backwards.
        val mean = asDeg(CircularStats.mean(deg(350f, 355f, 5f, 10f)))
        assertEquals(0f, CircularStats.normalize(Math.toRadians(mean.toDouble()).toFloat()), 0.01f)
    }

    @Test
    fun `mean of tight cluster is the cluster`() {
        val mean = asDeg(CircularStats.mean(deg(42f, 44f, 46f, 48f)))
        assertEquals(45f, mean, 0.5f)
    }

    @Test
    fun `difference takes the short way round`() {
        assertEquals(-20f, asDeg(CircularStats.difference(deg(350f)[0], deg(10f)[0])), 0.01f)
        assertEquals(20f, asDeg(CircularStats.difference(deg(10f)[0], deg(350f)[0])), 0.01f)
    }

    @Test
    fun `concentration falls as samples scatter`() {
        val tight = CircularStats.concentration(deg(44f, 45f, 46f))
        val loose = CircularStats.concentration(deg(0f, 45f, 90f, 135f))
        val scattered = CircularStats.concentration(deg(0f, 90f, 180f, 270f))

        assertTrue("tight cluster should be near 1, was $tight", tight > 0.99f)
        assertTrue("loose should sit between", loose in 0.3f..0.9f)
        // Four evenly spaced directions carry no directional information at all.
        assertTrue("evenly spaced should be ~0, was $scattered", scattered < 0.01f)
    }

    @Test
    fun `uncertainty grows with disagreement`() {
        val tight = CircularStats.stdDev(deg(44f, 45f, 46f))
        val loose = CircularStats.stdDev(deg(20f, 45f, 70f))
        assertTrue("spread samples must report more uncertainty", loose > tight)
        // A degree or two of scatter should not report tens of degrees of error.
        assertTrue("tight cluster over-reported: ${asDeg(tight)} deg", asDeg(tight) < 3f)
    }

    @Test
    fun `trimming removes the filing cabinet sample`() {
        // Sixteen good samples around 45 degrees, plus one taken next to something ferrous.
        val good = (0 until 16).map { Math.toRadians(45.0 + (it % 4) - 1.5).toFloat() }
        val withOutlier = good + deg(160f)

        val untrimmed = CircularStats.estimate(withOutlier)!!
        val trimmed = CircularStats.trimmedEstimate(withOutlier, sigmas = 2.5f, minRetained = 8)!!

        assertTrue("outlier should have been dropped", trimmed.count < untrimmed.count)
        assertEquals("trimmed mean should return to the cluster", 45f, asDeg(trimmed.meanRad), 2f)
        assertTrue(
            "trimmed estimate should be tighter",
            trimmed.stdDevRad < untrimmed.stdDevRad,
        )
    }

    @Test
    fun `trimming refuses to run on too few samples`() {
        val few = deg(44f, 45f, 46f, 120f)
        val result = CircularStats.trimmedEstimate(few, minRetained = 8)
        assertNotNull(result)
        // With only four samples there is no basis to call one an outlier.
        assertEquals(4, result!!.count)
    }

    @Test
    fun `standard error is floored so long walks cannot overclaim`() {
        val manyIdentical = List(400) { Math.toRadians(45.0).toFloat() }
        val estimate = CircularStats.estimate(manyIdentical)!!
        val floor = 0.035f      // ~2 degrees
        val error = CircularStats.standardError(estimate, floor)
        assertEquals(
            "400 correlated samples must not average down to zero error",
            floor, error, 1e-6f,
        )
    }

    @Test
    fun `normalize keeps everything in the half open turn`() {
        listOf(0f, 3.14f, -3.14f, 7f, -7f, 100f, -100f).forEach { raw ->
            val n = CircularStats.normalize(raw)
            assertTrue("$raw normalised to $n, outside (-pi, pi]", n > -Math.PI && n <= Math.PI + 1e-6)
        }
    }

    @Test
    fun `sky projection round trips through the world frame`() {
        // With a known north offset, a satellite due north must come back out due north.
        val northYaw = 0.7f
        val direction = SkyProjection.directionToWorld(
            azimuthDegFromNorth = 0f, elevationDeg = 0f, trueNorthYawRad = northYaw,
        )
        val recoveredWorldYaw = kotlin.math.atan2(direction.x, -direction.z)
        val recoveredBearing = CircularStats.normalize(recoveredWorldYaw + northYaw)
        assertTrue(
            "due north did not round trip: ${asDeg(recoveredBearing)} deg",
            abs(asDeg(recoveredBearing)) < 0.5f,
        )
    }

    @Test
    fun `uncertainty arc spans the error band and is centred`() {
        val arc = SkyProjection.uncertaintyArc(
            origin = Vec3.ZERO,
            azimuthDegFromNorth = 90f,
            elevationDeg = 30f,
            trueNorthYawRad = 0f,
            uncertaintyRad = Math.toRadians(10.0).toFloat(),
            steps = 9,
        )
        assertEquals(9, arc.size)

        val centre = SkyProjection.worldPoint(Vec3.ZERO, 90f, 30f, 0f)
        // The middle sample of the arc is the unmodified direction.
        assertTrue(
            "arc should be centred on the nominal direction",
            arc[4].distanceTo(centre) < 1f,
        )
        // The ends are further out than the middle.
        assertTrue(arc.first().distanceTo(centre) > arc[3].distanceTo(centre))
    }
}
