package com.pixel9.signalsurvey.model

import android.graphics.PointF
import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * A point in the ARCore world frame.
 *
 * The world frame is fixed at session start and shared by every shot in a [SurveySession] —
 * that is what makes cross-shot emitter resolution possible. It does not survive session
 * restart (that would need Cloud/Geospatial anchors).
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = sqrt(x * x + y * y + z * z)
    fun distanceTo(o: Vec3) = (this - o).length()

    /** Horizontal distance, ignoring height. Useful for plan-view work. */
    fun planarDistanceTo(o: Vec3) = hypot((x - o.x).toDouble(), (z - o.z).toDouble()).toFloat()

    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-6f) this else Vec3(x / l, y / l, z / l)
    }

    fun toFloatArray() = floatArrayOf(x, y, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        fun of(a: FloatArray) = Vec3(a[0], a[1], a[2])

        fun centroid(points: List<Vec3>): Vec3 {
            if (points.isEmpty()) return ZERO
            var sx = 0f; var sy = 0f; var sz = 0f
            points.forEach { sx += it.x; sy += it.y; sz += it.z }
            val n = points.size.toFloat()
            return Vec3(sx / n, sy / n, sz / n)
        }
    }
}

/**
 * Everything about the camera at the instant a shot was frozen.
 *
 * Storing the matrices (rather than recomputing from the pose) means annotations can be
 * re-projected identically weeks later, on a different device, from the exported JSON.
 * Not a data class: it holds FloatArrays, whose structural equality is identity.
 */
class CameraSnapshot(
    val worldPosition: Vec3,
    /** ARCore pose quaternion, x y z w. */
    val orientation: FloatArray,
    /** Column-major 4x4, OpenGL convention. */
    val viewMatrix: FloatArray,
    val projMatrix: FloatArray,
    val zNear: Float,
    val zFar: Float,
    /** Camera intrinsics in the CPU image stream: fx, fy. */
    val focalPx: FloatArray,
    /** Principal point cx, cy. */
    val principalPx: FloatArray,
    /** Horizontal field of view in degrees, derived from the projection matrix. */
    val horizontalFovDeg: Float,
    /**
     * Rotation from the ARCore world frame to true north, radians. Null when heading could
     * not be established, which disables GNSS sky projection but nothing else.
     */
    val trueNorthYawRad: Float?,
    /**
     * 1-sigma uncertainty on [trueNorthYawRad], radians, measured rather than assumed — see
     * HeadingResolver. Null when no heading was resolved.
     *
     * Carried this far because a heading is useless without it: at 20 degrees of error a
     * satellite marker is off by a fifth of the frame, and drawing it as a confident point
     * would be a lie the photograph then carries around on its own.
     */
    val trueNorthUncertaintyRad: Float? = null,
) {

    private val viewProj: FloatArray = FloatArray(16).also {
        Matrix.multiplyMM(it, 0, projMatrix, 0, viewMatrix, 0)
    }

    /**
     * Project a world point into the captured bitmap's pixel space.
     * Returns null when the point is behind the camera or well outside the frame.
     */
    fun projectToImage(world: Vec3, imageW: Int, imageH: Int, marginNdc: Float = 0.15f): PointF? {
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, viewProj, 0, floatArrayOf(world.x, world.y, world.z, 1f), 0)
        if (clip[3] <= 0f) return null
        val ndcX = clip[0] / clip[3]
        val ndcY = clip[1] / clip[3]
        if (abs(ndcX) > 1f + marginNdc || abs(ndcY) > 1f + marginNdc) return null
        return PointF((ndcX + 1f) * 0.5f * imageW, (1f - ndcY) * 0.5f * imageH)
    }

    /** True when the point falls strictly inside the frame. */
    fun isInFrame(world: Vec3, imageW: Int, imageH: Int): Boolean =
        projectToImage(world, imageW, imageH, marginNdc = 0f) != null

    /**
     * Camera-relative geometry. ARCore/OpenGL convention: the camera looks down -Z,
     * +X is right, +Y is up.
     */
    fun geometryTo(world: Vec3): Geometry {
        val local = FloatArray(4)
        Matrix.multiplyMV(local, 0, viewMatrix, 0, floatArrayOf(world.x, world.y, world.z, 1f), 0)
        val x = local[0]; val y = local[1]; val z = local[2]
        return Geometry(
            distanceM = sqrt(x * x + y * y + z * z),
            bearingDeg = Math.toDegrees(atan2(x.toDouble(), -z.toDouble())).toFloat(),
            elevationDeg = Math.toDegrees(
                atan2(y.toDouble(), hypot(x.toDouble(), z.toDouble()))
            ).toFloat(),
        )
    }

    /** Unit vector, in world space, pointing where the camera was aimed. */
    fun forward(): Vec3 {
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, viewMatrix, 0)
        val f = FloatArray(4)
        Matrix.multiplyMV(f, 0, inv, 0, floatArrayOf(0f, 0f, -1f, 0f), 0)
        return Vec3(f[0], f[1], f[2]).normalized()
    }

    /**
     * Unproject a pixel to a world point at [rangeM] from the camera. Used when nothing in
     * the scene can be hit-tested — a tower against open sky, for instance.
     */
    fun unprojectToRange(imageX: Float, imageY: Float, imageW: Int, imageH: Int, rangeM: Float): Vec3 {
        val ndcX = (imageX / imageW) * 2f - 1f
        val ndcY = 1f - (imageY / imageH) * 2f

        val invProj = FloatArray(16)
        Matrix.invertM(invProj, 0, projMatrix, 0)
        val eye = FloatArray(4)
        Matrix.multiplyMV(eye, 0, invProj, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        eye[2] = -1f; eye[3] = 0f

        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, invView, 0, eye, 0)

        val dir = Vec3(world[0], world[1], world[2]).normalized()
        return worldPosition + dir * rangeM
    }
}

