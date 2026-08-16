package com.pixel9.signalsurvey.survey

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.pixel9.signalsurvey.ar.ArSessionController
import com.pixel9.signalsurvey.ar.FrozenMetadata
import com.pixel9.signalsurvey.ar.HeadingResolver
import com.pixel9.signalsurvey.ar.HeadingSolution
import com.pixel9.signalsurvey.ar.SnapshotCapturer
import com.pixel9.signalsurvey.export.AnnotationRenderer
import com.pixel9.signalsurvey.export.ExportResult
import com.pixel9.signalsurvey.export.PlanViewRenderer
import com.pixel9.signalsurvey.export.SessionExporter
import com.pixel9.signalsurvey.fusion.FusionEngine
import com.pixel9.signalsurvey.fusion.SessionResolver
import com.pixel9.signalsurvey.model.RadioSummary
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.model.Vec3
import com.pixel9.signalsurvey.radio.RadioHub
import com.pixel9.signalsurvey.vision.DeviceDetector
import com.pixel9.signalsurvey.vision.RotationMapping
import com.pixel9.signalsurvey.vision.StillImageClassifier
import com.pixel9.signalsurvey.vision.VisualDetection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class SurveyPhase { IDLE, ARMING, CAPTURING, SUMMARY, ERROR }

data class LiveBox(val rect: RectF, val label: String, val confidence: Float)

/**
 * Whether the operator has moved enough for a capture to be worth taking.
 *
 * This gate is the single most important piece of UX in the app. On a base Pixel 9 there is
 * no ToF sensor, so depth comes from motion — and the same motion is what gives RSSI
 * gradient localisation and multi-vantage RTT anything to work with. Letting someone shoot
 * from a standstill produces a photo with no depth, no ranges and no positions, and they
 * would rightly blame the app rather than their feet.
 */
data class Readiness(
    val tracking: Boolean,
    val depthCoverage: Float,
    val pathLengthM: Float,
    val shotCount: Int,
) {
    val ready: Boolean
        get() = tracking && (depthCoverage > MIN_DEPTH_COVERAGE || pathLengthM > MIN_PATH_M)

    val hint: String
        get() = when {
            !tracking -> "Point at the room and move slowly - AR tracking is not established"
            depthCoverage < MIN_DEPTH_COVERAGE && pathLengthM < MIN_PATH_M ->
                "Pan slowly across the area to build depth"
            shotCount == 0 -> "Ready. Capture your first shot"
            shotCount < 3 -> "Walk a few metres and capture again from a new position"
            else -> "Ready. More viewpoints improve emitter positions"
        }

    companion object {
        const val MIN_DEPTH_COVERAGE = 0.25f
        const val MIN_PATH_M = 1.5f
    }
}

data class SurveyUiState(
    val phase: SurveyPhase = SurveyPhase.IDLE,
    val message: String? = null,
    val readiness: Readiness = Readiness(false, 0f, 0f, 0),
    val summary: RadioSummary = RadioSummary.EMPTY,
    val liveBoxes: List<LiveBox> = emptyList(),
    val shotCount: Int = 0,
    val capture: CaptureProgress? = null,
    val session: SurveySession? = null,
    val planView: Bitmap? = null,
    val exportResult: ExportResult? = null,
    val trilaterationReady: Int = 0,
    val hasClassifierModel: Boolean = false,
    /** Null until enough clean magnetometer samples have accumulated along the sweep. */
    val heading: HeadingSolution? = null,
    val needsCompassCalibration: Boolean = false,
)

class SurveyViewModel(app: Application) : AndroidViewModel(app) {

    // --- collaborators ---------------------------------------------------------

    val arController = ArSessionController(app)
    private val capturer = SnapshotCapturer()
    private val heading = HeadingResolver(app)
    private val detector = DeviceDetector(app)
    private val stillClassifier = StillImageClassifier(app)
    private val imageStore = ShotImageStore(app)
    private val annotationRenderer = AnnotationRenderer()
    private val planRenderer = PlanViewRenderer()
    private val exporter = SessionExporter(app)

    private val radioHub = RadioHub(app) { builder?.lastCameraWorld }
    private val fusion = FusionEngine { profileId ->
        radioHub.capabilities.unavailabilityReason(profileId)
    }
    private val orchestrator = CaptureOrchestrator(radioHub, stillClassifier, fusion)

    // --- state -----------------------------------------------------------------

    private val _uiState = MutableStateFlow(SurveyUiState())
    val uiState: StateFlow<SurveyUiState> = _uiState.asStateFlow()

    private var builder: SurveySessionBuilder? = null
    private val annotatedShots = HashMap<Int, Bitmap>()

