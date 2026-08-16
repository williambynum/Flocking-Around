package com.pixel9.signalsurvey.export

import android.graphics.Rect
import com.pixel9.signalsurvey.model.CameraSnapshot
import com.pixel9.signalsurvey.model.ConfirmedSignal
import com.pixel9.signalsurvey.model.GeoFix
import com.pixel9.signalsurvey.model.InferredSignal
import com.pixel9.signalsurvey.model.PathPoint
import com.pixel9.signalsurvey.model.PositionMethod
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.RangeSource
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.SatelliteFix
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.SignalCatalog
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Generates a full export bundle from a synthetic survey and writes it to
 * `export/build/sample-report/` so the output can actually be looked at.
 *
 * The assertions guard the things that are easy to break silently: HTML escaping of
 * hostile SSIDs, CSV quoting of fields containing commas, and the measured/inferred
 * distinction surviving into every format.
 */
class SampleReportTest {

    @Test
    fun `generates a readable bundle`() {
        val session = sampleSession()

        val html = ReportBuilder.html(
            session,
            shotImageNames = mapOf(1 to "shot_01.jpg", 2 to "shot_02.jpg"),
            planImageName = "plan_view.png",
        )
        val emitters = ReportBuilder.emittersCsv(session)
        val devices = ReportBuilder.devicesCsv(session)
        val summary = ReportBuilder.summaryText(session)

        val out = File("build/sample-report").apply { mkdirs() }
        File(out, "report.html").writeText(html)
        File(out, "emitters.csv").writeText(emitters)
        File(out, "devices.csv").writeText(devices)
        File(out, "summary.txt").writeText(summary)
        println("Sample bundle written to ${out.absolutePath}")

        // An SSID is attacker-controlled text that lands straight in the report.
        assertTrue("script tag must be escaped", html.contains("&lt;script&gt;"))
        assertFalse("raw script tag leaked into the report", html.contains("<script>alert"))

        // A field containing a comma must be quoted or every column after it shifts.
        assertTrue(
            "comma-bearing field must open with a quote",
            emitters.contains("\"Lab AP, north wall "),
        )
        // An embedded quote must be doubled.
        assertTrue("inner quotes must be doubled", emitters.contains("\"\"guest\"\""))

        // The provenance distinction has to survive into every rendering.
        assertTrue(html.contains("pill measured"))
        assertTrue(html.contains("pill inferred"))
        assertTrue(devices.contains("MEASURED"))
        assertTrue(devices.contains("INFERRED"))
        assertTrue(summary.contains("[MEASURED]"))
        assertTrue(summary.contains("[INFERRED]"))

        // The reason an inferred signal could not be confirmed must be carried through,
        // not silently dropped.
        assertTrue(summary.contains("No 802.15.4 receiver"))

        // CSV must be one header plus one row per emitter.
        val emitterRows = emitters.trim().split("\r\n")
        assertTrue("expected a header and 4 rows", emitterRows.size == 5)
    }

    // ------------------------------------------------------------------ fixtures

    private fun camera(position: Vec3) = CameraSnapshot(
        worldPosition = position,
        orientation = floatArrayOf(0f, 0f, 0f, 1f),
        viewMatrix = FloatArray(16),
        projMatrix = FloatArray(16),
        zNear = 0.1f,
        zFar = 200f,
        focalPx = floatArrayOf(1400f, 1400f),
        principalPx = floatArrayOf(640f, 480f),
        horizontalFovDeg = 66f,
        trueNorthYawRad = 0.31f,
    )

    private fun observation(
        key: String,
        family: RadioFamily,
        name: String,
        standard: String,
        band: String,
        rssi: Int,
        vendor: String? = null,
        measuredRange: Float? = null,
        extras: Map<String, String> = emptyMap(),
    ) = RadioObservation(
        key = key,
        family = family,
        displayName = name,
        vendor = vendor,
        rssiDbm = rssi,
        measuredRangeM = measuredRange,
        measuredRangeStdDevM = measuredRange?.let { 0.42f },
        estimatedRangeM = 7.4f,
        standard = standard,
        bandLabel = band,
        freqHz = 5_180_000_000L,
        firstSeenElapsedMs = 1_200,
        lastSeenElapsedMs = 41_800,
        sightings = 23,
        extras = extras,
    )