data class Geometry(
    val distanceM: Float,
    /** Positive is right of frame centre. */
    val bearingDeg: Float,
    /** Positive is above frame centre. */
    val elevationDeg: Float,
)

/**
 * Places things given as a compass bearing and an elevation — GNSS satellites, principally —
 * into the ARCore world frame.
 *
 * Lives in :model rather than :ar because both the capture side and the report renderer need
 * it, and it is pure trigonometry with no ARCore dependency.
 */
object SkyProjection {

    /** Satellites are effectively at infinity; far enough that parallax is irrelevant. */
    const val SKY_RANGE_M = 1_000f

    /**
     * Above this much heading uncertainty a satellite marker is worse than no marker: at
     * 15 degrees the error is already a sixth of a typical frame width, so the report lists
     * the satellites instead of pretending to place them.
     */
    const val MAX_USABLE_UNCERTAINTY_RAD = 0.262f   // 15 degrees

    /**
     * Direction in the ARCore world frame of a point at the given true-north azimuth and
     * elevation.
     *
     * @param trueNorthYawRad offset that converts an ARCore world yaw into a true bearing.
     */
    fun directionToWorld(
        azimuthDegFromNorth: Float,
        elevationDeg: Float,
        trueNorthYawRad: Float,
    ): Vec3 {
        val trueBearing = Math.toRadians(azimuthDegFromNorth.toDouble()).toFloat()
        val worldYaw = trueBearing - trueNorthYawRad
        val el = Math.toRadians(elevationDeg.toDouble()).toFloat()
        return Vec3(
            x = kotlin.math.sin(worldYaw) * kotlin.math.cos(el),
            y = kotlin.math.sin(el),
            z = -kotlin.math.cos(worldYaw) * kotlin.math.cos(el),
        ).normalized()
    }

    /** World-space point for a sky object, relative to a camera position. */
    fun worldPoint(
        origin: Vec3,
        azimuthDegFromNorth: Float,
        elevationDeg: Float,
        trueNorthYawRad: Float,
        rangeM: Float = SKY_RANGE_M,
    ): Vec3 = origin + directionToWorld(azimuthDegFromNorth, elevationDeg, trueNorthYawRad) * rangeM

    /**
     * The locus a sky object sweeps as the heading is varied across its uncertainty band.
     * Drawn as an arc, which is the honest rendering of "somewhere along here".
     */
    fun uncertaintyArc(
        origin: Vec3,
        azimuthDegFromNorth: Float,
        elevationDeg: Float,
        trueNorthYawRad: Float,
        uncertaintyRad: Float,
        steps: Int = 9,
    ): List<Vec3> {
        val spanDeg = Math.toDegrees(uncertaintyRad.toDouble()).toFloat()
        return (0 until steps).map { i ->
            val t = i / (steps - 1f) * 2f - 1f          // -1 .. +1
            worldPoint(
                origin,
                azimuthDegFromNorth + t * spanDeg,
                elevationDeg,
                trueNorthYawRad,
            )
        }
    }
}

/**
 * ARCore's depth map, copied out of the pooled [android.media.Image].
 * Typically 160x120 on a Pixel 9 — depth-from-motion, no ToF sensor.
 */
class DepthSnapshot(
    val widthPx: Int,
    val heightPx: Int,
    /** Row-major; low 13 bits of each entry are millimetres, 0 means "no estimate". */
    val millimetres: ShortArray,
    /**
     * Affine map from normalized view coordinates to the texture-normalized space the depth
     * map lives in: `[ox, oy, uxx, uxy, uyx, uyy]`.
     *
     * Captured at freeze time because `Frame.transformCoordinates2d` needs a live frame, and
     * detections are matched to depth long after that frame has been recycled. The mapping is
     * a crop plus a rotation, so an affine is exact.
     */
    val viewToTexture: FloatArray = IDENTITY_AFFINE,
) {

    /** Depth at a point expressed in normalized view coordinates (0..1, origin top-left). */
    fun metresAtViewNormalized(uView: Float, vView: Float): Float? {
        val t = viewToTexture
        val u = t[0] + uView * t[2] + vView * t[4]
        val v = t[1] + uView * t[3] + vView * t[5]
        return metresAt(u, v)
    }
    /**
     * Metric depth at a texture-normalized coordinate, as the median of a 3x3 patch.
     * Single depth pixels from depth-from-motion are noisy enough that the median matters.
     */
    fun metresAt(uNorm: Float, vNorm: Float): Float? {
        if (uNorm !in 0f..1f || vNorm !in 0f..1f) return null
        val cx = (uNorm * widthPx).toInt().coerceIn(0, widthPx - 1)
        val cy = (vNorm * heightPx).toInt().coerceIn(0, heightPx - 1)
        val patch = ArrayList<Int>(9)
        for (dy in -1..1) for (dx in -1..1) {
            val px = (cx + dx).coerceIn(0, widthPx - 1)
            val py = (cy + dy).coerceIn(0, heightPx - 1)
            val mm = millimetres[py * widthPx + px].toInt() and 0x1FFF
            if (mm > 0) patch.add(mm)
        }
        if (patch.isEmpty()) return null
        patch.sort()
        return patch[patch.size / 2] / 1000f
    }

    /** Fraction of the map that carries a usable estimate — the "is depth ready yet" gauge. */
    fun coverage(): Float {
        var filled = 0
        for (s in millimetres) if ((s.toInt() and 0x1FFF) > 0) filled++
        return filled.toFloat() / millimetres.size
    }

    companion object {
        val IDENTITY_AFFINE = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
    }
}
