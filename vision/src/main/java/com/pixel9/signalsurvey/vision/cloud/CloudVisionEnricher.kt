package com.pixel9.signalsurvey.vision.cloud

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.pixel9.signalsurvey.model.DeviceOntology
import com.pixel9.signalsurvey.vision.VisualDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** What came back for one detection, or why nothing did. */
sealed interface EnrichmentOutcome {
    data class Enriched(val detections: List<VisualDetection>, val costNote: String) : EnrichmentOutcome
    data class Failed(val reason: String, val retryable: Boolean) : EnrichmentOutcome
    data object Disabled : EnrichmentOutcome
}

/**
 * Optional cloud identification of the devices in a shot.
 *
 * The on-device labeller names a *category* — "Loudspeaker", "Computer monitor". A vision model
 * can often name the *thing*: "TP-Link mesh node, three external antennas". That difference is
 * what makes the RF ontology usable, since a router and a set top box are the same rectangle to
 * a generic labeller.
 *
 * It is off unless the operator turns it on, because it uploads photographs of whatever they
 * are surveying. Nothing here runs, and no client is constructed, without an explicit opt-in
 * and the operator's own API key.
 *
 * One request per shot, not one per crop: the full frame goes first for context, then each crop.
 * A router beside a TV in a media cabinet is far easier to identify with the cabinet visible
 * than as an isolated rectangle.
 */
class CloudVisionEnricher(private val settings: CloudVisionSettings) {

    /** Built lazily and only when enabled — constructing it eagerly would be a latent leak. */
    @Volatile private var client: AnthropicClient? = null

    private fun clientOrNull(): AnthropicClient? {
        if (!settings.isEnabled) return null
        client?.let { return it }
        val key = settings.apiKey ?: return null
        return synchronized(this) {
            client ?: runCatching {
                AnthropicOkHttpClient.builder().apiKey(key).build()
            }.onFailure { Log.e(TAG, "Could not build the API client", it) }
                .getOrNull()
                ?.also { client = it }
        }
    }

    /** Drop the cached client — call after the key changes or the toggle goes off. */
    fun invalidate() {
        synchronized(this) { client = null }
    }

    suspend fun enrich(
        shotBitmap: Bitmap,
        detections: List<VisualDetection>,
    ): EnrichmentOutcome = withContext(Dispatchers.IO) {
        if (detections.isEmpty()) return@withContext EnrichmentOutcome.Disabled
        val api = clientOrNull() ?: return@withContext EnrichmentOutcome.Disabled

        val considered = detections
            .sortedByDescending { it.boxImagePx.width() * it.boxImagePx.height() }
            .take(MAX_CROPS_PER_SHOT)

        val blocks = buildList {
            add(textBlock(
                "Overview of the whole scene. The numbered crops that follow are regions of " +
                    "this same photograph."
            ))
            encodeJpeg(shotBitmap, MAX_FRAME_WIDTH)?.let { add(imageBlock(it)) }

            considered.forEachIndexed { index, detection ->
                val crop = cropOf(shotBitmap, detection.boxImagePx) ?: return@forEachIndexed
                add(textBlock("Region $index:"))
                encodeJpeg(crop, MAX_CROP_WIDTH)?.let { add(imageBlock(it)) }
                if (crop != shotBitmap) crop.recycle()
            }

            add(textBlock(instruction(considered.size)))
        }

        val params = MessageCreateParams.builder()
            .model(Model.CLAUDE_OPUS_5)
            .maxTokens(2048L)
            .system(SYSTEM_PROMPT)
            // A classification task does not need deep reasoning, and each shot is on the
            // operator's critical path — low effort keeps latency and cost down.
            .putAdditionalBodyProperty("output_config", JsonValue.from(mapOf("effort" to "low")))
            .addUserMessageOfBlockParams(blocks)
            .build()

        try {
            val message = api.messages().create(params)

            // Safety classifiers can decline; check before reading content, which is empty on a
            // pre-output refusal.
            val refused = message.stopReason()
                .map { it == StopReason.REFUSAL }
                .orElse(false)
            if (refused) {
                return@withContext EnrichmentOutcome.Failed(
                    "The model declined to identify this image", retryable = false,
                )
            }

            val text = message.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("\n")

            val parsed = parseResponse(text, considered)
            if (parsed.isEmpty()) {
                return@withContext EnrichmentOutcome.Failed("No usable identification returned", true)
            }

            val usage = message.usage()
            val note = "cloud: %d in / %d out tokens".format(usage.inputTokens(), usage.outputTokens())
            EnrichmentOutcome.Enriched(mergeBack(detections, parsed), note)
        } catch (e: UnauthorizedException) {
            invalidate()
            EnrichmentOutcome.Failed("API key rejected — check it in settings", retryable = false)
        } catch (e: RateLimitException) {
            EnrichmentOutcome.Failed("Rate limited by the API", retryable = true)
        } catch (e: AnthropicIoException) {
            EnrichmentOutcome.Failed("No network connection", retryable = true)
        } catch (e: AnthropicServiceException) {
            EnrichmentOutcome.Failed("API error ${e.statusCode()}", retryable = e.statusCode() >= 500)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud enrichment failed", e)
            EnrichmentOutcome.Failed("Enrichment failed: ${e.message}", retryable = true)
        }
    }

