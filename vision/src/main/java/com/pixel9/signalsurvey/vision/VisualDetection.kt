package com.pixel9.signalsurvey.vision

import android.graphics.Rect

/**
 * One object the detector found.
 *
 * [boxImagePx] is in the *unrotated* camera image space, matching ARCore's
 * `Coordinates2d.IMAGE_PIXELS`, so it can be handed straight to
 * `Frame.transformCoordinates2d`. Getting this wrong is the classic way to end up with
 * annotations that are 90 degrees out — see [RotationMapping].
 */
data class VisualDetection(
    val trackingId: Int?,
    val label: String,
    val confidence: Float,
    val boxImagePx: Rect,
    /**
     * Set when the generic labeller named something the ontology has no entry for — a real
     * observation ("Bookcase") that carries no RF expectations. Beats discarding it.
     */
    val displayNameOverride: String? = null,
    /** The labeller's own wording, kept so the identification stays auditable in the export. */
    val rawLabel: String? = null,
    /**
     * True when a remote vision model supplied this identity rather than an on-device model.
     * Carried through to the annotations and the export: a reader deserves to know that one
     * label came off the device and another did not.
     */
    val identifiedByCloud: Boolean = false,
)

/**
 * Converts between ML Kit's upright coordinate space and ARCore's raw image space.
 *
 * ML Kit is handed a rotation so it can reason about upright objects, and it returns boxes
 * in that rotated frame. ARCore knows nothing about that rotation and expects raw sensor
 * pixels. Every box has to make the trip back.
 */
object RotationMapping {

    /**
     * Rotation, in degrees, that makes the back camera's output upright for the current
     * display orientation.
     *
     * @param displayRotationSurface one of `Surface.ROTATION_*`
     * @param sensorOrientation `CameraCharacteristics.SENSOR_ORIENTATION` for the AR camera
     */
    fun forBackCamera(displayRotationSurface: Int, sensorOrientation: Int): Int {
        val displayDegrees = when (displayRotationSurface) {
            1 -> 90    // Surface.ROTATION_90
            2 -> 180
            3 -> 270
            else -> 0
        }
        return (sensorOrientation - displayDegrees + 360) % 360
    }

    /**
     * Map a box from ML Kit's rotated frame back into raw image pixels.
     *
     * @param srcWidth width of the *unrotated* camera image
     * @param srcHeight height of the *unrotated* camera image
     */
    fun unrotate(box: Rect, rotationDegrees: Int, srcWidth: Int, srcHeight: Int): Rect {
        fun map(xr: Int, yr: Int): Pair<Int, Int> = when (rotationDegrees) {
            90 -> yr to (srcHeight - 1 - xr)
            180 -> (srcWidth - 1 - xr) to (srcHeight - 1 - yr)
            270 -> (srcWidth - 1 - yr) to xr
            else -> xr to yr
        }

        val (x1, y1) = map(box.left, box.top)
        val (x2, y2) = map(box.right, box.bottom)
        return Rect(
            minOf(x1, x2).coerceIn(0, srcWidth),
            minOf(y1, y2).coerceIn(0, srcHeight),
            maxOf(x1, x2).coerceIn(0, srcWidth),
            maxOf(y1, y2).coerceIn(0, srcHeight),
        )
    }
}
