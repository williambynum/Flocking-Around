package com.pixel9.signalsurvey.export

import com.pixel9.signalsurvey.model.CameraSnapshot
import com.pixel9.signalsurvey.model.RadioObservation
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import org.json.JSONArray
import org.json.JSONObject

/**
 * The machine-readable record.
 *
 * The annotated image is for people; this is what makes the app a survey instrument rather
 * than a novelty. Every number carries its provenance — how a range was measured, how a
 * position was solved, how many measurements are behind it — so a reader can disagree with a
 * conclusion without having to redo the walk.
 *
 * Platform `org.json` on purpose: no serialization plugin, no schema drift between the
 * exporter and the model.
 */
object SessionJson {

    const val SCHEMA_VERSION = 1

    fun encode(session: SurveySession, pretty: Boolean = true): String {
        val root = JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("sessionId", session.id)
            put("label", session.label)
            put("startedAtEpochMs", session.startedAtEpochMs)
            put("endedAtEpochMs", session.endedAtEpochMs ?: JSONObject.NULL)
            put("durationMs", session.durationMs)
            put("device", session.deviceProfile)
            put("pathLengthM", round(session.pathLengthM()))

            put("capabilityNote", CAPABILITY_NOTE)

            session.location?.let {
                put("location", JSONObject().apply {
                    put("lat", it.lat)
                    put("lon", it.lon)
                    put("altM", it.altM ?: JSONObject.NULL)
                    put("accuracyM", it.accuracyM)
                })
            }

            put("shots", JSONArray(session.shots.map { it.toJson() }))
            put("emitters", JSONArray(session.emitters.map { it.toJson() }))
            put("observations", JSONArray(session.observations.values.map { it.toJson() }))
            put("cameraPath", JSONArray(session.cameraPath.map { point ->
                JSONObject().apply {
                    put("elapsedMs", point.elapsedMs)
                    put("world", point.world.toJson())
                    point.shotIndex?.let { put("shotIndex", it) }
                }
            }))
            put("gnss", JSONArray(session.satellites.map { sat ->
                JSONObject().apply {
                    put("constellation", sat.constellation)
                    put("svid", sat.svid)
                    put("carrierFreqHz", sat.carrierFreqHz ?: JSONObject.NULL)
                    put("band", sat.bandLabel)
                    put("cn0DbHz", round(sat.cn0DbHz))
                    put("azimuthDeg", round(sat.azimuthDeg))
                    put("elevationDeg", round(sat.elevationDeg))
                    put("usedInFix", sat.usedInFix)
                }
            }))
        }
        return if (pretty) root.toString(2) else root.toString()
    }

    /** Compact per-shot record for the EXIF UserComment, so one JPEG stands alone. */
    fun encodeShot(session: SurveySession, shot: Shot): String =
        JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("sessionId", session.id)
            put("shotIndex", shot.index)
            put("capturedAtEpochMs", shot.capturedAtEpochMs)
            put("device", session.deviceProfile)
            put("camera", shot.camera.toJson())
            put("targets", JSONArray(shot.targets.map { it.toJson() }))
            put("capabilityNote", CAPABILITY_NOTE)
        }.toString()

    // ------------------------------------------------------------------ encoders

    private fun Shot.toJson() = JSONObject().apply {
        put("index", index)
        put("capturedAtEpochMs", capturedAtEpochMs)
        put("elapsedMs", elapsedMs)
        put("image", imagePath ?: JSONObject.NULL)
        put("widthPx", widthPx)
        put("heightPx", heightPx)
        put("depthCoverage", round(depthCoverage))
        put("camera", camera.toJson())
        put("targets", JSONArray(targets.map { it.toJson() }))
        note?.let { put("note", it) }
    }

    private fun CameraSnapshot.toJson() = JSONObject().apply {
        put("worldPosition", worldPosition.toJson())
        put("orientation", JSONArray(orientation.map { round(it) }))
        put("viewMatrix", JSONArray(viewMatrix.map { round(it, 6) }))
        put("projMatrix", JSONArray(projMatrix.map { round(it, 6) }))
        put("zNear", zNear)
        put("zFar", zFar)
        put("focalPx", JSONArray(focalPx.map { round(it) }))
        put("principalPx", JSONArray(principalPx.map { round(it) }))
        put("horizontalFovDeg", round(horizontalFovDeg))
        put("trueNorthYawRad", trueNorthYawRad?.let { round(it, 5) } ?: JSONObject.NULL)
        // Measured, not assumed: the circular variance of the magnetometer samples taken
        // along the sweep. Null means no usable heading was resolved for this shot, in
        // which case nothing in it is expressed against true north.
        put(
            "trueNorthUncertaintyRad",
            trueNorthUncertaintyRad?.let { round(it, 5) } ?: JSONObject.NULL,
        )
        put(
            "trueNorthUncertaintyDeg",
            trueNorthUncertaintyRad?.let { round(Math.toDegrees(it.toDouble()).toFloat(), 2) }
                ?: JSONObject.NULL,
        )
    }

    private fun VisualTarget.toJson() = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("displayName", displayName)
        put("visualConfidence", round(visualConfidence))
        put("identification", JSONObject().apply {
            put("source", identification.name)
            put("label", identification.label)
            // The distinction a reader needs: was an image uploaded to produce this name?
            put("onDevice", identification.onDevice)
        })
        put("boxPx", JSONArray(listOf(boxImagePx.left, boxImagePx.top, boxImagePx.right, boxImagePx.bottom)))
        put("anchorWorld", anchorWorld.toJson())
        put("range", JSONObject().apply {
            put("valueM", rangeM?.let { round(it) } ?: JSONObject.NULL)
            put("source", rangeSource.name)
            put("isMeasured", rangeSource.isMeasured)
        })
        put("bearingDeg", round(bearingDeg))
        put("elevationDeg", round(elevationDeg))
        put("seenInShots", JSONArray(seenInShots))
        put("measured", JSONArray(confirmed.map { signal ->
            JSONObject().apply {
                put("score", round(signal.score))
                put("evidence", JSONArray(signal.evidence))
                put("observation", signal.observation.toJson())
            }
        }))
        put("inferred", JSONArray(inferred.map { signal ->
            JSONObject().apply {
                put("standard", signal.profile.standard)
                put("family", signal.profile.family.name)
                put("band", signal.profile.bandLabel)
                put("prior", round(signal.prior))
                put("observability", signal.profile.observability.name)
                put("reason", signal.reason)
            }
        }))
    }

    private fun ResolvedEmitter.toJson() = JSONObject().apply {
        put("key", key)
        put("position", worldPosition?.toJson() ?: JSONObject.NULL)
        put("positionErrorM", positionErrorM?.let { round(it) } ?: JSONObject.NULL)
        put("method", method.name)
        put("methodLabel", method.label)
        put("methodIsMeasured", method.isMeasured)
        put("fixCount", fixCount)
        put("seenInShots", JSONArray(seenInShots))
        put("visualTargetId", visualTargetId ?: JSONObject.NULL)
        put("observation", observation.toJson())
    }

    private fun RadioObservation.toJson() = JSONObject().apply {
        put("key", key)
        put("family", family.name)
        put("displayName", displayName)
        put("vendor", vendor ?: JSONObject.NULL)
        put("standard", standard)
        put("band", bandLabel)
        put("freqHz", freqHz ?: JSONObject.NULL)
        put("rssiDbm", rssiDbm ?: JSONObject.NULL)
        put("measuredRangeM", measuredRangeM?.let { round(it) } ?: JSONObject.NULL)
        put("measuredRangeStdDevM", measuredRangeStdDevM?.let { round(it) } ?: JSONObject.NULL)
        put("estimatedRangeM", estimatedRangeM?.let { round(it) } ?: JSONObject.NULL)
        put("estimateIsPathLoss", estimatedRangeM != null)
        put("sightings", sightings)
        put("firstSeenElapsedMs", firstSeenElapsedMs)
        put("lastSeenElapsedMs", lastSeenElapsedMs)
        put("activity", activityDescription())
        put("extras", JSONObject(extras.toMap()))
    }

    private fun Vec3.toJson() = JSONArray(listOf(round(x), round(y), round(z)))

    private fun round(value: Float, decimals: Int = 3): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return Math.round(value * factor) / factor
    }

    private const val CAPABILITY_NOTE =
        "Signals marked inferred were never observed: this hardware has receivers only for " +
            "Wi-Fi, Bluetooth, cellular, GNSS and NFC. Zigbee, Thread, Z-Wave, sub-GHz ISM, " +
            "DECT and proprietary 2.4 GHz links cannot be detected by any Pixel phone. " +
            "Wi-Fi clients are also invisible - Android has no monitor mode - so those are " +
            "found over mDNS instead."
}
