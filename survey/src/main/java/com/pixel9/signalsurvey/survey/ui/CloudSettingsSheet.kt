package com.pixel9.signalsurvey.survey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pixel9.signalsurvey.survey.CloudUiState

/**
 * The opt-in surface for cloud identification.
 *
 * Structured so the disclosure cannot be skipped: the toggle stays disabled until the operator
 * has read what gets uploaded and said yes. That ordering is the point of the screen — a
 * feature that uploads photographs of someone's home should not be reachable by tapping a
 * switch you happened to brush past.
 */
@Composable
fun CloudSettingsSheet(
    state: CloudUiState,
    onAcceptDisclosure: () -> Unit,
    onSetApiKey: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onForgetKey: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF11161D))
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
        ) {
            Text("Cloud identification", color = Color.White, fontSize = 20.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Optional. On-device labelling already names categories — monitor, speaker, " +
                    "laptop. A cloud vision model can often name the actual device, which is " +
                    "what makes the RF profiles specific.",
                color = Color(0xFFB9C4D0), fontSize = 13.sp,
            )

            Spacer(Modifier.height(18.dp))

            // ---- disclosure ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1A222C))
                    .padding(16.dp),
            ) {
                Text("What leaves your device", color = Color(0xFFFFA65C), fontSize = 13.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                listOf(
                    "Photographs of whatever you are surveying — your home, office, or a " +
                        "client site — are uploaded to Anthropic's API.",
                    "Downscaled copies of the full frame plus up to six cropped regions per " +
                        "shot. Roughly 2 cents per shot on Claude Opus 5.",
                    "No RF data, no MAC addresses, no location, and no survey results are " +
                        "sent. Images only.",
                    "This needs a network connection at capture time. Everything else in the " +
                        "app works offline and stays offline.",
                    "Your API key is stored encrypted on this device via the Android Keystore " +
                        "and is sent only to Anthropic.",
                ).forEach { line ->
                    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 6.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF8B94A0))
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(line, color = Color(0xFFB9C4D0), fontSize = 12.sp)
                    }
                }

                if (!state.disclosureAccepted) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = onAcceptDisclosure,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFA65C)),
                    ) {
                        Text("I understand", color = Color.Black, fontWeight = FontWeight.Medium)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- key ----
            var keyInput by remember { mutableStateOf("") }
            Text("Anthropic API key", color = Color(0xFFE4EAF0), fontSize = 13.sp,
                fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.hasApiKey) "Stored: ${state.maskedKey}"
                else "Create one at console.anthropic.com. Billing is on your own account.",
                color = Color(0xFF8B94A0), fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                singleLine = true,
                placeholder = { Text("sk-ant-…", color = Color(0xFF6B7480)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSetApiKey(keyInput); keyInput = "" },
                    enabled = keyInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5CC8FF)),
                ) {
                    Text("Save key", color = Color.Black)
                }
                if (state.hasApiKey) {
                    OutlinedButton(onClick = onForgetKey) {
                        Text("Forget key", color = Color(0xFFFF8B8B))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ---- toggle ----
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Upload shots for identification",
                        color = if (state.unavailableReason == null) Color.White
                        else Color(0xFF6B7480),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    state.unavailableReason?.let {
                        Text(it, color = Color(0xFFFFA65C), fontSize = 11.sp)
                    }
                }
                Switch(
                    checked = state.enabled,
                    onCheckedChange = onSetEnabled,
                    enabled = state.unavailableReason == null || state.enabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color(0xFF5CC8FF),
                    ),
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Cloud-identified devices are labelled as such on every annotated shot and in " +
                    "the exported data, so a reader can always tell which names came off the " +
                    "device and which did not.",
                color = Color(0xFF6B7480), fontSize = 11.sp,
            )

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Done", color = Color(0xFF5CC8FF)) }
            }
        }
    }
}
