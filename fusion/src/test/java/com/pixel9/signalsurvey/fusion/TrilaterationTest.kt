package com.pixel9.signalsurvey.fusion

import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.log10

/**
 * Ground truth for the solvers, since a tape measure is not available on the JVM.
 *
 * Every case places an emitter at a known point, synthesises the ranges a phone would have
 * measured from a known walk, and checks the answer against the point it started from. The
 * walks are deliberately the shapes a person actually produces: hand-held, so nearly level,
 * and often along a wall or a corridor.
 */
class TrilaterationTest {

    private val handHeight = 1.4f

    private fun fixes(
        emitter: Vec3,
        cameras: List<Vec3>,
        noiseM: Float = 0f,
        seed: Long = 7L,
    ): List<RttFix> {
        val random = Random(seed)
        return cameras.mapIndexed { i, camera ->
            RttFix(
                key = "AA:BB:CC:DD:EE:FF",
                elapsedMs = i * 1_000L,
                distanceM = camera.distanceTo(emitter) + (random.nextGaussian() * noiseM).toFloat(),
                stdDevM = noiseM,
                rssiDbm = -50,
                cameraWorld = camera,
                shotIndex = i + 1,
            )
        }
    }

    private fun level(vararg xz: Pair<Float, Float>) = xz.map { Vec3(it.first, handHeight, it.second) }

    /** A walk around a room: four corners, all at hand height. */
    private val roomWalk = level(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f)

    // ------------------------------------------------------------------ RTT

    @Test
    fun `recovers an emitter level with the phone`() {
        val emitter = Vec3(1f, handHeight, 2.2f)
        val solution = Trilateration.solveFromRtt(fixes(emitter, roomWalk))!!

        assertEquals(0f, solution.position.distanceTo(emitter), 0.05f)
        assertEquals(0f, solution.residualM, 0.05f)
        assertEquals(4, solution.usedMeasurements)
    }

    @Test
    fun `places a ceiling access point correctly in plan from a level walk`() {
        // The common indoor case: the AP is well above the phone and the walk has no vertical
        // spread to see that with. Its height is unobservable in sign, but its plan position
        // is fully determined — and the ranges must not be treated as horizontal distances.
        val emitter = Vec3(0.5f, handHeight + 1.5f, 0.6f)
        val solution = Trilateration.solveFromRtt(fixes(emitter, roomWalk))!!

        assertEquals(0f, solution.position.planarDistanceTo(emitter), 0.15f)
        assertTrue("residual ${solution.residualM}", solution.residualM < 0.15f)
        assertTrue(solution.planarOnly)
    }

    @Test
    fun `a planar solve does not claim a height it cannot know`() {
        val emitter = Vec3(0.5f, handHeight + 1.5f, 0.6f)
        val solution = Trilateration.solveFromRtt(fixes(emitter, roomWalk))!!

        // Above or below is a mirror ambiguity, so the answer stays at the walk's own height.
        assertEquals(handHeight, solution.position.y, 0.01f)
    }

    @Test
    fun `solves in three dimensions when the walk has vertical spread`() {
        val emitter = Vec3(1.5f, 2.6f, -2f)
        val cameras = listOf(
            Vec3(0f, 0.6f, 0f), Vec3(4f, 1.4f, 0f), Vec3(4f, 2.0f, 3f),
            Vec3(0f, 1.2f, 3f), Vec3(2f, 0.4f, 1.5f),
        )
        val solution = Trilateration.solveFromRtt(fixes(emitter, cameras))!!

        assertFalse(solution.planarOnly)
        assertEquals(0f, solution.position.distanceTo(emitter), 0.1f)
    }

    @Test
    fun `stays close under realistic ranging noise and reports an honest residual`() {
        val emitter = Vec3(1f, handHeight, 2.2f)
        val cameras = level(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f, 2f to -1f, 5f to 1.5f)
        val solution = Trilateration.solveFromRtt(fixes(emitter, cameras, noiseM = 0.3f))!!

        assertTrue(
            "error ${solution.position.distanceTo(emitter)}",
            solution.position.distanceTo(emitter) < 1.0f,
        )
        // Noise in must show up as residual out: a zero here would be a false claim of precision.
        assertTrue("residual ${solution.residualM}", solution.residualM in 0.05f..1.0f)
    }

