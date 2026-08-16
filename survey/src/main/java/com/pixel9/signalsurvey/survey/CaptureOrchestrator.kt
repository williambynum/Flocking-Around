package com.pixel9.signalsurvey.survey

import android.graphics.Bitmap
import com.pixel9.signalsurvey.ar.FrozenMetadata
import com.pixel9.signalsurvey.fusion.FusionEngine
import com.pixel9.signalsurvey.fusion.FusionRequest
import com.pixel9.signalsurvey.model.RangeSource
import com.pixel9.signalsurvey.model.Shot
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.model.VisualTarget
import com.pixel9.signalsurvey.radio.RadioHub
import com.pixel9.signalsurvey.vision.StillImageClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface CaptureProgress {
    /** Pixels are frozen and on screen. Emitted immediately so the shutter feels instant. */
    data class Frozen(val bitmap: Bitmap) : CaptureProgress
    data class Listening(val elapsedMs: Long, val totalMs: Long, val heard: Int) : CaptureProgress
    data class Complete(val shot: Shot, val bitmap: Bitmap) : CaptureProgress
    data class Failed(val reason: String) : CaptureProgress
}

/**
 * Runs one shot.
 *
 * The trick that makes this feel right: pixels freeze at t=0 and appear instantly, but the
 * radios keep integrating for another six seconds behind the frozen image. A Wi-Fi scan
 * takes two to four seconds and is rate-limited; BLE needs a few seconds to hear the slower
 * advertisers; RTT ranging is a round trip per responder. Demanding all of that at shutter
 * speed would either block the UI or produce a much thinner answer.
 *
 * The heavy classifier runs concurrently with the listen window, so it is free.
 */
class CaptureOrchestrator(
    private val radioHub: RadioHub,
    private val classifier: StillImageClassifier,
    private val fusion: FusionEngine,
) {

    fun capture(
        shotIndex: Int,
        elapsedMs: Long,
        bitmap: Bitmap,
        metadata: FrozenMetadata,
        allocateTargetId: () -> Int,
        onRttFixes: (List<com.pixel9.signalsurvey.model.RttFix>) -> Unit,
    ): Flow<CaptureProgress> = channelFlow {

        send(CaptureProgress.Frozen(bitmap))

        val cameraWorld: Vec3 = metadata.camera.worldPosition

        // Vision runs alongside the radios rather than after them.
        val visionJob = async(Dispatchers.Default) {
            runCatching { classifier.detect(bitmap) }.getOrDefault(emptyList())
        }

        // Kick a fresh Wi-Fi scan at the top of the window so its results land inside it.
        radioHub.wifi.requestScan()

        // Classic Bluetooth inquiry only runs here: it saturates the 2.4 GHz radio, so it
        // must not be left running during the arming sweep where BLE density matters more.
        val classicJob = launch {
            if (radioHub.classicBt.isAvailable()) {
                runCatching { radioHub.classicBt.discover().collect { } }
            }
        }

        // RTT ranging from this standing position. Each shot contributes one vantage point;
        // three across a session is what turns ranges into a position.
        val rttJob = async {
            val responders = radioHub.wifi.rttCapableResults()
            if (responders.isEmpty()) emptyList()
            else radioHub.rtt.range(responders, cameraWorld, shotIndex)
        }

        val ticker = launch {
            var waited = 0L
            while (waited < LISTEN_WINDOW_MS) {
                delay(TICK_MS)
                waited += TICK_MS
                send(
                    CaptureProgress.Listening(
                        elapsedMs = waited,
                        totalMs = LISTEN_WINDOW_MS,
                        heard = radioHub.currentObservations().size,
                    )
                )
            }
        }

        ticker.join()
        classicJob.cancel()

        val rttFixes = rttJob.await()
        onRttFixes(rttFixes)

        // Fold the fresh ranges into the observations this shot will be annotated against.
        val rangeByKey = rttFixes.associateBy { it.key }
        val observations = radioHub.currentObservations().map { obs ->
            rangeByKey[obs.key.uppercase()]?.let { fix ->
                obs.copy(
                    measuredRangeM = fix.distanceM,
                    measuredRangeStdDevM = fix.stdDevM,
                    extras = obs.extras + mapOf(
                        "rttStdDevM" to "%.2f".format(fix.stdDevM),
                        "rttFromShot" to shotIndex.toString(),
                    ),
                )
            } ?: obs
        }

        val detections = visionJob.await()

        val targets: List<VisualTarget> = withContext(Dispatchers.Default) {
            detections.map { detection ->
                val centreX = detection.boxImagePx.exactCenterX()
                val centreY = detection.boxImagePx.exactCenterY()

                // Depth is sampled from the frozen map rather than a live hit test, because
                // by now the frame that produced it is long gone.
                val depthRange = metadata.depth?.metresAtViewNormalized(
                    centreX / bitmap.width,
                    centreY / bitmap.height,
                )

                val range = depthRange
                val source = if (depthRange != null) RangeSource.AR_DEPTH else RangeSource.ASSUMED

                val anchorWorld = metadata.camera.unprojectToRange(
                    imageX = centreX,
                    imageY = centreY,
                    imageW = bitmap.width,
                    imageH = bitmap.height,
                    rangeM = range ?: DEFAULT_RANGE_M,
                )

                fusion.annotate(
                    FusionRequest(
                        targetId = allocateTargetId(),
                        trackingId = detection.trackingId,
                        label = detection.label,
                        visualConfidence = detection.confidence,
                        boxImagePx = detection.boxImagePx,
                        anchorWorld = anchorWorld,
                        rangeM = range,
                        rangeSource = source,
                        camera = metadata.camera,
                        shotIndex = shotIndex,
                    ),
                    observations,
                )
            }
        }

        send(
            CaptureProgress.Complete(
                shot = Shot(
                    index = shotIndex,
                    capturedAtEpochMs = System.currentTimeMillis(),
                    elapsedMs = elapsedMs,
                    imagePath = null,
                    widthPx = bitmap.width,
                    heightPx = bitmap.height,
                    camera = metadata.camera,
                    depth = metadata.depth,
                    targets = targets,
                ),
                bitmap = bitmap,
            )
        )
        // channelFlow completes when this block returns - no awaitClose here.
    }

    companion object {
        /** Long enough for a Wi-Fi scan plus a meaningful BLE integration. */
        const val LISTEN_WINDOW_MS = 6_000L
        const val TICK_MS = 200L
        private const val DEFAULT_RANGE_M = 6f
    }
}