    private val captureRequested = AtomicBoolean(false)
    @Volatile private var pendingMetadata: FrozenMetadata? = null
    @Volatile private var sensorOrientation: Int = 90
    @Volatile private var lastDepthCoverage: Float = 0f
    @Volatile private var cameraImageSize: Pair<Int, Int>? = null
    /** IMAGE_PIXELS -> VIEW affine, refreshed each frame so async results can still be mapped. */
    @Volatile private var imageToView: FloatArray? = null
    private var lastInferenceMs = 0L
    private var lastDepthSampleMs = 0L

    /**
     * Refreshed off the frame loop. The heading resolver needs it for magnetic declination
     * and for the expected field strength that drives distortion rejection; without it the
     * result is magnetic north, which is up to ~20 degrees off depending where you stand.
     */
    @Volatile private var cachedLocation: com.pixel9.signalsurvey.model.GeoFix? = null

    init {
        _uiState.value = _uiState.value.copy(hasClassifierModel = detector.hasCustomModel)
        viewModelScope.launch {
            radioHub.summary.collect { summary ->
                _uiState.value = _uiState.value.copy(summary = summary)
            }
        }
        viewModelScope.launch {
            while (true) {
                cachedLocation = radioHub.gnss.lastKnownLocation()
                _uiState.value = _uiState.value.copy(
                    heading = heading.solution(),
                    needsCompassCalibration = heading.needsCalibration,
                )
                kotlinx.coroutines.delay(LOCATION_REFRESH_MS)
            }
        }
    }

    // --- lifecycle -------------------------------------------------------------

    fun onResume(activity: Activity, userRequestedInstall: Boolean): Boolean {
        if (!arController.createSession(activity, userRequestedInstall)) {
            arController.lastFailure?.let { failure ->
                _uiState.value = _uiState.value.copy(phase = SurveyPhase.ERROR, message = failure)
            }
            return false
        }
        if (!arController.resume()) {
            _uiState.value = _uiState.value.copy(
                phase = SurveyPhase.ERROR,
                message = arController.lastFailure ?: "Could not start the AR session",
            )
            return false
        }
        readSensorOrientation(activity)
        heading.start()
        radioHub.start(viewModelScope)
        return true
    }

    fun onPause() {
        heading.stop()
        arController.pause()
    }

    override fun onCleared() {
        radioHub.stop()
        detector.close()
        stillClassifier.close()
        arController.close()
        annotatedShots.values.forEach { it.recycle() }
        annotatedShots.clear()
    }

    private fun readSensorOrientation(activity: Activity) {
        runCatching {
            val cameraId = arController.session?.cameraConfig?.cameraId ?: return
            val manager = activity.getSystemService(CameraManager::class.java)
            sensorOrientation = manager
                .getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        }.onFailure { Log.w(TAG, "Falling back to a 90 degree sensor orientation") }
    }

    // --- session control -------------------------------------------------------

    fun startSession(label: String = defaultLabel()) {
        builder?.let { imageStore.clear(it.id) }
        annotatedShots.values.forEach { it.recycle() }
        annotatedShots.clear()
        radioHub.reset()
        // Heading is per-session: the world frame it is expressed against is recreated
        // whenever the AR session restarts, so carrying the old offset over would be wrong.
        heading.reset()
        builder = SurveySessionBuilder(label, radioHub.capabilities.describe())
        _uiState.value = _uiState.value.copy(
            phase = SurveyPhase.ARMING,
            message = null,
            shotCount = 0,
            session = null,
            planView = null,
            exportResult = null,
            capture = null,
        )
    }

    /** Request a capture. Fulfilled on the next AR frame so the pixels match the metadata. */
    fun requestCapture(surfaceView: SurfaceView) {
        val active = builder ?: return
        if (_uiState.value.phase != SurveyPhase.ARMING) return
        if (!_uiState.value.readiness.ready) return

        captureRequested.set(true)
        _uiState.value = _uiState.value.copy(phase = SurveyPhase.CAPTURING)

        viewModelScope.launch {
            // Wait for the GL thread to hand back metadata for a tracked frame.
            val metadata = awaitMetadata() ?: run {
                _uiState.value = _uiState.value.copy(
                    phase = SurveyPhase.ARMING,
                    capture = CaptureProgress.Failed("Tracking was lost - hold steadier"),
                )
                return@launch
            }

            val bitmap = capturer.copyPixels(surfaceView) ?: run {
                _uiState.value = _uiState.value.copy(
                    phase = SurveyPhase.ARMING,
                    capture = CaptureProgress.Failed("Could not read the camera surface"),
                )
                return@launch
            }

            val shotIndex = active.shotCount + 1
            orchestrator.capture(
                shotIndex = shotIndex,
                elapsedMs = active.elapsedMs(),
                bitmap = bitmap,
                metadata = metadata,
                allocateTargetId = { active.allocateTargetId() },
                onRttFixes = { active.addRttFixes(it) },
            ).collect { progress ->
                _uiState.value = _uiState.value.copy(capture = progress)
                if (progress is CaptureProgress.Complete) {
                    active.addShot(progress.shot)
                    imageStore.put(active.id, progress.shot.index, progress.bitmap)
                    progress.bitmap.recycle()
                    _uiState.value = _uiState.value.copy(
                        phase = SurveyPhase.ARMING,
                        shotCount = active.shotCount,
                        trilaterationReady = active.trilaterationReadyCount(),
                    )
                }
            }
        }
    }

