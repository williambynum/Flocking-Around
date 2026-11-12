package com.pixel9.signalsurvey.fusion

import android.graphics.Rect
import com.pixel9.signalsurvey.model.CameraSnapshot
import com.pixel9.signalsurvey.model.ConfirmedSignal
import com.pixel9.signalsurvey.model.PositionMethod
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.RangeSource
import com.pixel9.signalsurvey.model.RssiSample
import com.pixel9.signalsurvey.model.RttFix
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionResolverTest {

    // --------------------------------------------------------------- fixtures

    private fun camera(at: Vec3 = Vec3.ZERO) = CameraSnapshot(
        worldPosition = at,
        orientation = floatArrayOf(0f, 0f, 0f, 1f),
        viewMatrix = FloatArray(16),
        projMatrix = FloatArray(16),
        zNear = 0.1f,
        zFar = 100f,
        focalPx = floatArrayOf(1000f, 1000f),
        principalPx = floatArrayOf(540f, 960f),
        horizontalFovDeg = 65f,
        trueNorthYawRad = null,
    )

    private fun target(
        id: Int,
        label: String,
        at: Vec3,
        rangeM: Float? = 2f,
        confirmed: List<ConfirmedSignal> = emptyList(),
    ) = VisualTarget(
        id = id,
        trackingId = null,
        label = label,
        displayName = label,
        visualConfidence = 0.8f,
        boxImagePx = Rect(),
        anchorWorld = at,
        rangeM = rangeM,
        rangeSource = if (rangeM != null) RangeSource.AR_DEPTH else RangeSource.ASSUMED,
        bearingDeg = 0f,
        elevationDeg = 0f,
        confirmed = confirmed,
    )

    private fun shot(index: Int, elapsedMs: Long = index * 20_000L, vararg targets: VisualTarget) = Shot(
        index = index,
        capturedAtEpochMs = 0L,
        elapsedMs = elapsedMs,
        imagePath = null,
        widthPx = 1080,
        heightPx = 1920,
        camera = camera(),
        depth = null,
        targets = targets.toList(),
    )

    private fun wifi(key: String, rssi: Int = -55, firstMs: Long = 0L, lastMs: Long = 100_000L) =
        RadioObservation(
            key = key,
            family = RadioFamily.WIFI,
            displayName = "net-$key",
            rssiDbm = rssi,
            standard = "802.11ax",
            bandLabel = "5 GHz",
            firstSeenElapsedMs = firstMs,
            lastSeenElapsedMs = lastMs,
        )

    private fun rttFixes(key: String, emitter: Vec3, cameras: List<Vec3>) =
        cameras.mapIndexed { i, cam ->
            RttFix(key, i * 1_000L, cam.distanceTo(emitter), 0.2f, -50, cam, i + 1)
        }

    private val corners = listOf(
        Vec3(0f, 1.4f, 0f), Vec3(4f, 1.4f, 0f), Vec3(4f, 1.4f, 3f), Vec3(0f, 1.4f, 3f),
    )

    private fun resolve(
        observations: List<RadioObservation>,
        fixes: List<RttFix> = emptyList(),
        samples: List<RssiSample> = emptyList(),
        shots: List<Shot> = emptyList(),
        targets: List<VisualTarget> = emptyList(),
    ) = SessionResolver.resolveEmitters(
        observations.associateBy { it.key }, fixes, samples, shots, targets,
    )

    // ---------------------------------------------------------- target merging

    @Test
    fun `the same device seen from two shots becomes one target`() {
        val assignments = SessionResolver.mergeTargets(
            listOf(
                shot(1, targets = arrayOf(target(1, "wireless_router", Vec3(1f, 1f, -2f)))),
                shot(2, targets = arrayOf(target(2, "wireless_router", Vec3(1.2f, 1f, -2.1f)))),
            )
        )
        assertEquals(1, assignments[1])
        assertEquals(1, assignments[2])
    }

    @Test
    fun `different devices in the same place stay separate`() {
        val assignments = SessionResolver.mergeTargets(
            listOf(
                shot(1, targets = arrayOf(target(1, "wireless_router", Vec3(1f, 1f, -2f)))),
                shot(2, targets = arrayOf(target(2, "smart_speaker", Vec3(1.1f, 1f, -2f)))),
            )
        )
        assertNotEquals(assignments[1], assignments[2])
    }

    @Test
    fun `an unrecognised sighting merges with a recognised one at the same spot`() {
        val assignments = SessionResolver.mergeTargets(
            listOf(
                shot(1, targets = arrayOf(target(1, "unknown_device", Vec3(1f, 1f, -2f)))),
                shot(2, targets = arrayOf(target(2, "printer", Vec3(1.1f, 1f, -2f)))),
            )
        )
        assertEquals(assignments[1], assignments[2])
    }

    @Test
    fun `two of the same thing a desk-width apart are not merged at close range`() {
        val assignments = SessionResolver.mergeTargets(
            listOf(
                shot(
                    1,
                    targets = arrayOf(
                        target(1, "laptop", Vec3(0f, 1f, -1f), rangeM = 1f),
                        target(2, "laptop", Vec3(0.8f, 1f, -1f), rangeM = 1f),
                    ),
                ),
            )
        )
        assertNotEquals(assignments[1], assignments[2])
    }

    @Test
    fun `the merge radius widens with distance`() {
        // 1.5 m apart is two objects at arm's length but one object at 20 m, where the
        // anchor error alone is that large.
        val assignments = SessionResolver.mergeTargets(
            listOf(
                shot(1, targets = arrayOf(target(1, "cell_tower", Vec3(0f, 5f, -20f), rangeM = 20f))),
                shot(2, targets = arrayOf(target(2, "cell_tower", Vec3(1.5f, 5f, -20f), rangeM = 20f))),
            )
        )
        assertEquals(assignments[1], assignments[2])
    }

    // -------------------------------------------------------- emitter resolution

    @Test
    fun `three ranges from distinct positions trilaterate an emitter`() {
        val emitter = Vec3(1f, 1.4f, 2f)
        val resolved = resolve(
            observations = listOf(wifi("AP")),
            fixes = rttFixes("AP", emitter, corners),
        ).single()

        assertEquals(PositionMethod.RTT_TRILATERATION, resolved.method)
        assertEquals(0f, resolved.worldPosition!!.distanceTo(emitter), 0.1f)
        assertEquals(4, resolved.fixCount)
    }

    @Test
    fun `a single range is reported as a ring and never as a point`() {
        val resolved = resolve(
            observations = listOf(wifi("AP")),
            fixes = rttFixes("AP", Vec3(1f, 1.4f, 2f), corners.take(1)),
        ).single()

        assertEquals(PositionMethod.SINGLE_RANGE_RING, resolved.method)
        assertNull(resolved.worldPosition)
        assertFalse(resolved.isLocated)
    }

    @Test
    fun `ranges that cannot be reconciled leave the emitter unlocated`() {
        // Ranges that fit no point at all — multipath, or two radios sharing a key.
        val impossible = corners.mapIndexed { i, cam ->
            RttFix("AP", i * 1_000L, if (i % 2 == 0) 0.5f else 25f, 0.2f, -50, cam, i + 1)
        }
        val resolved = resolve(listOf(wifi("AP")), fixes = impossible).single()

        assertEquals(PositionMethod.UNLOCATED, resolved.method)
        assertNull(resolved.worldPosition)
    }

    @Test
    fun `an emitter that was only heard is unlocated`() {
        val resolved = resolve(listOf(wifi("AP"))).single()

        assertEquals(PositionMethod.UNLOCATED, resolved.method)
        assertEquals("heard, not located", resolved.confidenceLabel)
    }

    @Test
    fun `a visual anchor outranks trilateration`() {
        val anchor = Vec3(9f, 1f, 9f)
        val observation = wifi("AP")
        val seen = target(
            7, "wireless_router", anchor,
            confirmed = listOf(ConfirmedSignal(observation, 0.9f, listOf("MAC prefix"))),
        )
        val resolved = resolve(
            observations = listOf(observation),
            fixes = rttFixes("AP", Vec3(1f, 1.4f, 2f), corners),
            targets = listOf(seen),
        ).single()

        assertEquals(PositionMethod.VISUAL_ANCHOR, resolved.method)
        assertEquals(anchor, resolved.worldPosition)
        assertEquals(7, resolved.visualTargetId)
    }

    @Test
    fun `rtt fixes for one emitter are not used for another`() {
        val resolved = resolve(
            observations = listOf(wifi("AP-1"), wifi("AP-2")),
            fixes = rttFixes("AP-1", Vec3(1f, 1.4f, 2f), corners),
        ).associateBy { it.key }

        assertTrue(resolved.getValue("AP-1").isLocated)
        assertFalse(resolved.getValue("AP-2").isLocated)
    }

    @Test
    fun `located emitters sort ahead of unlocated ones, then by signal strength`() {
        val resolved = resolve(
            observations = listOf(
                wifi("LOUD", rssi = -30),
                wifi("QUIET", rssi = -80),
                wifi("PLACED", rssi = -70),
            ),
            fixes = rttFixes("PLACED", Vec3(1f, 1.4f, 2f), corners),
        )
        assertEquals(listOf("PLACED", "LOUD", "QUIET"), resolved.map { it.key })
    }

    @Test
    fun `an emitter is listed only against shots taken while it was audible`() {
        val shots = listOf(shot(1, elapsedMs = 10_000L), shot(2, elapsedMs = 60_000L), shot(3, elapsedMs = 120_000L))
        val heardEarly = wifi("AP", firstMs = 5_000L, lastMs = 20_000L)

        val resolved = resolve(listOf(heardEarly), shots = shots).single()

        assertEquals(listOf(1), resolved.seenInShots)
    }
}
