package com.pixel9.signalsurvey.model

import android.graphics.Rect

/**
 * A multi-shot survey. Every shot shares one ARCore world frame and one continuous radio
 * log, which is what lets shot 5 be annotated with an emitter that was only ever ranged
 * during shots 1 and 2.
 */
data class SurveySession(
    val id: String,
    val label: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val deviceProfile: String,
    val shots: List<Shot> = emptyList(),
    /** Every distinct thing heard during the whole session, latest state per key. */
    val observations: Map<String, RadioObservation> = emptyMap(),
    /** RSSI vs. position, the input to gradient localization. */
    val rssiSamples: List<RssiSample> = emptyList(),
    /** True ranges from Wi-Fi RTT, the input to trilateration. */
    val rttFixes: List<RttFix> = emptyList(),
    /** Emitters after cross-shot resolution. Populated when the session is finalised. */
    val emitters: List<ResolvedEmitter> = emptyList(),
    /** Where the phone travelled, for the plan view. */
    val cameraPath: List<PathPoint> = emptyList(),
    val satellites: List<SatelliteFix> = emptyList(),
    val location: GeoFix? = null,
) {
    val durationMs: Long get() = (endedAtEpochMs ?: System.currentTimeMillis()) - startedAtEpochMs

    val locatedEmitters: List<ResolvedEmitter> get() = emitters.filter { it.worldPosition != null }

    /** Emitters heard but never placed. These belong in the margin rail, not on the image. */
    val unlocatedEmitters: List<ResolvedEmitter> get() = emitters.filter { it.worldPosition == null }

    fun observationsFor(family: RadioFamily): List<RadioObservation> =
        observations.values.filter { it.family == family }

    /** Path length in metres — the "did the operator move enough?" number. */
    fun pathLengthM(): Float {
        if (cameraPath.size < 2) return 0f
        var total = 0f
        for (i in 1 until cameraPath.size) {
            total += cameraPath[i].world.distanceTo(cameraPath[i - 1].world)
        }
        return total
    }
}

data class PathPoint(val elapsedMs: Long, val world: Vec3, val shotIndex: Int? = null)

/**
 * One frozen frame plus everything measurable about it.
 *
 * [imagePath] is null until the session is exported; the live bitmap is held separately by
 * the repository so the model stays cheap to copy.
 */
data class Shot(
    val index: Int,
    val capturedAtEpochMs: Long,
    val elapsedMs: Long,
    val imagePath: String?,
    val widthPx: Int,
    val heightPx: Int,
    val camera: CameraSnapshot,
    val depth: DepthSnapshot?,
    /** Devices seen in *this* frame. IDs are session-wide after cross-shot merging. */
    val targets: List<VisualTarget> = emptyList(),
    val note: String? = null,
) {
    /** Depth map coverage at capture time; below ~0.25 the operator did not move enough. */
    val depthCoverage: Float get() = depth?.coverage() ?: 0f
}

/** A device recognised in the image and pinned to a world position. */
data class VisualTarget(
    /** Session-wide identity; the same physical router keeps one ID across shots. */
    val id: Int,
    /** ML Kit tracking ID within the shot it came from. Not stable across shots. */
    val trackingId: Int?,
    val label: String,
    val displayName: String,
    val visualConfidence: Float,
    /** In the captured bitmap's pixel space. */
    val boxImagePx: Rect,
    val anchorWorld: Vec3,
    val rangeM: Float?,
    val rangeSource: RangeSource,
    val bearingDeg: Float,
    val elevationDeg: Float,
    val confirmed: List<ConfirmedSignal> = emptyList(),
    val inferred: List<InferredSignal> = emptyList(),
    /** Shot indices this target has been seen in. */
    val seenInShots: List<Int> = emptyList(),
    /** Where the name came from. Surfaced everywhere the name is. */
    val identification: IdentificationSource = IdentificationSource.NONE,
) {
    val anchorCenterPx get() = android.graphics.PointF(
        boxImagePx.exactCenterX(), boxImagePx.exactCenterY()
    )
}

