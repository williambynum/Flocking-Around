package com.pixel9.signalsurvey.survey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps captured shots off the heap.
 *
 * A 1080x2400 ARGB_8888 bitmap is about 10 MB. A ten-shot survey with a raw and an annotated
 * copy of each is 200 MB resident, which is a guaranteed OOM on a device that is also running
 * ARCore, ML Kit and four radios. Full-resolution frames go to cache as JPEG immediately;
 * only small thumbnails stay in memory for the filmstrip.
 */
class ShotImageStore(context: Context) {

    private val dir = File(context.cacheDir, "shots").apply { mkdirs() }
    private val thumbnails = HashMap<Int, Bitmap>()

    suspend fun put(sessionId: String, index: Int, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            try {
                file(sessionId, index).outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, RAW_QUALITY, it)
                }
                val scale = THUMBNAIL_WIDTH.toFloat() / bitmap.width
                synchronized(thumbnails) {
                    thumbnails[index]?.recycle()
                    thumbnails[index] = Bitmap.createScaledBitmap(
                        bitmap,
                        THUMBNAIL_WIDTH,
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cache shot $index", e)
                false
            }
        }

    suspend fun load(sessionId: String, index: Int): Bitmap? = withContext(Dispatchers.IO) {
        val f = file(sessionId, index)
        if (!f.exists()) return@withContext null
        runCatching {
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }.getOrNull()
    }

    fun thumbnail(index: Int): Bitmap? = synchronized(thumbnails) { thumbnails[index] }

    fun clear(sessionId: String) {
        synchronized(thumbnails) {
            thumbnails.values.forEach { it.recycle() }
            thumbnails.clear()
        }
        runCatching { dir.listFiles { f -> f.name.startsWith(sessionId) }?.forEach { it.delete() } }
    }

    private fun file(sessionId: String, index: Int) = File(dir, "${sessionId}_$index.jpg")

    private companion object {
        const val TAG = "ShotImageStore"
        const val RAW_QUALITY = 92
        const val THUMBNAIL_WIDTH = 180
    }
}
