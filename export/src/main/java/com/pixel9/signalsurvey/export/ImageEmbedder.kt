package com.pixel9.signalsurvey.export

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Turns bitmaps into `data:` URIs so the report is genuinely self-contained.
 *
 * Referencing sibling files by name does not work where the report actually gets opened.
 * Android hands a file manager's "open with" a `content://` URI, and a relative `src` cannot
 * resolve against a content URI — the images sit right there in the folder and every one of
 * them renders as a broken icon. The same happens if the HTML is mailed, moved, or synced
 * without the folder around it.
 *
 * Embedding costs size, so the inline copies are downscaled. The full-resolution JPEGs stay
 * in the folder alongside for zooming, printing and anything that wants the real pixels.
 */
object ImageEmbedder {

    /**
     * Wide enough that annotation callout text stays legible on a laptop screen, small
     * enough that a ten-shot survey does not produce a 200 MB HTML file.
     */
    private const val MAX_INLINE_WIDTH = 1400

    /** The plan view is a diagram; text stays crisper with a little more headroom. */
    private const val MAX_INLINE_PLAN_WIDTH = 1600

    private const val INLINE_JPEG_QUALITY = 82

    /**
     * Total embedded budget. Past this, browsers get sluggish and mail servers start
     * rejecting attachments, so the remaining shots fall back to filename references and the
     * report says so rather than silently dropping them.
     */
    private const val MAX_TOTAL_EMBEDDED_BYTES = 24 * 1024 * 1024

    private const val TAG = "ImageEmbedder"

    class Budget {
        var usedBytes: Long = 0L
            private set
        var exhausted: Boolean = false
            private set

        fun consume(bytes: Int): Boolean {
            if (usedBytes + bytes > MAX_TOTAL_EMBEDDED_BYTES) {
                exhausted = true
                return false
            }
            usedBytes += bytes
            return true
        }
    }

    /**
     * A downscaled JPEG of [bitmap] as a data URI, or null when it would not fit the budget
     * (in which case the caller should fall back to a filename reference).
     */
    fun embedPhoto(bitmap: Bitmap, budget: Budget): String? =
        embed(bitmap, MAX_INLINE_WIDTH, Bitmap.CompressFormat.JPEG, INLINE_JPEG_QUALITY, budget)

    /** As [embedPhoto] but PNG, so the plan view's labels and hairlines stay sharp. */
    fun embedDiagram(bitmap: Bitmap, budget: Budget): String? =
        embed(bitmap, MAX_INLINE_PLAN_WIDTH, Bitmap.CompressFormat.PNG, 100, budget)

    private fun embed(
        bitmap: Bitmap,
        maxWidth: Int,
        format: Bitmap.CompressFormat,
        quality: Int,
        budget: Budget,
    ): String? {
        if (bitmap.isRecycled) return null

        var scaled: Bitmap? = null
        return try {
            val source = if (bitmap.width > maxWidth) {
                val height = (bitmap.height.toFloat() * maxWidth / bitmap.width)
                    .toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true).also { scaled = it }
            } else {
                bitmap
            }

            val bytes = ByteArrayOutputStream(512 * 1024).use { stream ->
                source.compress(format, quality, stream)
                stream.toByteArray()
            }

            // Base64 inflates by about a third; charge the budget for the encoded size.
            val encodedSize = (bytes.size * 4 + 2) / 3
            if (!budget.consume(encodedSize)) {
                Log.w(TAG, "Inline image budget exhausted; falling back to file references")
                return null
            }

            val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            // OutOfMemory is a real possibility on a long survey; a report with file
            // references beats no report at all.
            Log.e(TAG, "Could not embed image", e)
            null
        } finally {
            scaled?.recycle()
        }
    }
}