    // ------------------------------------------------------------------ parsing

    private fun parseResponse(
        raw: String,
        considered: List<VisualDetection>,
    ): Map<Int, VisualDetection> {
        // The model is asked for a bare JSON object; tolerate it wrapping the object in prose
        // or a fenced block rather than discarding an otherwise good answer.
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap()

        return runCatching {
            val devices = JSONObject(raw.substring(start, end + 1)).optJSONArray("devices")
                ?: return@runCatching emptyMap<Int, VisualDetection>()

            buildMap {
                for (i in 0 until devices.length()) {
                    val entry = devices.optJSONObject(i) ?: continue
                    val index = entry.optInt("region", -1)
                    if (index !in considered.indices) continue

                    val ontologyLabel = entry.optString("label").takeIf {
                        it.isNotBlank() && DeviceOntology.forLabel(it) != null
                    }
                    val specific = entry.optString("specific_name").takeIf { it.isNotBlank() }
                    val confidence = entry.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f)
                    if (ontologyLabel == null && specific == null) continue

                    val base = considered[index]
                    put(
                        index,
                        base.copy(
                            label = ontologyLabel ?: base.label,
                            confidence = confidence,
                            displayNameOverride = specific
                                ?: DeviceOntology.forLabel(ontologyLabel ?: "")?.displayName,
                            rawLabel = specific ?: ontologyLabel,
                            identifiedByCloud = true,
                        ),
                    )
                }
            }
        }.getOrElse {
            Log.w(TAG, "Could not parse the identification response", it)
            emptyMap()
        }
    }

    /** Replace only the detections the model spoke about; leave the rest exactly as they were. */
    private fun mergeBack(
        original: List<VisualDetection>,
        enriched: Map<Int, VisualDetection>,
    ): List<VisualDetection> {
        val byBox = enriched.values.associateBy { it.boxImagePx }
        return original.map { byBox[it.boxImagePx] ?: it }
    }

    // ------------------------------------------------------------------ imaging

    private fun instruction(count: Int): String = """
        Identify the electronic device in each of the $count numbered regions above.

        For each region return:
          "region": the region number
          "label": one of the allowed labels below, or "" if none fits
          "specific_name": what you actually see, in a few words, naming make and model only
                           if they are legible in the image (e.g. "Wi-Fi router, three external
                           antennas" or "Netgear Nighthawk RAX50")
          "confidence": 0.0 to 1.0
          "visible_evidence": the specific visual details you used

        Allowed labels: ${DeviceOntology.labels.joinToString(", ")}

        Do not guess a make or model that is not legible in the image. If a region contains no
        electronic device, omit it entirely. Return only a JSON object of the form
        {"devices": [...]} with no other text.
    """.trimIndent()

    private fun cropOf(source: Bitmap, box: Rect): Bitmap? {
        val padX = (box.width() * 0.10f).roundToInt()
        val padY = (box.height() * 0.10f).roundToInt()
        val left = (box.left - padX).coerceIn(0, source.width - 1)
        val top = (box.top - padY).coerceIn(0, source.height - 1)
        val right = (box.right + padX).coerceIn(left + 1, source.width)
        val bottom = (box.bottom + padY).coerceIn(top + 1, source.height)
        if (right - left < 32 || bottom - top < 32) return null
        return runCatching { Bitmap.createBitmap(source, left, top, right - left, bottom - top) }
            .getOrNull()
    }

    /** Downscale before upload: image tokens dominate the cost and full resolution buys little. */
    private fun encodeJpeg(bitmap: Bitmap, maxWidth: Int): String? {
        if (bitmap.isRecycled) return null
        var scaled: Bitmap? = null
        return try {
            val source = if (bitmap.width > maxWidth) {
                val height = (bitmap.height.toFloat() * maxWidth / bitmap.width)
                    .toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true).also { scaled = it }
            } else bitmap

            val bytes = ByteArrayOutputStream(256 * 1024).use { stream ->
                source.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                stream.toByteArray()
            }
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not encode image for upload", e)
            null
        } finally {
            scaled?.recycle()
        }
    }

    private fun textBlock(text: String): ContentBlockParam =
        ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())

    private fun imageBlock(base64: String): ContentBlockParam =
        ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                        .data(base64)
                        .build()
                )
                .build()
        )

    private companion object {
        const val TAG = "CloudVisionEnricher"

        /** Beyond this the per-shot cost climbs without identifying much more. */
        const val MAX_CROPS_PER_SHOT = 6
        const val MAX_FRAME_WIDTH = 1024
        const val MAX_CROP_WIDTH = 448
        const val JPEG_QUALITY = 80

        val SYSTEM_PROMPT = """
            You identify electronic devices in photographs for an RF survey tool. The operator
            is documenting which devices are present in a space and what radio signals they are
            likely to emit.

            Be precise about what is visible and silent about what is not. Naming a specific
            model from a blurry rectangle is worse than useless here: the app attaches radio
            expectations to whatever you say, so a confident wrong answer becomes a confident
            wrong claim about the airwaves in someone's home. When you can only tell that
            something is a speaker, say it is a speaker.
        """.trimIndent()
    }
}
