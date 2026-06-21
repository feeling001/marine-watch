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
import androidx.wear.compose.material.*
import com.marinewatch.app.MainViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.data.AutopilotData

// ─────────────────────────────────────────────────────────────────────────────
// Colour palette (shared with the rest of the app)
// ─────────────────────────────────────────────────────────────────────────────

private val ColorBackground = Color(0xFF000000)
private val ColorAccent     = Color(0xFF00BFFF)
private val ColorLabel      = Color(0xFF607D8B)
private val ColorValue      = Color(0xFFECEFF1)
private val ColorWarning    = Color(0xFFFFC107)
private val ColorSuccess    = Color(0xFF69F0AE)
private val ColorDanger     = Color(0xFFFF5252)
private val ColorDisabled   = Color(0xFF2A3A40)

// ─────────────────────────────────────────────────────────────────────────────
// AutopilotPage — embedded directly into the HorizontalPager
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AutopilotPage(
    viewModel: MainViewModel,
    isAmbient: Boolean = false
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val autopilot       by viewModel.autopilotData.collectAsState()

    val isConnected = connectionState == BleConnectionState.CONNECTED

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(ColorBackground),
        contentAlignment = Alignment.Center
    ) {
        if (isAmbient) {
            AutopilotAmbientContent(autopilot = autopilot)
        } else {
            AutopilotInteractiveContent(
                autopilot   = autopilot,
                isConnected = isConnected,
                onCommand   = { viewModel.sendAutopilotCommand(it) }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Interactive content
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutopilotInteractiveContent(
    autopilot:   AutopilotData,
    isConnected: Boolean,
    onCommand:   (String) -> Unit
) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Mode badge ─────────────────────────────────────────────────────
        ModeBadge(autopilot = autopilot, isConnected = isConnected)

        // ── Heading / wind target ──────────────────────────────────────────
        TargetDisplay(autopilot = autopilot)

        // ── Mode buttons: STBY / AUTO / WIND ──────────────────────────────
        ModeButtonRow(isConnected = isConnected, onCommand = onCommand)

        // ── Adjust buttons ─────────────────────────────────────────────────
        AdjustRow(
            stepLabel = "10°",
            plusCmd   = "adjust+10",
            minusCmd  = "adjust-10",
            enabled   = isConnected,
            onCommand = onCommand
        )
        AdjustRow(
            stepLabel = "1°",
            plusCmd   = "adjust+1",
            minusCmd  = "adjust-1",
            enabled   = isConnected,
            onCommand = onCommand
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ambient content — minimal, OLED-safe
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutopilotAmbientContent(autopilot: AutopilotData) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier            = Modifier.fillMaxSize()
    ) {
        val modeLabel = autopilot.mode?.uppercase() ?: "---"
        Text(
            text          = modeLabel,
            color         = Color(0xFF455A64),
            fontSize      = 10.sp,
            letterSpacing = 2.sp
        )
        val targetLabel = autopilot.headingTarget?.let { "%.0f°".format(it) }
            ?: autopilot.windTarget?.let { "%.0f°".format(it) }
            ?: "---"
        Text(
            text       = targetLabel,
            color      = Color(0xFF90A4AE),
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModeBadge(autopilot: AutopilotData, isConnected: Boolean) {
    val (label, color) = when {
        !isConnected           -> "NO CONNECTION" to ColorWarning
        autopilot.isEngaged    -> "● ${(autopilot.mode ?: "AUTO").uppercase()}" to ColorSuccess
        autopilot.mode != null -> "○ ${autopilot.mode.uppercase()} — STBY" to ColorWarning
        else                   -> "○ STANDBY" to ColorLabel
    }

    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = color,
            fontSize   = 10.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center
        )
    }
}

@Composable
private fun TargetDisplay(autopilot: AutopilotData) {
    val (sublabel, value) = when (autopilot.mode) {
        "auto", "track" -> "HDG" to (autopilot.headingTarget?.let { "%.0f°".format(it) } ?: "---")
        "wind"          -> "WIND" to (autopilot.windTarget?.let { "%.0f°".format(it) } ?: "---")
        else            -> "TARGET" to "---"
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text       = sublabel,
            color      = ColorLabel,
            fontSize   = 9.sp,
            modifier   = Modifier.padding(end = 4.dp)
        )
        Text(
            text       = value,
            color      = ColorValue,
            fontSize   = 22.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ModeButtonRow(isConnected: Boolean, onCommand: (String) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ModeChip(label = "STBY", cmd = "standby", color = ColorDanger,
            enabled = isConnected, onCommand = onCommand, modifier = Modifier.weight(1f))
        ModeChip(label = "AUTO", cmd = "auto",    color = ColorSuccess,
            enabled = isConnected, onCommand = onCommand, modifier = Modifier.weight(1f))
        ModeChip(label = "WIND", cmd = "wind",    color = ColorAccent,
            enabled = isConnected, onCommand = onCommand, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ModeChip(
    label:    String,
    cmd:      String,
    color:    Color,
    enabled:  Boolean,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Chip(
        modifier = modifier.height(30.dp),
        onClick  = { if (enabled) onCommand(cmd) },
        enabled  = enabled,
        colors   = ChipDefaults.chipColors(
            backgroundColor         = if (enabled) color.copy(alpha = 0.18f) else ColorDisabled,
            disabledBackgroundColor = ColorDisabled
        ),
        label = {
            Text(
                text       = label,
                color      = if (enabled) color else ColorLabel,
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier   = Modifier.fillMaxWidth(),
                textAlign  = TextAlign.Center
            )
        }
    )
}

@Composable
private fun AdjustRow(
    stepLabel: String,
    plusCmd:   String,
    minusCmd:  String,
    enabled:   Boolean,
    onCommand: (String) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Chip(
            modifier = Modifier.weight(1f).height(30.dp),
            onClick  = { if (enabled) onCommand(minusCmd) },
            enabled  = enabled,
            colors   = ChipDefaults.chipColors(
                backgroundColor         = if (enabled) ColorAccent.copy(alpha = 0.15f) else ColorDisabled,
                disabledBackgroundColor = ColorDisabled
            ),
            label = {
                Text(
                    text       = "−$stepLabel",
                    color      = if (enabled) ColorAccent else ColorLabel,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.fillMaxWidth(),
                    textAlign  = TextAlign.Center
                )
            }
        )
        Chip(
            modifier = Modifier.weight(1f).height(30.dp),
            onClick  = { if (enabled) onCommand(plusCmd) },
            enabled  = enabled,
            colors   = ChipDefaults.chipColors(
                backgroundColor         = if (enabled) ColorAccent.copy(alpha = 0.15f) else ColorDisabled,
                disabledBackgroundColor = ColorDisabled
            ),
            label = {
                Text(
                    text       = "+$stepLabel",
                    color      = if (enabled) ColorAccent else ColorLabel,
                    fontSize   = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier.fillMaxWidth(),
                    textAlign  = TextAlign.Center
                )
            }
        )
    }
}
