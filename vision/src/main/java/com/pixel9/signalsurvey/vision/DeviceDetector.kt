package com.pixel9.signalsurvey.vision

import android.content.Context
import android.graphics.Rect
import android.media.Image
import android.util.Log
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live detection during the arming sweep.
 *
 * The pipeline is deliberately hybrid:
 *
 * - **ML Kit's detector** handles localisation and tracking. Its tracking IDs are the reason
 *   anchors survive between frames; writing an equivalent tracker is a project of its own.
 * - **A custom TFLite classifier** supplies the labels. ML Kit's built-in classifier only
 *   emits five coarse categories (home good, fashion good, food, place, plant), none of which
 *   distinguishes a router from a toaster.
 *
 * When [MODEL_ASSET] is absent the detector still runs class-agnostically and reports
 * [UNKNOWN_LABEL], so the rest of the app — anchors, RF fusion, export — is fully testable
 * before a model has been trained.
 */
class DeviceDetector(context: Context) {

    val hasCustomModel: Boolean = runCatching {
        context.assets.list("")?.contains(MODEL_ASSET) == true
    }.getOrDefault(false)

    private val detector: ObjectDetector = if (hasCustomModel) {
        val localModel = LocalModel.Builder().setAssetFilePath(MODEL_ASSET).build()
        ObjectDetection.getClient(
            CustomObjectDetectorOptions.Builder(localModel)
                .setDetectorMode(CustomObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .setClassificationConfidenceThreshold(CONFIDENCE_FLOOR)
                .setMaxPerObjectLabelCount(3)
                .build()
        )
    } else {
        Log.w(TAG, "assets/$MODEL_ASSET missing - running class-agnostic. Labels will be '$UNKNOWN_LABEL'.")
        ObjectDetection.getClient(
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .build()
        )
    }

    private val voter = LabelVoter()
    private val inFlight = AtomicBoolean(false)

    /**
     * Run one inference. Drops the frame if a previous one is still running — at roughly
     * 8-12 Hz against a 30 Hz render loop that is the correct behaviour, not a compromise.
     *
     * Takes ownership of [image] and closes it when done. Failing to close stalls ARCore
     * within a few frames, because the CPU image pool is only two or three deep.
     */
    fun analyze(
        image: Image,
        rotationDegrees: Int,
        onResult: (List<VisualDetection>) -> Unit,
    ) {
        if (!inFlight.compareAndSet(false, true)) {
            image.close()
            return
        }

        val srcWidth = image.width
        val srcHeight = image.height
        val input = try {
            InputImage.fromMediaImage(image, rotationDegrees)
        } catch (e: Exception) {
            image.close()
            inFlight.set(false)
            return
        }

        detector.process(input)
            .addOnSuccessListener { objects ->
                val detections = objects.mapNotNull {
                    it.toDetection(rotationDegrees, srcWidth, srcHeight)
                }
                voter.retainOnly(detections.mapNotNull { it.trackingId }.toSet())
                onResult(detections)
            }
            .addOnFailureListener { Log.w(TAG, "Detection failed", it) }
            .addOnCompleteListener {
                image.close()
                inFlight.set(false)
            }
    }

    private fun DetectedObject.toDetection(
        rotationDegrees: Int,
        srcWidth: Int,
        srcHeight: Int,
    ): VisualDetection? {
        val box = RotationMapping.unrotate(boundingBox, rotationDegrees, srcWidth, srcHeight)
        if (box.width() < MIN_BOX_PX || box.height() < MIN_BOX_PX) return null

        val id = trackingId
        val best = labels.maxByOrNull { it.confidence }

        // Class-agnostic mode: still emit the box so anchors and RF fusion can be exercised.
        if (best == null) {
            return VisualDetection(id, UNKNOWN_LABEL, 0f, box)
        }

        // Temporal majority vote. A single frame's top label flickers between neighbours
        // constantly; a label that changes under the annotation is worse than a late one.
        val stable = if (id != null) {
            voter.vote(id, best.text) ?: return null   // not yet confident - emit nothing
        } else {
            best.text
        }

        return VisualDetection(id, stable, best.confidence, box)
    }

    fun close() = detector.close()

    companion object {
        const val MODEL_ASSET = "device_classifier.tflite"
        const val UNKNOWN_LABEL = "unknown_device"
        private const val TAG = "DeviceDetector"
        private const val CONFIDENCE_FLOOR = 0.45f
        private const val MIN_BOX_PX = 48
    }
}

/**
 * Rolling majority vote per tracking ID.
 *
 * Not merely cosmetic: the fusion engine keys its priors off the label, so a label that
 * oscillates makes the RF association oscillate with it.
 */
class LabelVoter(
    private val window: Int = 8,
    private val majority: Int = 5,
) {
    private val votes = HashMap<Int, ArrayDeque<String>>()

    /** @return the stable label, or null while no label has reached the majority yet. */
    fun vote(trackingId: Int, label: String): String? {
        val q = votes.getOrPut(trackingId) { ArrayDeque() }
        q.addLast(label)
        while (q.size > window) q.removeFirst()

        val (best, count) = q.groupingBy { it }.eachCount()
            .maxByOrNull { it.value } ?: return null
        return if (count >= majority) best else null
    }

    fun retainOnly(liveIds: Set<Int>) {
        votes.keys.retainAll(liveIds)
    }

    fun forget(trackingId: Int) { votes.remove(trackingId) }
}
