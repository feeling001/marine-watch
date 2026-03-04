package com.marinewatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import com.marinewatch.app.MainViewModel
import com.marinewatch.app.ble.BleConnectionState

private val ColorBackground = Color(0xFF000000)
private val ColorAccent     = Color(0xFF00BFFF)
private val ColorLabel      = Color(0xFF607D8B)
private val ColorValue      = Color(0xFFECEFF1)
private val ColorWarning    = Color(0xFFFFC107)
private val ColorDanger     = Color(0xFFFF5252)
private val ColorSuccess    = Color(0xFF69F0AE)

/**
 * Full-screen BLE settings panel.
 *
 * Accessible from the Config page (last pager page) via the "BLE Settings" chip.
 * Navigation back is handled by [onDismiss] — the caller (MarineDisplay) replaces
 * this composable with the pager when onDismiss is invoked.
 *
 * Provides:
 *  1. Connection status + Disconnect / Force reconnect actions.
 *  2. PIN editor — 6-digit spinner, persisted via [MainViewModel.setPinCode].
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()

    var pinDraft      by remember {
        mutableStateOf(viewModel.getPinCode().toString().padStart(6, '0'))
    }
    var pinError      by remember { mutableStateOf(false) }
    var savedFeedback by remember { mutableStateOf(false) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(ColorBackground),
        contentAlignment = Alignment.Center
    ) {
        ScalingLazyColumn(
            modifier            = Modifier.fillMaxSize(),
            contentPadding      = PaddingValues(horizontal = 8.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Back ──────────────────────────────────────────────────────────
            item {
                ActionChip(label = "← Back", color = ColorLabel, onClick = onDismiss)
            }

            // ── Title ─────────────────────────────────────────────────────────
            item {
                Text(
                    text          = "BLE SETTINGS",
                    color         = ColorAccent,
                    fontSize      = 11.sp,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            // ── Connection ────────────────────────────────────────────────────
            item {
                SettingsSection(title = "CONNECTION") {
                    ConnectionStatusBadge(state = connectionState)

                    Spacer(Modifier.height(8.dp))

                    val canDisconnect = connectionState == BleConnectionState.CONNECTED ||
                            connectionState == BleConnectionState.CONNECTING ||
                            connectionState == BleConnectionState.SCANNING

                    ActionChip(
                        label   = "Disconnect",
                        color   = if (canDisconnect) ColorDanger else ColorLabel,
                        enabled = canDisconnect,
                        onClick = { viewModel.disconnect() }
                    )

                    Spacer(Modifier.height(6.dp))

                    val canReconnect = connectionState == BleConnectionState.DISCONNECTED ||
                            connectionState == BleConnectionState.RECONNECTING

                    ActionChip(
                        label   = "Force reconnect",
                        color   = if (canReconnect) ColorSuccess else ColorLabel,
                        enabled = canReconnect,
                        onClick = { viewModel.reconnect() }
                    )
                }
            }

            // ── PIN ───────────────────────────────────────────────────────────
            item {
                SettingsSection(title = "PAIRING PIN") {
                    Text(
                        text      = "6-digit PIN shown on the\nMarine Gateway display",
                        color     = ColorLabel,
                        fontSize  = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.padding(bottom = 8.dp)
                    )

                    PinEditor(
                        pin       = pinDraft,
                        hasError  = pinError,
                        onPinChange = { new ->
                            pinDraft      = new
                            pinError      = false
                            savedFeedback = false
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    if (savedFeedback) {
                        Text(
                            text       = "✓  PIN saved",
                            color      = ColorSuccess,
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        ActionChip(
                            label   = "Save PIN",
                            color   = ColorAccent,
                            onClick = {
                                val pin = pinDraft.toIntOrNull()
                                if (pin != null && pinDraft.length == 6) {
                                    viewModel.setPinCode(pin)
                                    savedFeedback = true
                                } else {
                                    pinError = true
                                }
                            }
                        )
                    }

                    if (pinError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text     = "Enter exactly 6 digits",
                            color    = ColorDanger,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// PIN editor — 6 individual digit spinners
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PinEditor(
    pin:        String,
    hasError:   Boolean,
    onPinChange: (String) -> Unit
) {
    val digits = pin.padStart(6, '0').take(6).map { it.digitToIntOrNull() ?: 0 }

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        digits.forEachIndexed { index, digit ->
            DigitSpinner(
                digit       = digit,
                hasError    = hasError,
                onIncrement = {
                    val d = digits.toMutableList().also { it[index] = (digit + 1) % 10 }
                    onPinChange(d.joinToString(""))
                },
                onDecrement = {
                    val d = digits.toMutableList().also { it[index] = (digit + 9) % 10 }
                    onPinChange(d.joinToString(""))
                }
            )
        }
    }
}

@Composable
private fun DigitSpinner(
    digit:       Int,
    hasError:    Boolean,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.width(26.dp)
    ) {
        CompactButton(
            modifier = Modifier.size(20.dp),
            onClick  = onIncrement,
            colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C2A30))
        ) { Text("▲", fontSize = 7.sp, color = ColorLabel) }

        Box(
            modifier         = Modifier
                .size(width = 22.dp, height = 24.dp)
                .background(
                    color  = if (hasError) ColorDanger.copy(alpha = 0.20f) else Color(0xFF1A2832),
                    shape  = RoundedCornerShape(4.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = digit.toString(),
                color      = if (hasError) ColorDanger else ColorValue,
                fontSize   = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        CompactButton(
            modifier = Modifier.size(20.dp),
            onClick  = onDecrement,
            colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C2A30))
        ) { Text("▼", fontSize = 7.sp, color = ColorLabel) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConnectionStatusBadge(state: BleConnectionState) {
    val (label, color) = when (state) {
        BleConnectionState.CONNECTED    -> "● Connected"       to ColorSuccess
        BleConnectionState.SCANNING     -> "◌ Scanning…"      to ColorAccent
        BleConnectionState.CONNECTING   -> "◌ Connecting…"    to ColorAccent
        BleConnectionState.RECONNECTING -> "⟳ Reconnecting…"  to ColorWarning
        BleConnectionState.PAIRING      -> "🔒 Pairing…"       to ColorAccent
        BleConnectionState.DISCONNECTED -> "○ Disconnected"    to ColorWarning
    }
    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = color,
            fontSize   = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ActionChip(
    label:   String,
    color:   Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Chip(
        modifier  = Modifier.fillMaxWidth().height(36.dp),
        onClick   = { if (enabled) onClick() },
        enabled   = enabled,
        colors    = ChipDefaults.chipColors(
            backgroundColor = if (enabled) color.copy(alpha = 0.13f) else Color(0xFF111111)
        ),
        label     = {
            Text(
                text      = label,
                color     = if (enabled) color else ColorLabel.copy(alpha = 0.5f),
                fontSize  = 12.sp,
                modifier  = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    )
}

@Composable
private fun SettingsSection(
    title:   String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1A20), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text          = title,
            color         = ColorLabel,
            fontSize      = 9.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier      = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}