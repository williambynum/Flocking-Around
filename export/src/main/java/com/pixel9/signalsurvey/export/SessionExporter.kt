package com.pixel9.signalsurvey.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.pixel9.signalsurvey.model.SurveySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(val name: String, val uri: Uri?, val path: String?, val bytes: Long)

data class ExportResult(
    /** Display path of the folder everything landed in. */
    val folderPath: String,
    val files: List<ExportedFile>,
    /** Set when MediaStore was unavailable and the bundle went to app storage instead. */
    val usedFallback: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
    val imageCount: Int get() = files.count { it.name.endsWith(".jpg") }
}

/**
 * Writes a finished survey as one self-contained folder.
 *
 * Everything lands in a single directory under Download, because a survey is a document, not
 * a photo album — splitting the images into Pictures and the data into app storage makes the
 * bundle impossible to hand to anyone.
 *
 * Five renderings of the same survey, so it can be read without this app:
 *
 * - `report.html`  the whole thing as a web page with the images inline
 * - `summary.txt`  plain text
 * - `emitters.csv` one row per emitter, for Excel or pandas
 * - `devices.csv`  one row per signal claim against each identified device
 * - `survey.json`  the complete machine-readable record
 *
 * Each JPEG also carries its own shot record in EXIF, so an image forwarded on its own still
 * says what was measured and what was only inferred.
 */
class SessionExporter(private val context: Context) {

    suspend fun export(
        session: SurveySession,
        annotatedShots: Map<Int, Bitmap>,
        planView: Bitmap?,
    ): ExportResult = withContext(Dispatchers.IO) {

        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US)
            .format(Date(session.startedAtEpochMs))
        val folderName = "${stamp}_${session.label.sanitised()}"
        val relativePath = "$BASE_FOLDER/$folderName"

        val writer = resolveWriter(relativePath, folderName)
        val written = mutableListOf<ExportedFile>()

        // Deterministic names, so the HTML can reference the images before they exist.
        val shotNames = session.shots.associate { it.index to "shot_%02d.jpg".format(it.index) }
        val planName = planView?.let { PLAN_NAME }

        // ---- images ----
        session.shots.forEach { shot ->
            val bitmap = annotatedShots[shot.index] ?: return@forEach
            val name = shotNames.getValue(shot.index)
            writer.writeBitmap(name, bitmap, png = false) { uri ->
                applyExif(uri, session, SessionJson.encodeShot(session, shot))
            }?.let { written += it }
        }

        if (planView != null) {
            writer.writeBitmap(PLAN_NAME, planView, png = true, onWritten = null)
                ?.let { written += it }
        }

        // ---- readable renderings ----
        writer.writeText(
            "report.html", "text/html",
            ReportBuilder.html(session, shotNames, planName),
        )?.let { written += it }

        writer.writeText(
            "summary.txt", "text/plain",
            ReportBuilder.summaryText(session),
        )?.let { written += it }

        writer.writeText(
            "emitters.csv", "text/csv",
            ReportBuilder.emittersCsv(session),
        )?.let { written += it }

        writer.writeText(
            "devices.csv", "text/csv",
            ReportBuilder.devicesCsv(session),
        )?.let { written += it }

        writer.writeText(
            "survey.json", "application/json",
            SessionJson.encode(session),
        )?.let { written += it }