    private suspend fun awaitMetadata(timeoutMs: Long = 2_000L): FrozenMetadata? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pendingMetadata?.let {
                pendingMetadata = null
                return it
            }
            kotlinx.coroutines.delay(16)
        }
        captureRequested.set(false)
        return null
    }

    /**
     * Close the survey: resolve emitters across every shot, re-render each shot with the
     * full picture, build the plan view, and export.
     *
     * Re-rendering matters. Shot #1 was annotated with what was known six seconds after it
     * was taken; by the end of the session an access point may have been trilaterated from
     * ranges gathered during shots 3 and 4, and shot #1 should show it.
     */
    fun finishSession() {
        val active = builder ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(phase = SurveyPhase.CAPTURING, message = "Resolving...")

            val session = withContext(Dispatchers.Default) {
                active.finalise(
                    observations = radioHub.observations.value,
                    rssiSamples = radioHub.rssiSamples(),
                    satellites = radioHub.satellites.value,
                    location = radioHub.gnss.lastKnownLocation(),
                )
            }

            val rendered = withContext(Dispatchers.Default) {
                session.shots.mapNotNull { shot ->
                    val base = imageStore.load(session.id, shot.index) ?: return@mapNotNull null
                    val visible = SessionResolver.emittersVisibleIn(shot, session.emitters)
                    val annotated = annotationRenderer.render(
                        shot = shot,
                        base = base,
                        targets = shot.targets,
                        visibleEmitters = visible,
                        unlocated = SessionResolver.unlocatedFor(session).take(24),
                        sessionLabel = session.label,
                        satellites = session.satellites,
                    )
                    base.recycle()
                    shot.index to annotated
                }.toMap()
            }

            annotatedShots.putAll(rendered)
            val plan = withContext(Dispatchers.Default) { planRenderer.render(session) }

            _uiState.value = _uiState.value.copy(
                phase = SurveyPhase.SUMMARY,
                message = null,
                session = session,
                planView = plan,
            )
        }
    }

    fun exportSession() {
        val session = _uiState.value.session ?: return
        viewModelScope.launch {
            val result = exporter.export(session, annotatedShots, _uiState.value.planView)
            _uiState.value = _uiState.value.copy(
                exportResult = result,
                message = buildString {
                    append("Exported ").append(result.files.size).append(" files (")
                    append("%.1f MB".format(result.totalBytes / 1_048_576f)).append(") to ")
                    append(result.folderPath)
                    if (result.usedFallback) append(" - Downloads was unavailable")
                },
            )
        }
    }

    fun annotatedShot(index: Int): Bitmap? = annotatedShots[index]

    fun thumbnail(index: Int): Bitmap? = imageStore.thumbnail(index)

    // --- AR frame loop (GL thread) ---------------------------------------------

    /**
     * Called on the GL thread once per rendered frame. Everything here is bounded work:
     * inference is throttled and asynchronous, and the capture path only copies matrices.
     */
    fun onFrame(session: Session, frame: Frame, viewWidth: Int, viewHeight: Int) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val active = builder

        if (tracking && active != null) {
            active.recordCameraPosition(Vec3.of(camera.pose.translation))
        }

        cacheImageToViewTransform(frame)

        // Feed the heading resolver. It self-throttles on movement and rotation, so calling
        // every frame is cheap and the sweep the operator is already doing for depth
        // doubles as the magnetic survey.
        if (tracking) {
            heading.recordSample(
                cameraForward = forwardOf(frame),
                cameraPosition = Vec3.of(camera.pose.translation),
                location = cachedLocation,
            )
        }

        if (captureRequested.get() && tracking) {
            val metadata = capturer.grabMetadata(frame, viewWidth, viewHeight, heading.solution())
            if (metadata != null) {
                lastDepthCoverage = metadata.depth?.coverage() ?: lastDepthCoverage
                pendingMetadata = metadata
                captureRequested.set(false)
            }
        }

        maybeSampleDepth(frame)
        maybeRunInference(frame, viewWidth, viewHeight)
        publishReadiness(tracking, active)
    }

    private fun maybeSampleDepth(frame: Frame) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDepthSampleMs < DEPTH_SAMPLE_INTERVAL_MS) return
        lastDepthSampleMs = now
        sampleDepthCoverage(frame)
    }

    private fun maybeRunInference(frame: Frame, viewWidth: Int, viewHeight: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastInferenceMs < INFERENCE_INTERVAL_MS) return
        if (_uiState.value.phase != SurveyPhase.ARMING) return
        lastInferenceMs = now

        val image = try {
            frame.acquireCameraImage()
        } catch (e: NotYetAvailableException) {
            return
        } catch (e: Exception) {
            return
        }

        cameraImageSize = image.width to image.height
        val rotation = RotationMapping.forBackCamera(displayRotation, sensorOrientation)

        detector.analyze(image, rotation) { detections ->
            _uiState.value = _uiState.value.copy(
                liveBoxes = detections.mapNotNull { it.toLiveBox(viewWidth, viewHeight) }
            )
        }
    }

    private fun VisualDetection.toLiveBox(viewWidth: Int, viewHeight: Int): LiveBox? {
        val affine = imageToView ?: return null
        fun map(x: Float, y: Float) = floatArrayOf(
            affine[0] + x * affine[2] + y * affine[4],
            affine[1] + x * affine[3] + y * affine[5],
        )

        val topLeft = map(boxImagePx.left.toFloat(), boxImagePx.top.toFloat())
        val bottomRight = map(boxImagePx.right.toFloat(), boxImagePx.bottom.toFloat())

        val rect = RectF(
            minOf(topLeft[0], bottomRight[0]),
            minOf(topLeft[1], bottomRight[1]),
            maxOf(topLeft[0], bottomRight[0]),
            maxOf(topLeft[1], bottomRight[1]),
        )
        if (rect.right < 0 || rect.bottom < 0 ||
            rect.left > viewWidth || rect.top > viewHeight
        ) return null

        return LiveBox(rect, label, confidence)
    }

    /**
     * Detections arrive after the frame that produced them has been recycled, so the
     * IMAGE_PIXELS -> VIEW mapping has to be captured while the frame is alive. It is a crop
     * plus a rotation, so three points describe it exactly.
     */
    private fun cacheImageToViewTransform(frame: Frame) {
        val (imageW, imageH) = cameraImageSize ?: return
        runCatching {
            val src = floatArrayOf(0f, 0f, imageW.toFloat(), 0f, 0f, imageH.toFloat())
            val dst = FloatArray(6)
            frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, src, Coordinates2d.VIEW, dst)
            imageToView = floatArrayOf(
                dst[0], dst[1],
                (dst[2] - dst[0]) / imageW, (dst[3] - dst[1]) / imageW,
                (dst[4] - dst[0]) / imageH, (dst[5] - dst[1]) / imageH,
            )
        }
    }

    private fun forwardOf(frame: Frame): Vec3 {
        val z = frame.camera.pose.zAxis   // ARCore camera looks along -Z
        return Vec3(-z[0], -z[1], -z[2]).normalized()
    }

    private fun publishReadiness(tracking: Boolean, active: SurveySessionBuilder?) {
        val readiness = Readiness(
            tracking = tracking,
            depthCoverage = lastDepthCoverage,
            pathLengthM = active?.pathLengthM() ?: 0f,
            shotCount = active?.shotCount ?: 0,
        )
        val current = _uiState.value
        if (current.readiness != readiness) {
            _uiState.value = current.copy(readiness = readiness)
        }
    }

    /** Set from the Activity so the ML rotation is right in landscape. */
    @Volatile var displayRotation: Int = 0

    /** Sample the depth map during arming so the readiness gauge reflects reality. */
    fun sampleDepthCoverage(frame: Frame) {
        runCatching {
            frame.acquireDepthImage16Bits().use { image ->
                val plane = image.planes[0]
                val buffer = plane.buffer
                var filled = 0
                var total = 0
                var i = 0
                while (i + 1 < buffer.limit()) {
                    val value = (buffer.get(i).toInt() and 0xFF) or
                        ((buffer.get(i + 1).toInt() and 0x1F) shl 8)
                    if (value > 0) filled++
                    total++
                    i += 2 * DEPTH_SAMPLE_STRIDE
                }
                if (total > 0) lastDepthCoverage = filled.toFloat() / total
            }
        }
    }

    private fun defaultLabel(): String =
        "Survey " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    private companion object {
        const val TAG = "SurveyViewModel"
        const val INFERENCE_INTERVAL_MS = 120L
        const val LOCATION_REFRESH_MS = 3_000L
        /** The readiness gauge does not need to be recomputed at frame rate. */
        const val DEPTH_SAMPLE_INTERVAL_MS = 500L
        /** Sampling every Nth depth pixel is plenty for a coverage percentage. */
        const val DEPTH_SAMPLE_STRIDE = 4
    }
}
