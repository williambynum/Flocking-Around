package com.pixel9.signalsurvey.survey.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel9.signalsurvey.model.PositionMethod
import com.pixel9.signalsurvey.model.ResolvedEmitter
import com.pixel9.signalsurvey.model.SurveySession
import com.pixel9.signalsurvey.survey.SurveyViewModel

/**
 * The finished survey.
 *
 * Two views of the same data: the annotated shots, and the plan. The plan is the one that
 * justifies having taken more than one photo — it shows the space rather than a viewpoint,
 * with every located emitter placed whether or not a camera ever saw it.
 */
@Composable
fun SessionSummaryScreen(
    viewModel: SurveyViewModel,
    onNewSurvey: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session = state.session ?: return

    var showPlan by remember { mutableStateOf(true) }
    var selectedShot by remember { mutableIntStateOf(1) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .statusBarsPadding(),
    ) {
        SummaryHeader(session)

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToggleChip("Plan view", showPlan) { showPlan = true }
            ToggleChip("Shots (${session.shots.size})", !showPlan) { showPlan = false }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showPlan) {
                state.planView?.let { plan ->
                    ZoomableImage(plan, Modifier.fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        viewModel.annotatedShot(selectedShot)?.let { bitmap ->
                            ZoomableImage(bitmap, Modifier.fillMaxSize())
                        }
                    }
                    LazyRow(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(session.shots) { shot ->
                            Surface(
                                color = if (shot.index == selectedShot) Color(0xFF5CC8FF)
                                else Color(0xFF1A222C),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable { selectedShot = shot.index },
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(
                                        "#${shot.index}",
                                        color = if (shot.index == selectedShot) Color.Black
                                        else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        "${shot.targets.size} devices",
                                        color = if (shot.index == selectedShot) Color(0xCC000000)
                                        else Color(0xFF8B94A0),
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        EmitterList(session, Modifier.height(200.dp))

        state.exportResult?.let { result ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF14201A))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    "Saved to ${result.folderPath}",
                    color = Color(0xFF7FD4A8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${result.files.size} files · %.1f MB · open report.html to read it"
                        .format(result.totalBytes / 1_048_576f),
                    color = Color(0xFF8B94A0),
                    fontSize = 11.sp,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { viewModel.exportSession() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5CC8FF)),
            ) {
                Text(
                    if (state.exportResult == null) "Export bundle" else "Export again",
                    color = Color.Black,
                    fontWeight = FontWeight.Medium,
                )
            }
            OutlinedButton(onClick = onNewSurvey, modifier = Modifier.weight(1f)) {
                Text("New survey", color = Color.White)
            }
        }
    }
}

@Composable
private fun SummaryHeader(session: SurveySession) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(session.label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "%d shots · %.1f m walked · %d emitters heard · %d located".format(
                session.shots.size,
                session.pathLengthM(),
                session.observations.size,
                session.locatedEmitters.size,
            ),
            color = Color(0xFF8B94A0),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun EmitterList(session: SurveySession, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF11161D))
            .padding(horizontal = 16.dp),
    ) {
        item {
            Text(
                "EMITTERS",
                color = Color(0xFF8B94A0),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        items(session.emitters) { emitter -> EmitterRow(emitter) }
    }
}

@Composable
private fun EmitterRow(emitter: ResolvedEmitter) {
    val observation = emitter.observation
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(familyColor(observation.family))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                observation.displayName,
                color = Color(0xFFE4EAF0),
                fontSize = 13.sp,
                maxLines = 1,
            )
            Text(
                observation.standard + (observation.vendor?.let { " · $it" } ?: ""),
                color = Color(0xFF8B94A0),
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                observation.rssiDbm?.let { "$it dBm" } ?: "-",
                color = Color(0xFFB9C4D0),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                emitter.confidenceLabel,
                color = if (emitter.method.isMeasured) Color(0xFF7FD4A8) else Color(0xFF8B94A0),
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0xFF5CC8FF) else Color(0xFF1A222C),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color(0xFFB9C4D0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * Pinch-zoom over the rendered image. Annotations are baked into image space, so they scale
 * with the photo exactly — which is why they were drawn onto the bitmap rather than
 * overlaid as views.
 */
@Composable
private fun ZoomableImage(bitmap: android.graphics.Bitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
        )
    }
}