        ExportResult(
            folderPath = writer.displayPath,
            files = written,
            usedFallback = writer.isFallback,
        )
    }

    // ---------------------------------------------------------------- writers

    private fun resolveWriter(relativePath: String, folderName: String): BundleWriter =
        MediaStoreWriter(context, relativePath).takeIf { it.isUsable() }
            ?: FileWriter(context, folderName)

    private interface BundleWriter {
        val displayPath: String
        val isFallback: Boolean
        fun writeBitmap(
            name: String,
            bitmap: Bitmap,
            png: Boolean,
            onWritten: ((Uri) -> Unit)?,
        ): ExportedFile?
        fun writeText(name: String, mime: String, content: String): ExportedFile?
    }

    /**
     * The Downloads collection is the only MediaStore collection that accepts arbitrary MIME
     * types, which is what allows the images, the CSVs and the HTML to share one directory.
     * The trade-off is that the shots do not appear in the system gallery — an acceptable
     * price for a bundle that can be handed over intact.
     */
    private class MediaStoreWriter(
        private val context: Context,
        private val relativePath: String,
    ) : BundleWriter {

        override val displayPath = relativePath
        override val isFallback = false

        fun isUsable(): Boolean = runCatching {
            context.contentResolver.getType(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            true
        }.getOrDefault(false)

        override fun writeBitmap(
            name: String,
            bitmap: Bitmap,
            png: Boolean,
            onWritten: ((Uri) -> Unit)?,
        ): ExportedFile? = insert(name, if (png) "image/png" else "image/jpeg") { stream ->
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            bitmap.compress(format, if (png) 100 else JPEG_QUALITY, stream)
        }?.also { file -> file.uri?.let { onWritten?.invoke(it) } }

        override fun writeText(name: String, mime: String, content: String): ExportedFile? =
            insert(name, mime) { stream -> stream.write(content.toByteArray(Charsets.UTF_8)) }

        private fun insert(
            name: String,
            mime: String,
            write: (java.io.OutputStream) -> Unit,
        ): ExportedFile? {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = runCatching {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: run {
                Log.e(TAG, "MediaStore insert failed for $name")
                return null
            }

            return try {
                var bytes = 0L
                resolver.openOutputStream(uri)?.use { stream ->
                    val counting = CountingStream(stream)
                    write(counting)
                    counting.flush()
                    bytes = counting.count
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ExportedFile(name, uri, "$relativePath/$name", bytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed writing $name", e)
                runCatching { resolver.delete(uri, null, null) }
                null
            }
        }
    }

    /** Fallback to app-specific storage, so an export never silently produces nothing. */
    private class FileWriter(context: Context, folderName: String) : BundleWriter {

        private val dir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "SignalSurvey/$folderName",
        ).apply { mkdirs() }

        override val displayPath: String = dir.absolutePath
        override val isFallback = true

        override fun writeBitmap(
            name: String,
            bitmap: Bitmap,
            png: Boolean,
            onWritten: ((Uri) -> Unit)?,
        ): ExportedFile? = runCatching {
            val file = File(dir, name)
            file.outputStream().use {
                val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, if (png) 100 else JPEG_QUALITY, it)
            }
            ExportedFile(name, Uri.fromFile(file), file.absolutePath, file.length())
        }.getOrNull()

        override fun writeText(name: String, mime: String, content: String): ExportedFile? =
            runCatching {
                val file = File(dir, name)
                file.writeText(content)
                ExportedFile(name, Uri.fromFile(file), file.absolutePath, file.length())
            }.getOrNull()
    }

    private fun applyExif(uri: Uri, session: SurveySession, comment: String) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
                    setAttribute(ExifInterface.TAG_SOFTWARE, SOFTWARE_TAG)
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, session.label)
                    session.location?.let { setLatLong(it.lat, it.lon) }
                    saveAttributes()
                }
            }
        }.onFailure { Log.w(TAG, "Could not write EXIF: ${it.message}") }
    }

    private class CountingStream(private val delegate: java.io.OutputStream) :
        java.io.OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len); count += len
        }

        override fun flush() = delegate.flush()
    }

    private fun String.sanitised(): String =
        replace(Regex("[^A-Za-z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(32)
            .ifBlank { "survey" }

    private companion object {
        const val TAG = "SessionExporter"
        // Not const: Environment.DIRECTORY_DOWNLOADS is resolved at runtime.
        val BASE_FOLDER = "${Environment.DIRECTORY_DOWNLOADS}/SignalSurvey"
        const val PLAN_NAME = "plan_view.png"
        const val JPEG_QUALITY = 95
        const val SOFTWARE_TAG = "Pixel9SignalSurvey"
    }
}
