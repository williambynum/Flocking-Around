package com.pixel9.signalsurvey.ar

import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.pixel9.signalsurvey.model.RangeSource
import com.pixel9.signalsurvey.model.Vec3

/** A world anchor plus how confident we are about the distance behind it. */
class PlacedPoint(
    val world: Vec3,
    val rangeM: Float,
    val source: RangeSource,
    /** Null when the point was synthesised along a ray rather than hit-tested. */
    val pose: Pose?,
)

/**
 * Turns a screen point into a world position, degrading gracefully.
 *
 * Ceiling APs, wall cameras and towers against open sky routinely fail every hit test, and
 * an annotation with no anchor is worse than an approximate one — so the last tier always
 * succeeds, and simply reports itself as [RangeSource.ASSUMED].
 */
class AnchorPlacer {

    fun place(
        session: Session,
        frame: Frame,
        viewX: Float,
        viewY: Float,
        /** Range hint from RF (RTT if available), used only by the fallback tiers. */
        rfRangeHintM: Float?,
    ): PlacedPoint? {
        if (frame.camera.trackingState != TrackingState.TRACKING) return null

        val hits = runCatching { frame.hitTest(viewX, viewY) }.getOrDefault(emptyList())

        // Tier 1 - real depth. Best metric accuracy out to roughly 8 m.
        hits.firstOrNull { it.trackable is DepthPoint }?.let {
            return PlacedPoint(Vec3.of(it.hitPose.translation), it.distance, RangeSource.AR_DEPTH, it.hitPose)
        }

        // Tier 2 - a detected plane, but only if the hit is inside its polygon. Without that
        // check ARCore happily returns hits on the infinite extension of a plane.
        hits.firstOrNull { h ->
            val t = h.trackable
            t is Plane && t.isPoseInPolygon(h.hitPose) && h.distance > 0f
        }?.let {
            return PlacedPoint(Vec3.of(it.hitPose.translation), it.distance, RangeSource.AR_PLANE, it.hitPose)
        }

        // Tier 3 - a feature point with a usable normal.
        hits.firstOrNull { h ->
            val t = h.trackable
            t is Point && t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
        }?.let {
            return PlacedPoint(Vec3.of(it.hitPose.translation), it.distance, RangeSource.AR_PLANE, it.hitPose)
        }

        // Tier 4 - instant placement. The distance is a guess that ARCore refines as the
        // operator moves, which the multi-shot flow gives it plenty of opportunity to do.
        val approx = rfRangeHintM ?: DEFAULT_RANGE_M
        runCatching { frame.hitTestInstantPlacement(viewX, viewY, approx) }
            .getOrDefault(emptyList())
            .firstOrNull { it.trackable is InstantPlacementPoint }
            ?.let {
                return PlacedPoint(
                    Vec3.of(it.hitPose.translation), it.distance, RangeSource.ASSUMED, it.hitPose
                )
            }

        // Tier 5 - nothing to hit. Project along the view ray at the best range we have.
        return placeAlongRay(frame, viewX, viewY, approx)
    }

    /**
     * Unproject a view pixel and walk [rangeM] along it. Used for anything the scene cannot
     * hit-test: a mast on the skyline, an AP behind glass.
     */
    private fun placeAlongRay(frame: Frame, viewX: Float, viewY: Float, rangeM: Float): PlacedPoint? {
        val cam = frame.camera
        val ndc = FloatArray(2)
        runCatching {
            frame.transformCoordinates2d(
                com.google.ar.core.Coordinates2d.VIEW, floatArrayOf(viewX, viewY),
                com.google.ar.core.Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, ndc,
            )
        }.getOrElse { return null }

        val proj = FloatArray(16).also { cam.getProjectionMatrix(it, 0, 0.1f, 200f) }
        val invProj = FloatArray(16)
        if (!android.opengl.Matrix.invertM(invProj, 0, proj, 0)) return null

        val eye = FloatArray(4)
        android.opengl.Matrix.multiplyMV(eye, 0, invProj, 0, floatArrayOf(ndc[0], ndc[1], -1f, 1f), 0)
        eye[2] = -1f
        eye[3] = 0f

        val view = FloatArray(16).also { cam.getViewMatrix(it, 0) }
        val invView = FloatArray(16)
        if (!android.opengl.Matrix.invertM(invView, 0, view, 0)) return null

        val worldDir = FloatArray(4)
        android.opengl.Matrix.multiplyMV(worldDir, 0, invView, 0, eye, 0)

        val dir = Vec3(worldDir[0], worldDir[1], worldDir[2]).normalized()
        val origin = Vec3.of(cam.pose.translation)
        return PlacedPoint(origin + dir * rangeM, rangeM, RangeSource.ASSUMED, null)
    }

    private companion object {
        /** Median indoor device distance; only used when nothing better exists. */
        const val DEFAULT_RANGE_M = 6f
    }
}