    @Test
    fun `refuses a straight-line walk because the emitter could be on either side`() {
        // Walking down a corridor. Every range is equally consistent with the emitter being
        // to the left or to the right, so any single point would be a coin flip.
        val emitter = Vec3(2f, handHeight, 3f)
        val corridor = level(0f to 0f, 1.5f to 0f, 3f to 0f, 4.5f to 0f)

        assertNull(Trilateration.solveFromRtt(fixes(emitter, corridor)))
    }

    @Test
    fun `accepts a walk that bends even slightly more than tracking jitter`() {
        val emitter = Vec3(2f, handHeight, 3f)
        val dogleg = level(0f to 0f, 1.5f to 0f, 3f to 0.2f, 4.5f to 1.2f)
        val solution = Trilateration.solveFromRtt(fixes(emitter, dogleg))!!

        assertEquals(0f, solution.position.planarDistanceTo(emitter), 0.2f)
    }

    @Test
    fun `needs three distinct standing positions`() {
        val emitter = Vec3(1f, handHeight, 2f)
        assertNull(Trilateration.solveFromRtt(fixes(emitter, level(0f to 0f, 4f to 0f))))
        assertNull(Trilateration.solveFromRtt(emptyList()))
    }

    @Test
    fun `repeated ranges from one spot count once`() {
        val emitter = Vec3(1f, handHeight, 2f)
        // Ten measurements, but only two places: still a circle of answers, not a point.
        val cameras = List(5) { Vec3(0f, handHeight, 0.01f * it) } +
            List(5) { Vec3(4f, handHeight, 0.01f * it) }

        assertNull(Trilateration.solveFromRtt(fixes(emitter, cameras)))
    }

    @Test
    fun `refuses positions too close together to triangulate from`() {
        val emitter = Vec3(3f, handHeight, 3f)
        val shuffle = level(0f to 0f, 0.45f to 0f, 0f to 0.45f)

        assertNull(Trilateration.solveFromRtt(fixes(emitter, shuffle)))
    }

    // ----------------------------------------------------------------- RSSI

    private fun rssiSamples(emitter: Vec3, cameras: List<Vec3>, exponent: Double = 2.7) =
        cameras.mapIndexed { i, camera ->
            val d = camera.distanceTo(emitter).coerceAtLeast(0.3f).toDouble()
            RssiSample("beacon", i * 500L, (-40.0 - 10.0 * exponent * log10(d)).toInt(), camera)
        }

    /** A loop around a room, sampled every half metre or so. */
    private val loop: List<Vec3> = buildList {
        for (i in 0..8) add(Vec3(i * 0.5f, handHeight, 0f))
        for (i in 1..6) add(Vec3(4f, handHeight, i * 0.5f))
        for (i in 1..8) add(Vec3(4f - i * 0.5f, handHeight, 3f))
        for (i in 1..5) add(Vec3(0f, handHeight, 3f - i * 0.5f))
    }

    @Test
    fun `rssi search lands near an emitter inside the walked loop`() {
        val emitter = Vec3(1.2f, handHeight, 1.8f)
        val solution = Trilateration.solveFromRssi(rssiSamples(emitter, loop))!!

        // Whole-dBm quantisation alone costs accuracy; this is the coarse method and the
        // assertion is correspondingly loose.
        assertTrue(
            "error ${solution.position.planarDistanceTo(emitter)}",
            solution.position.planarDistanceTo(emitter) < 1.5f,
        )
        assertEquals(loop.size, solution.usedMeasurements)
    }

    @Test
    fun `rssi search never reports an error bar tighter than half a metre`() {
        val emitter = Vec3(1.2f, handHeight, 1.8f)
        val solution = Trilateration.solveFromRssi(rssiSamples(emitter, loop))!!

        assertTrue(solution.residualM >= 0.5f)
    }

    @Test
    fun `rssi search needs enough samples and enough movement`() {
        val emitter = Vec3(1f, handHeight, 1f)
        assertNull(Trilateration.solveFromRssi(rssiSamples(emitter, loop.take(5))))

        val standingStill = List(20) { Vec3(0.01f * it, handHeight, 0f) }
        assertNull(Trilateration.solveFromRssi(rssiSamples(emitter, standingStill)))
    }

    @Test
    fun `rssi search copes with a constant signal`() {
        // Every sample identical: no gradient at all. Must not crash or return NaN.
        val flat = loop.mapIndexed { i, camera -> RssiSample("beacon", i * 500L, -60, camera) }
        val solution = Trilateration.solveFromRssi(flat)

        assertNotNull(solution)
        assertFalse(solution!!.position.x.isNaN() || solution.residualM.isNaN())
    }
}
