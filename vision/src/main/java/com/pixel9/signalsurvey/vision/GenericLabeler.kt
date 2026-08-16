package com.pixel9.signalsurvey.vision

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Names objects using ML Kit's generic image labeller.
 *
 * This is a different API from object detection, and the distinction is the whole reason
 * detections were coming back unlabelled: the object detector is class-agnostic, and its
 * bundled classifier only knows five coarse buckets (home good, fashion good, food, place,
 * plant). The labeller carries a ~400-entry everyday vocabulary — Television, Loudspeaker,
 * Laptop, Printer, Camera — on-device, with no training and no network.
 *
 * What it cannot do is tell a router from a set top box; "wireless router" is not in its
 * vocabulary and no prompt makes it appear. Bundling a trained classifier remains the path to
 * RF-specific classes. Until then this turns "unknown device" into "Loudspeaker", which is
 * most of the practical value.
 */
class GenericLabeler {

    private val labeler = ImageLabeling.getClient(
        ImageLabelerOptions.Builder()
            // Low floor on purpose: LabelMapper ranks and filters, and a weak-but-mapped
            // label beats no label at all. The confidence travels to the UI either way.
            .setConfidenceThreshold(0.35f)
            .build()
    )

    /**
     * Label one detected object.
     *
     * The crop is padded before labelling — the labeller performs noticeably better with a
     * little surrounding context than on a tight box, because scene cues help disambiguate
     * (a rectangle on a desk versus a rectangle on a wall).
     */
    suspend fun label(source: Bitmap, box: Rect): List<Pair<String, Float>> {
        val crop = cropWithPadding(source, box) ?: return emptyList()
        return try {
            suspendCancellableCoroutine { cont ->
                labeler.process(InputImage.fromBitmap(crop, 0))
                    .addOnSuccessListener { labels ->
                        cont.resume(labels.map { it.text to it.confidence })
                    }
                    .addOnFailureListener {
                        Log.w(TAG, "Labelling failed", it)
                        cont.resume(emptyList())
                    }
            }
        } finally {
            if (crop != source) crop.recycle()
        }
    }

    /** Scene-level labels for the whole frame — useful context when nothing was detected. */
    suspend fun labelScene(source: Bitmap): List<Pair<String, Float>> =
        suspendCancellableCoroutine { cont ->
            labeler.process(InputImage.fromBitmap(source, 0))
                .addOnSuccessListener { labels -> cont.resume(labels.map { it.text to it.confidence }) }
                .addOnFailureListener { cont.resume(emptyList()) }
        }

    private fun cropWithPadding(source: Bitmap, box: Rect): Bitmap? {
        if (source.isRecycled) return null

        val padX = (box.width() * PADDING_FRACTION).roundToInt()
        val padY = (box.height() * PADDING_FRACTION).roundToInt()

        val left = (box.left - padX).coerceIn(0, source.width - 1)
        val top = (box.top - padY).coerceIn(0, source.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)

        val width = right - left
        val height = bottom - top
        if (width < MIN_CROP_PX || height < MIN_CROP_PX) return null

        return runCatching {
            Bitmap.createBitmap(source, left, top, width, height)
        }.getOrNull()
    }

    fun close() = labeler.close()

    private companion object {
        const val TAG = "GenericLabeler"
        /** Surrounding context measurably improves labelling over a tight crop. */
        const val PADDING_FRACTION = 0.12f
        const val MIN_CROP_PX = 32
    }
}