    private fun sampleSession(): SurveySession {
        val router = observation(
            key = "A0:63:91:2C:14:8E",
            family = RadioFamily.WIFI,
            // Deliberately hostile: a comma, quotes and an HTML tag in one SSID.
            name = "Lab AP, north wall \"guest\" <script>alert(1)</script>",
            standard = "802.11be (Wi-Fi 7)",
            band = "6 GHz (Wi-Fi 6E/7)",
            rssi = -41,
            vendor = "Netgear",
            measuredRange = 3.14f,
            extras = mapOf(
                "channel" to "37",
                "channelWidth" to "160 MHz",
                "security" to "WPA3-SAE",
                "rttResponder" to "true",
                "mloLinks" to "2.4 GHz ch6, 5 GHz ch44, 6 GHz ch37",
            ),
        )
        val tracker = observation(
            key = "4C:A1:6B:03:9F:22",
            family = RadioFamily.BLUETOOTH,
            name = "Tile tracker",
            standard = "Bluetooth LE 5.x (extended adv)",
            band = "2.4 GHz",
            rssi = -73,
            vendor = "Tile",
            extras = mapOf(
                "phy" to "LE 1M + LE 2M",
                "services" to "0000feed-0000-1000-8000-00805f9b34fb",
                "addressRandom" to "true",
            ),
        )
        val cell = observation(
            key = "nr:118273645",
            family = RadioFamily.CELLULAR,
            name = "5G NR cell",
            standard = "5G NR (sub-6)",
            band = "n78",
            rssi = -88,
            extras = mapOf("registered" to "true", "pci" to "413", "band" to "n78"),
        )
        val cast = observation(
            key = "mdns:Living Room TV:_googlecast._tcp",
            family = RadioFamily.NETWORK_SERVICE,
            name = "Living Room TV",
            standard = "mDNS / DNS-SD",
            band = "over Wi-Fi (client)",
            rssi = -60,
            vendor = "Google",
            extras = mapOf("serviceType" to "_googlecast._tcp"),
        )

        val target = VisualTarget(
            id = 1,
            trackingId = 7,
            label = "wireless_router",
            displayName = "Wi-Fi Router / Gateway",
            visualConfidence = 0.87f,
            boxImagePx = Rect(412, 880, 690, 1050),
            anchorWorld = Vec3(1.24f, -0.31f, -3.02f),
            rangeM = 3.14f,
            rangeSource = RangeSource.WIFI_RTT,
            bearingDeg = -8.4f,
            elevationDeg = 3.1f,
            confirmed = listOf(
                ConfirmedSignal(
                    observation = router,
                    score = 0.92f,
                    evidence = listOf(
                        "MAC prefix A0:63:91 matches this device class",
                        "Measured range 3.1 m agrees with the visual 3.1 m",
                    ),
                )
            ),
            inferred = listOf(
                InferredSignal(
                    profile = SignalCatalog.ZIGBEE,
                    prior = 0.15f,
                    reason = "No 802.15.4 receiver on any Pixel phone - cannot be verified",
                ),
                InferredSignal(
                    profile = SignalCatalog.DECT,
                    prior = 0.10f,
                    reason = "No receiver",
                ),
            ),
            seenInShots = listOf(1, 2),
        )

        val shots = listOf(
            Shot(
                index = 1,
                capturedAtEpochMs = 1_760_000_000_000,
                elapsedMs = 18_400,
                imagePath = null,
                widthPx = 1080,
                heightPx = 2400,
                camera = camera(Vec3(0f, 0f, 0f)),
                depth = null,
                targets = listOf(target),
            ),
            Shot(
                index = 2,
                capturedAtEpochMs = 1_760_000_042_000,
                elapsedMs = 60_500,
                imagePath = null,
                widthPx = 1080,
                heightPx = 2400,
                camera = camera(Vec3(2.6f, 0.1f, -1.4f)),
                depth = null,
                targets = emptyList(),
            ),
        )

        val emitters = listOf(
            ResolvedEmitter(
                key = router.key,
                observation = router,
                worldPosition = Vec3(1.24f, -0.31f, -3.02f),
                positionErrorM = 0.38f,
                method = PositionMethod.RTT_TRILATERATION,
                seenInShots = listOf(1, 2),
                visualTargetId = 1,
                fixCount = 4,
            ),
            ResolvedEmitter(
                key = cast.key,
                observation = cast,
                worldPosition = Vec3(-2.1f, 0.4f, -4.8f),
                positionErrorM = 4.2f,
                method = PositionMethod.RSSI_GRADIENT,
                seenInShots = listOf(1, 2),
                fixCount = 19,
            ),
            ResolvedEmitter(
                key = tracker.key,
                observation = tracker,
                worldPosition = null,
                positionErrorM = null,
                method = PositionMethod.UNLOCATED,
                seenInShots = listOf(2),
                fixCount = 0,
            ),
            ResolvedEmitter(
                key = cell.key,
                observation = cell,
                worldPosition = null,
                positionErrorM = null,
                method = PositionMethod.UNLOCATED,
                seenInShots = listOf(1, 2),
                fixCount = 0,
            ),
        )

        return SurveySession(
            id = "sample-0001",
            label = "Office floor 3",
            startedAtEpochMs = 1_759_999_940_000,
            endedAtEpochMs = 1_760_000_060_000,
            deviceProfile = "Pixel 9 (caiman, Android 15) - Wi-Fi, Wi-Fi RTT, BLE, " +
                "Cellular, GNSS, NFC, no UWB",
            shots = shots,
            observations = listOf(router, tracker, cell, cast).associateBy { it.key },
            rssiSamples = emptyList(),
            rttFixes = emptyList(),
            emitters = emitters,
            cameraPath = listOf(
                PathPoint(0, Vec3(0f, 0f, 0f)),
                PathPoint(30_000, Vec3(1.4f, 0.05f, -0.9f)),
                PathPoint(60_000, Vec3(2.6f, 0.1f, -1.4f)),
            ),
            satellites = listOf(
                SatelliteFix("Galileo", 21, 1_176_450_000, 44.1f, 132f, 61f, true),
                SatelliteFix("Galileo", 21, 1_575_420_000, 46.8f, 132f, 61f, true),
                SatelliteFix("GPS", 14, 1_575_420_000, 41.2f, 208f, 34f, true),
            ),
            location = GeoFix(51.5074, -0.1278, 24.0, 8.5f),
        )
    }
}