/**
 * How a device got its name.
 *
 * The app already separates measured signals from inferred ones; identification deserves the
 * same treatment. A name that came from a remote model is a different kind of claim from one
 * an on-device classifier produced — it was better informed, and it left the device to get
 * that way. Both facts belong on the record.
 */
enum class IdentificationSource(val label: String, val onDevice: Boolean) {
    /** Nothing recognised it. */
    NONE("unidentified", true),

    /** ML Kit's generic labeller — a category, not a product. */
    ON_DEVICE_GENERIC("on-device, generic labeller", true),

    /** A bundled TFLite classifier trained on RF-relevant device classes. */
    ON_DEVICE_CLASSIFIER("on-device classifier", true),

    /** A remote vision model. The image was uploaded to produce this. */
    CLOUD_VISION("cloud vision model (image uploaded)", false),
}

/**
 * How a distance was arrived at. Rendered differently for each source — an RSSI estimate
 * must never be mistaken for a measurement.
 */
enum class RangeSource(val shortLabel: String, val isMeasured: Boolean) {
    WIFI_RTT("802.11mc FTM", true),
    AR_DEPTH("AR depth", true),
    AR_PLANE("AR plane", true),
    TRILATERATION("multi-shot RTT", true),
    PATH_LOSS("RSSI estimate", false),
    ASSUMED("assumed", false),
}

data class ConfirmedSignal(
    val observation: RadioObservation,
    val score: Float,
    /** Why the fusion engine believes this: "vendor OUI match", "RTT range agrees", ... */
    val evidence: List<String>,
)

data class InferredSignal(
    val profile: SignalProfile,
    val prior: Float,
    /** Why it could not be confirmed. Always shown — this is the app's integrity. */
    val reason: String,
)

/**
 * An emitter resolved across the whole session rather than a single shot. This is the
 * multi-shot payoff: [worldPosition] can come from ranges taken at several standing
 * positions, and once solved the emitter can be drawn on *every* shot that looks at it.
 */
data class ResolvedEmitter(
    val key: String,
    val observation: RadioObservation,
    val worldPosition: Vec3?,
    /** 1-sigma position error in metres, where the method can estimate one. */
    val positionErrorM: Float?,
    val method: PositionMethod,
    val seenInShots: List<Int>,
    /** Set when fusion tied this emitter to something visible. */
    val visualTargetId: Int? = null,
    /** Number of independent measurements behind the position. */
    val fixCount: Int = 0,
) {
    val isLocated: Boolean get() = worldPosition != null

    val confidenceLabel: String get() = when {
        worldPosition == null -> "heard, not located"
        method == PositionMethod.RTT_TRILATERATION && (positionErrorM ?: 99f) < 2f -> "located"
        method == PositionMethod.VISUAL_ANCHOR -> "located (visual)"
        else -> "approximate"
    }
}

enum class PositionMethod(val label: String, val isMeasured: Boolean) {
    /** Least squares over >= 3 Wi-Fi RTT ranges from distinct positions. The good one. */
    RTT_TRILATERATION("multi-shot RTT trilateration", true),

    /** One RTT range: a sphere, not a point. Placed on the ring only if a bearing exists. */
    SINGLE_RANGE_RING("single RTT range", true),

    /** Anchored to a device the camera actually saw. */
    VISUAL_ANCHOR("visual anchor", true),

    /** Grid search over the RSSI field sampled along the camera path. Coarse. */
    RSSI_GRADIENT("RSSI gradient", false),

    UNLOCATED("not located", false),
}

/** Live counts for the arming HUD. */
data class RadioSummary(
    val perFamily: Map<RadioFamily, Int> = emptyMap(),
    val rttCapableAps: Int = 0,
    val strongestRssi: Int? = null,
) {
    val total: Int get() = perFamily.values.sum()

    companion object { val EMPTY = RadioSummary() }
}
