package com.pixel9.signalsurvey.ar

import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.pixel9.signalsurvey.model.CameraSnapshot
import com.pixel9.signalsurvey.model.DepthSnapshot
import com.pixel9.signalsurvey.model.Vec3
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.math.atan

/** Camera metadata + depth, grabbed synchronously on the GL thread. */
class FrozenMetadata(
    val camera: CameraSnapshot,
    val depth: DepthSnapshot?,
    val viewWidth: Int,
    val viewHeight: Int,
)

/** A complete frozen shot: pixels plus everything needed to re-project into them. */
class FrozenFrame(
    val bitmap: Bitmap,
    val camera: CameraSnapshot,
    val depth: DepthSnapshot?,
)

/**
 * Freezes a shot in two halves.
 *
 * [grabMetadata] must run on the GL thread while the [Frame] is alive — matrices, intrinsics
 * and the depth image are only valid there. [copyPixels] then reads the rendered surface
 * asynchronously. Splitting it this way keeps the GL thread unblocked and, more importantly,
 * means the metadata corresponds to *exactly* the frame the operator saw when they tapped.
 */
class SnapshotCapturer {

    fun grabMetadata(
        frame: Frame,
        viewWidth: Int,
        viewHeight: Int,
        trueNorthYawRad: Float?,
    ): FrozenMetadata? {
        val cam = frame.camera
        if (cam.trackingState != TrackingState.TRACKING) return null

        val view = FloatArray(16).also { cam.getViewMatrix(it, 0) }
        val proj = FloatArray(16).also { cam.getProjectionMatrix(it, 0, Z_NEAR, Z_FAR) }
        val intrinsics = cam.imageIntrinsics
        val pose = cam.pose

        // For a standard perspective matrix m[0] = 1 / (aspect * tan(fovY/2)) = 1 / tan(fovX/2).
        val hFovDeg = Math.toDegrees(2.0 * atan(1.0 / proj[0])).toFloat()

        val snapshot = CameraSnapshot(
            worldPosition = Vec3.of(pose.translation),
            orientation = pose.rotationQuaternion,
            viewMatrix = view,
            projMatrix = proj,
            zNear = Z_NEAR,
            zFar = Z_FAR,
            focalPx = intrinsics.focalLength,
            principalPx = intrinsics.principalPoint,
            horizontalFovDeg = hFovDeg,
            trueNorthYawRad = trueNorthYawRad,
        )

        return FrozenMetadata(snapshot, frame.copyDepth(), viewWidth, viewHeight)
    }

    /**
     * PixelCopy the rendered AR surface. This captures the camera background only — our
     * annotations are separate view layers — which is exactly what the report renderer wants
     * as its clean base image.
     */
    suspend fun copyPixels(surfaceView: SurfaceView): Bitmap? {
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return null

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(surfaceView, bitmap, { result ->
                    cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "PixelCopy rejected the surface", e)
                cont.resume(false)
            }
        }
        if (!ok) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    /**
     * ARCore recycles depth Images aggressively, so the buffer must be copied out before the
     * next frame. Returns null when depth has not converged — on a base Pixel 9 that means
     * the operator has not moved enough yet, since there is no ToF sensor to fall back on.
     */
    private fun Frame.copyDepth(): DepthSnapshot? {
        val image: Image = try {
            acquireDepthImage16Bits()
        } catch (e: NotYetAvailableException) {
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Depth unavailable: ${e.message}")
            return null
        }

        return image.use { img ->
            val plane = img.planes[0]
            val buffer = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val out = ShortArray(img.width * img.height)
            val rowStrideShorts = plane.rowStride / 2
            for (y in 0 until img.height) {
                buffer.position(y * rowStrideShorts)
                buffer.get(out, y * img.width, img.width)
            }
            DepthSnapshot(img.width, img.height, out, captureViewToTextureAffine())
        }
    }

    /**
     * Capture the VIEW_NORMALIZED -> TEXTURE_NORMALIZED mapping as an affine, so depth can be
     * sampled after the frame is gone. The transform is a crop plus a rotation, so three
     * points determine it exactly.
     */
    private fun Frame.captureViewToTextureAffine(): FloatArray {
        val src = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
        val dst = FloatArray(6)
        return try {
            transformCoordinates2d(
                Coordinates2d.VIEW_NORMALIZED, src,
                Coordinates2d.TEXTURE_NORMALIZED, dst,
            )
            floatArrayOf(
                dst[0], dst[1],
                dst[2] - dst[0], dst[3] - dst[1],
                dst[4] - dst[0], dst[5] - dst[1],
            )
        } catch (e: NotYetAvailableException) {
            DepthSnapshot.IDENTITY_AFFINE
        }
    }

    private companion object {
        const val TAG = "SnapshotCapturer"
        const val Z_NEAR = 0.1f
        const val Z_FAR = 200f
    }
}
