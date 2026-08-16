package com.pixel9.signalsurvey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pixel9.signalsurvey.survey.SurveyPhase
import com.pixel9.signalsurvey.survey.SurveyViewModel
import com.pixel9.signalsurvey.survey.ui.SessionSummaryScreen
import com.pixel9.signalsurvey.survey.ui.SurveyScreen
import com.pixel9.signalsurvey.ui.theme.SignalSurveyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SurveyViewModel by viewModels()

    /** ARCore may ask to install or update itself; that flow needs a second resume. */
    private var userRequestedArInstall = true

    private var permissionsGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results[Permissions.REQUIRED_CAMERA] ?: Permissions.hasCamera(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionsGranted = Permissions.hasCamera(this)

        setContent {
            SignalSurveyTheme {
                KeepScreenOn()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                when {
                    !permissionsGranted -> PermissionGate(
                        missing = Permissions.missing(this),
                        onRequest = { permissionLauncher.launch(Permissions.CORE) },
                    )

                    state.phase == SurveyPhase.ERROR -> ErrorPane(state.message)

                    state.phase == SurveyPhase.SUMMARY -> SessionSummaryScreen(
                        viewModel = viewModel,
                        onNewSurvey = { viewModel.startSession() },
                    )

                    else -> SurveyScreen(viewModel = viewModel, onOpenSummary = { })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Permissions.hasCamera(this)) {
            permissionLauncher.launch(Permissions.CORE)
            return
        }
        permissionsGranted = true
        viewModel.displayRotation = display?.rotation ?: 0
        // requestInstall must only pass true once per resume cycle, or ARCore loops.
        viewModel.onResume(this, userRequestedArInstall)
        userRequestedArInstall = false
    }

    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
}

/**
 * Holds the display awake for as long as the app is in the foreground.
 *
 * A survey is a slow, two-handed job — sweep the room, walk to a new position, sweep again —
 * and long stretches of it involve no touching of the screen at all. Letting the display time
 * out mid-sweep does not merely annoy: the ARCore session pauses with it, so tracking, the
 * accumulated depth, and the world frame every shot is anchored to are all lost.
 *
 * Uses the View flag rather than a WakeLock: it needs no permission, is scoped to this view
 * being attached and visible, and Android drops it automatically when the app backgrounds —
 * so it cannot leak and flatten the battery from the background.
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun PermissionGate(missing: List<String>, onRequest: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Signal Survey",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Maps the RF devices around you onto photographs, and says plainly which " +
                "signals were measured and which are only inferred.",
            color = Color(0xFFB9C4D0),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(28.dp))

        missing.forEach { permission ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(
                    Modifier
                        .padding(top = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF5CC8FF))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        permission.substringAfterLast('.').replace('_', ' ').lowercase()
                            .replaceFirstChar { it.uppercase() },
                        color = Color(0xFFE4EAF0),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        Permissions.consequenceOf(permission),
                        color = Color(0xFF8B94A0),
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRequest,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5CC8FF)),
        ) {
            Text("Grant permissions", color = Color.Black, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Scan data stays on the device. Nothing is uploaded.",
            color = Color(0xFF6B7480),
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ErrorPane(message: String?) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F14))
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("AR unavailable", color = Color(0xFFFF8B8B), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            message ?: "The AR session could not be started.",
            color = Color(0xFFB9C4D0),
            fontSize = 14.sp,
        )
    }
}
