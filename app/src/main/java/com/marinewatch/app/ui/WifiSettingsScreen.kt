package com.marinewatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.marinewatch.app.MainViewModel

// Colours reuse the same palette as MarineDisplay
private val ColorBackground   = Color(0xFF000000)
private val ColorAccent       = Color(0xFF00BFFF)
private val ColorLabel        = Color(0xFF607D8B)
private val ColorValue        = Color(0xFFECEFF1)
private val ColorWarning      = Color(0xFFFFC107)
private val ColorSuccess      = Color(0xFF4CAF50)
private val ColorDanger       = Color(0xFFF44336)

/**
 * WiFi settings screen displayed on the Wear OS watch.
 *
 * Allows the user to:
 *  - View the current WiFi mode, SSID and IP address (from AdminData)
 *  - Switch to infrastructure mode (wifi_sta) with custom SSID + password
 *  - Switch to access point mode (wifi_ap) with custom SSID + password
 *
 * After sending a command the device reboots (~3 s) and reconnects automatically.
 * A feedback message is shown to inform the user of the outcome.
 *
 * @param viewModel   Provides [AdminData] and send-command helpers.
 * @param isAmbient   When true, the screen is dimmed (ambient/AOD mode).
 * @param onBack      Callback to return to the main / settings screen.
 */
@Composable
fun WifiSettingsScreen(
    viewModel: MainViewModel,
    isAmbient: Boolean = false,
    onBack: () -> Unit
) {
    val adminData by viewModel.adminData.collectAsState()

    // ---- Form state ----
    var mode by remember { mutableStateOf(WifiMode.STA) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    // Feedback message shown after a command is sent
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- Title ----
            item {
                Text(
                    text = "WiFi Settings",
                    color = ColorAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )
            }

            // ---- Current status ----
            item {
                CurrentWifiStatus(adminData = adminData, isAmbient = isAmbient)
            }

            // ---- Mode selector ----
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeChip(
                        label = "STA",
                        selected = mode == WifiMode.STA,
                        onClick = { mode = WifiMode.STA }
                    )
                    ModeChip(
                        label = "AP",
                        selected = mode == WifiMode.AP,
                        onClick = { mode = WifiMode.AP }
                    )
                }
            }

            // ---- SSID field ----
            item {
                SettingsTextField(
                    label = "SSID",
                    value = ssid,
                    onValueChange = { ssid = it },
                    placeholder = if (mode == WifiMode.STA) "Network name" else "AP name"
                )
            }

            // ---- Password field ----
            item {
                SettingsTextField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = if (mode == WifiMode.STA) "Password" else "Min 8 chars",
                    isPassword = !showPassword
                )
            }

            // ---- Show/hide password toggle ----
            item {
                CompactChip(
                    onClick = { showPassword = !showPassword },
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text(
                            text = if (showPassword) "Hide password" else "Show password",
                            fontSize = 10.sp,
                            color = ColorLabel
                        )
                    }
                )
            }

            // ---- Send button ----
            item {
                val buttonLabel = when (mode) {
                    WifiMode.STA -> "Apply STA"
                    WifiMode.AP  -> "Apply AP"
                }
                Chip(
                    onClick = {
                        if (ssid.isBlank()) {
                            feedbackMessage = "SSID cannot be empty"
                            return@Chip
                        }
                        val sent = when (mode) {
                            WifiMode.STA -> viewModel.sendWifiSta(ssid.trim(), password)
                            WifiMode.AP  -> viewModel.sendWifiAp(ssid.trim(), password)
                        }
                        feedbackMessage = if (sent) {
                            "Command sent.\nDevice will reboot."
                        } else {
                            "Send failed.\nCheck BLE connection."
                        }
                    },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = ColorAccent
                    ),
                    modifier = Modifier.fillMaxWidth(0.85f),
                    label = {
                        Text(
                            text = buttonLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                )
            }

            // ---- Feedback message ----
            feedbackMessage?.let { msg ->
                item {
                    val isError = msg.startsWith("Send failed") || msg.startsWith("SSID")
                    Text(
                        text = msg,
                        color = if (isError) ColorWarning else ColorSuccess,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            }

            // ---- Back button ----
            item {
                CompactChip(
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = {
                        Text("← Back", fontSize = 11.sp, color = ColorLabel)
                    }
                )
            }
        }
    }
}

// ----------------------------------------------------------------
// Current status card
// ----------------------------------------------------------------

@Composable
private fun CurrentWifiStatus(
    adminData: com.marinewatch.app.data.AdminData,
    isAmbient: Boolean
) {
    val labelColor = if (isAmbient) Color(0xFF455A64) else ColorLabel
    val valueColor = if (isAmbient) Color(0xFF90A4AE) else ColorValue

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        StatusRow(
            label = "Mode",
            value = adminData.wifiMode?.uppercase() ?: "—",
            labelColor = labelColor,
            valueColor = valueColor
        )
        StatusRow(
            label = "SSID",
            value = adminData.wifiSsid ?: "—",
            labelColor = labelColor,
            valueColor = valueColor
        )
        StatusRow(
            label = "IP",
            value = adminData.ip ?: "—",
            labelColor = labelColor,
            valueColor = valueColor
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    labelColor: Color,
    valueColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ----------------------------------------------------------------
// Mode selector chip
// ----------------------------------------------------------------

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    CompactChip(
        onClick = onClick,
        colors = if (selected) {
            ChipDefaults.primaryChipColors(backgroundColor = ColorAccent)
        } else {
            ChipDefaults.secondaryChipColors()
        },
        label = {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Color.Black else ColorLabel
            )
        }
    )
}

// ----------------------------------------------------------------
// Text field (Wear OS uses OutlinedTextField from wear.compose.material3
// or a simple composable — we use a minimal approach compatible with
// wear-compose-material 1.3.x which does not expose a text field widget)
// ----------------------------------------------------------------

/**
 * Minimal labelled text field for Wear OS.
 *
 * Since androidx.wear.compose:compose-material 1.3.x does not ship a
 * TextField, we use [androidx.compose.material3.OutlinedTextField] via
 * the standard Material 3 library (already transitively available through
 * the Compose BOM) rendered inside a Wear-aware container.
 *
 * If the project does not include the material3 dependency, replace this
 * with a simple [androidx.compose.foundation.text.BasicTextField].
 */
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.9f),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            color = ColorLabel,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = if (isPassword)
                PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = ColorValue,
                fontSize = 12.sp
            ),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1A2332), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(text = placeholder, color = Color(0xFF37474F), fontSize = 12.sp)
                    }
                    inner()
                }
            }
        )
    }
}

// ----------------------------------------------------------------
// Internal enum
// ----------------------------------------------------------------

private enum class WifiMode { STA, AP }
