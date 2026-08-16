package com.pixel9.signalsurvey.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Detection on a frozen shot.
 *
 * Separate from [DeviceDetector] because a still has a completely different budget. The live
 * detector is racing a 30 Hz render loop; this one has the whole listen window (six seconds
 * of radio integration) to work in, so it runs in SINGLE_IMAGE_MODE, which is more thorough
 * per frame and does not have to keep tracker state consistent.
 *
 * Coordinates need no un-rotation here: the bitmap came from PixelCopy of the already-upright
 * AR surface, so boxes are directly in the shot's pixel space.
 */
class StillImageClassifier(context: Context) {

    val hasCustomModel: Boolean = runCatching {
        context.assets.list("")?.contains(DeviceDetector.MODEL_ASSET) == true
    }.getOrDefault(false)

    private val detector: ObjectDetector = if (hasCustomModel) {
        val localModel = LocalModel.Builder()
            .setAssetFilePath(DeviceDetector.MODEL_ASSET)
            .build()
        ObjectDetection.getClient(
            CustomObjectDetectorOptions.Builder(localModel)
                .setDetectorMode(CustomObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                // Lower floor than the live path: a still is sharper and there is time to
                // show a low-confidence guess with its confidence stated on the card.
                .setClassificationConfidenceThreshold(0.30f)
                .setMaxPerObjectLabelCount(3)
                .build()
        )
    } else {
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()
        )
    }

    suspend fun detect(bitmap: Bitmap): List<VisualDetection> =
        suspendCancellableCoroutine { cont ->
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { objects ->
                    cont.resume(
                        objects.mapNotNull { obj ->
                            val box = obj.boundingBox
                            if (box.width() < MIN_BOX_PX || box.height() < MIN_BOX_PX) {
                                return@mapNotNull null
                            }
                            val best = obj.labels.maxByOrNull { it.confidence }
                            VisualDetection(
                                trackingId = obj.trackingId,
                                label = best?.text ?: DeviceDetector.UNKNOWN_LABEL,
                                confidence = best?.confidence ?: 0f,
                                boxImagePx = box,
                            )
                        }
                    )
                }
                .addOnFailureListener {
                    Log.w(TAG, "Still detection failed", it)
                    cont.resume(emptyList())
                }
        }

    fun close() = detector.close()

    private companion object {
        const val TAG = "StillImageClassifier"
        const val MIN_BOX_PX = 40
    }
}
