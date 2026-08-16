package com.pixel9.signalsurvey.survey.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel9.signalsurvey.ar.ArSurfaceView
import com.pixel9.signalsurvey.model.RadioFamily
import com.pixel9.signalsurvey.survey.CaptureProgress
import com.pixel9.signalsurvey.survey.SurveyPhase
import com.pixel9.signalsurvey.survey.SurveyViewModel

/**
 * The arming and capture screen.
 *
 * Live annotation is deliberately minimal — thin boxes and a label, nothing more. The
 * detailed cards belong on the frozen shot, where there is room to read them and time to
 * have measured something worth reading.
 */
@Composable
fun SurveyScreen(
    viewModel: SurveyViewModel,
    onOpenSummary: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var surfaceView by remember { mutableStateOf<ArSurfaceView?>(null) }

    LaunchedEffect(Unit) {
        if (state.phase == SurveyPhase.IDLE) viewModel.startSession()
    }

    LaunchedEffect(state.phase == SurveyPhase.SUMMARY) {
        if (state.phase == SurveyPhase.SUMMARY) onOpenSummary()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                ArSurfaceView(ctx).also { view ->
                    surfaceView = view
                    (ctx as? Activity)?.display?.let { view.updateDisplayGeometry(it) }
                }
            },
            update = { view ->
                val session = viewModel.arController.session
                if (session != null) {
                    view.attach(session) { s, frame, w, h -> viewModel.onFrame(s, frame, w, h) }
                    // The session usually arrives after ON_RESUME has already fired, so the
                    // lifecycle observer alone would never start the renderer. Idempotent.
                    view.startRendering()
                }
            },
            // The only place the view is torn down: when Compose actually discards it.
            onRelease = { it.detach() },
        )

        // The GL thread must be started and stopped in lockstep with the ARCore session, and
        // in the right order: session resumed before the surface draws, surface stopped before
        // the session pauses. Without this the GL thread keeps calling update() after the
        // activity pauses and ARCore throws SessionPausedException on a background thread,
        // which is a process death rather than a caught error.
        //
        // MainActivity.onResume has already resumed the session by the time ON_RESUME reaches
        // here, so this ordering falls out naturally.
        // Keyed on the lifecycle owner ONLY. Keying it on surfaceView as well meant the effect
        // restarted the moment that state went null -> view, and its onDispose detached the
        // session a frame after rendering began — black screen, no tracking, no error.
        //
        // The view is read at event time rather than captured, and teardown of the view itself
        // belongs to AndroidView's onRelease, not here.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> surfaceView?.startRendering()
                    Lifecycle.Event.ON_PAUSE -> surfaceView?.stopRendering()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LiveDetectionOverlay(state.liveBoxes)

        if (state.cloud.settingsOpen) {
            CloudSettingsSheet(
                state = state.cloud,
                onAcceptDisclosure = { viewModel.acceptCloudDisclosure() },
                onSetApiKey = { viewModel.setCloudApiKey(it) },
                onSetEnabled = { viewModel.setCloudEnabled(it) },
                onForgetKey = { viewModel.forgetCloudKey() },
                onDismiss = { viewModel.closeCloudSettings() },
            )
        }

        // ---- top HUD ----
        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpectrumHud(state.summary.perFamily, state.summary.rttCapableAps)
            if (!state.hasClassifierModel) {
                HintChip(
                    "Generic labelling: devices are named on capture (monitor, speaker, " +
                        "laptop...). Add device_classifier.tflite to assets for RF-specific " +
                        "classes like routers and access points.",
                    Color(0xFF5CC8FF),
                )
            }
            // Heading only matters for GNSS sky projection, so this is informational,
            // never blocking. A bad compass costs you satellite markers and nothing else.
            if (state.needsCompassCalibration) {
                HintChip(
                    "Compass uncalibrated - move the phone in a figure of eight to " +
                        "enable satellite marking",
                    Color(0xFFFFA65C),
                )
            } else {
                state.heading?.let { heading ->
                    HintChip(
                        "Heading %s: +/-%.0f deg from %d samples (%d rejected as distorted)"
                            .format(
                                heading.quality,
                                heading.uncertaintyDeg,
                                heading.sampleCount,
                                heading.rejectedCount,
                            ),
                        if (heading.uncertaintyDeg < 10f) Color(0xFF7FD4A8) else Color(0xFFFFA65C),
                    )
                }
            }
            HintChip(
                if (state.cloud.enabled) "Cloud identification ON - shots are uploaded"
                else "Cloud identification off - tap to review",
                if (state.cloud.enabled) Color(0xFFFFA65C) else Color(0xFF8B94A0),
                onClick = { viewModel.openCloudSettings() },
            )
            state.message?.let { HintChip(it, Color(0xFF7FD4A8)) }
        }

        // ---- capture progress ----
        AnimatedVisibility(
            visible = state.phase == SurveyPhase.CAPTURING,
            modifier = Modifier.align(Alignment.Center),
        ) {
            CaptureIndicator(state.capture)
        }

        // ---- bottom controls ----
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.shotCount > 0) {
                ShotFilmstrip(state.shotCount) { index -> viewModel.thumbnail(index) }
            }

            ReadinessBar(
                hint = state.readiness.hint,
                depthCoverage = state.readiness.depthCoverage,
                pathLengthM = state.readiness.pathLengthM,
                trilaterationReady = state.trilaterationReady,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Spacer(Modifier.width(72.dp))

                ShutterButton(
                    enabled = state.readiness.ready && state.phase == SurveyPhase.ARMING,
                    onClick = { surfaceView?.let { viewModel.requestCapture(it) } },
                )

                Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                    if (state.shotCount > 0 && state.phase == SurveyPhase.ARMING) {
                        TextButton(onClick = { viewModel.finishSession() }) {
                            Text("Finish", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveDetectionOverlay(boxes: List<com.pixel9.signalsurvey.survey.LiveBox>) {
    Canvas(Modifier.fillMaxSize()) {
        boxes.forEach { box ->
            drawRoundRect(
                color = Color(0xFF5CC8FF),
                topLeft = Offset(box.rect.left, box.rect.top),
                size = Size(box.rect.width(), box.rect.height()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                style = Stroke(width = 3f),
            )
            drawCorners(box.rect.left, box.rect.top, box.rect.width(), box.rect.height())
        }
    }
}

/** Corner ticks read as "tracking" far better than a plain rectangle does. */
private fun DrawScope.drawCorners(left: Float, top: Float, width: Float, height: Float) {
    val len = minOf(width, height) * 0.18f
    val color = Color(0xFF5CC8FF)
    val stroke = 5f
    listOf(
        Triple(Offset(left, top), Offset(left + len, top), Offset(left, top + len)),
        Triple(Offset(left + width, top), Offset(left + width - len, top), Offset(left + width, top + len)),
        Triple(Offset(left, top + height), Offset(left + len, top + height), Offset(left, top + height - len)),
        Triple(
            Offset(left + width, top + height),
            Offset(left + width - len, top + height),
            Offset(left + width, top + height - len),
        ),
    ).forEach { (corner, horizontal, vertical) ->
        drawLine(color, corner, horizontal, strokeWidth = stroke)
        drawLine(color, corner, vertical, strokeWidth = stroke)
    }
}

@Composable
private fun SpectrumHud(perFamily: Map<RadioFamily, Int>, rttCapable: Int) {
    Surface(
        color = Color(0xCC0B0F14),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "LIVE SPECTRUM",
                color = Color(0xFF8B94A0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            perFamily.entries.sortedByDescending { it.value }.forEach { (family, count) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(familyColor(family))
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "$count  ${family.label}",
                        color = Color(0xFFD5DEE8),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (rttCapable > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "$rttCapable rangeable (802.11mc)",
                    color = Color(0xFF7FD4A8),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ReadinessBar(
    hint: String,
    depthCoverage: Float,
    pathLengthM: Float,
    trilaterationReady: Int,
) {
    Surface(color = Color(0xB30B0F14), shape = RoundedCornerShape(20.dp)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(hint, color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                "depth %.0f%%  ·  walked %.1f m%s".format(
                    depthCoverage * 100,
                    pathLengthM,
                    if (trilaterationReady > 0) "  ·  $trilaterationReady locatable" else "",
                ),
                color = Color(0xFF8B94A0),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.White else Color(0x40FFFFFF))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (enabled) Color(0xFF5CC8FF) else Color(0x30FFFFFF))
        )
    }
}

@Composable
private fun CaptureIndicator(progress: CaptureProgress?) {
    Surface(color = Color(0xCC0B0F14), shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (progress) {
                is CaptureProgress.Listening -> {
                    Text("Listening", color = Color.White, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.elapsedMs.toFloat() / progress.totalMs },
                        modifier = Modifier.width(180.dp),
                        color = Color(0xFF5CC8FF),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${progress.heard} emitters heard",
                        color = Color(0xFF8B94A0),
                        fontSize = 12.sp,
                    )
                }
                is CaptureProgress.Failed -> Text(progress.reason, color = Color(0xFFFF8B8B))
                else -> {
                    CircularProgressIndicator(color = Color(0xFF5CC8FF))
                    Spacer(Modifier.height(10.dp))
                    Text("Resolving", color = Color(0xFF8B94A0), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ShotFilmstrip(count: Int, thumbnail: (Int) -> android.graphics.Bitmap?) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((1..count).toList()) { index ->
            Box(
                Modifier
                    .size(width = 44.dp, height = 62.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x40FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = thumbnail(index)
                if (bitmap != null && !bitmap.isRecycled) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Shot $index",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    "$index",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color(0x99000000))
                        .padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun HintChip(text: String, accent: Color, onClick: (() -> Unit)? = null) {
    Surface(
        color = Color(0xCC0B0F14),
        shape = RoundedCornerShape(10.dp),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color(0xFFD5DEE8), fontSize = 11.sp)
        }
    }
}

internal fun familyColor(family: RadioFamily): Color = when (family) {
    RadioFamily.WIFI -> Color(0xFF5CC8FF)
    RadioFamily.BLUETOOTH -> Color(0xFF9B8CFF)
    RadioFamily.CELLULAR -> Color(0xFFFFA65C)
    RadioFamily.GNSS -> Color(0xFFFFE066)
    RadioFamily.NETWORK_SERVICE -> Color(0xFF7FD4A8)
    else -> Color(0xFF8B94A0)
}
