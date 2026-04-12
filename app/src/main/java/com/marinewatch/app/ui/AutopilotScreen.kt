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

/**
 * Autopilot control page, displayed as the last data page just before the
 * configuration page in the [HorizontalPager].
 *
 * Provides:
 *  - Status badge (mode + engaged/standby)
 *  - Current heading target display
 *  - Enable / Disable toggle button
 *  - ±10° and ±1° heading adjustment buttons
 *
 * All BLE commands are forwarded to [MainViewModel.sendAutopilotCommand].
 * Controls are disabled when the BLE connection is not [BleConnectionState.CONNECTED].
 */
@Composable
fun AutopilotPage(
    viewModel:    MainViewModel,
    isAmbient:    Boolean = false
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
            .padding(horizontal = 10.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Title ──────────────────────────────────────────────────────────
        Text(
            text          = "AUTOPILOT",
            color         = ColorAccent,
            fontSize      = 11.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        // ── Status badge ───────────────────────────────────────────────────
        AutopilotStatusBadge(autopilot = autopilot, isConnected = isConnected)

        // ── Heading target ─────────────────────────────────────────────────
        HeadingTargetDisplay(autopilot = autopilot)

        // ── Enable / Disable ───────────────────────────────────────────────
        EnableDisableButton(
            autopilot   = autopilot,
            isConnected = isConnected,
            onCommand   = onCommand
        )

        // ── Adjust buttons ─────────────────────────────────────────────────
        AdjustRow(
            label       = "±10°",
            plusCmd     = "adjust+10",
            minusCmd    = "adjust-10",
            isConnected = isConnected && autopilot.isEngaged,
            onCommand   = onCommand
        )

        AdjustRow(
            label       = "±1°",
            plusCmd     = "adjust+1",
            minusCmd    = "adjust-1",
            isConnected = isConnected && autopilot.isEngaged,
            onCommand   = onCommand
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
        Text(
            text          = "AUTO",
            color         = Color(0xFF455A64),
            fontSize      = 10.sp,
            letterSpacing = 2.sp
        )
        val statusLabel = when {
            autopilot.isEngaged -> autopilot.headingTarget?.let { "%.0f°".format(it) } ?: "---"
            else                -> "STBY"
        }
        Text(
            text       = statusLabel,
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
private fun AutopilotStatusBadge(autopilot: AutopilotData, isConnected: Boolean) {
    val (label, color) = when {
        !isConnected         -> "No connection" to ColorWarning
        autopilot.isEngaged  -> {
            val mode = autopilot.mode?.uppercase() ?: "AUTO"
            "● $mode — Engaged" to ColorSuccess
        }
        autopilot.mode != null -> {
            "○ ${autopilot.mode.uppercase()} — Standby" to ColorWarning
        }
        else -> "○ Standby" to ColorLabel
    }

    Box(
        modifier         = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .padding(vertical = 4.dp),
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
private fun HeadingTargetDisplay(autopilot: AutopilotData) {
    val target = autopilot.headingTarget
    val label  = when {
        autopilot.isEngaged && target != null -> "%.0f°".format(target)
        autopilot.mode == "wind"              ->
            autopilot.windTarget?.let { "%.0f°".format(it) } ?: "---"
        else                                  -> "---"
    }
    val sublabel = when (autopilot.mode) {
        "auto"  -> "HDG target"
        "wind"  -> "Wind target"
        "track" -> "Track target"
        else    -> "Target"
    }

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text      = sublabel,
                color     = ColorLabel,
                fontSize  = 9.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text       = label,
                color      = ColorValue,
                fontSize   = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center
            )
        }
    }
}

@Composable
private fun EnableDisableButton(
    autopilot:   AutopilotData,
    isConnected: Boolean,
    onCommand:   (String) -> Unit
) {
    val isEngaged = autopilot.isEngaged

    val label = if (isEngaged) "DISABLE" else "AUTO"
    val color = if (isEngaged) ColorDanger else ColorSuccess
    val cmd   = if (isEngaged) "disable"  else "auto"

    Chip(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp),
        onClick  = { if (isConnected) onCommand(cmd) },
        enabled  = isConnected,
        colors   = ChipDefaults.chipColors(
            backgroundColor         = if (isConnected) color.copy(alpha = 0.18f) else ColorDisabled,
            disabledBackgroundColor = ColorDisabled
        ),
        label = {
            Text(
                text      = label,
                color     = if (isConnected) color else ColorLabel,
                fontSize  = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier  = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    )
}

@Composable
private fun AdjustRow(
    label:       String,
    plusCmd:     String,
    minusCmd:    String,
    isConnected: Boolean,
    onCommand:   (String) -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // −  button
        Chip(
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
            onClick  = { if (isConnected) onCommand(minusCmd) },
            enabled  = isConnected,
            colors   = ChipDefaults.chipColors(
                backgroundColor         = if (isConnected) ColorAccent.copy(alpha = 0.15f) else ColorDisabled,
                disabledBackgroundColor = ColorDisabled
            ),
            label = {
                Text(
                    text      = "−$label",
                    color     = if (isConnected) ColorAccent else ColorLabel,
                    fontSize  = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )

        // +  button
        Chip(
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
            onClick  = { if (isConnected) onCommand(plusCmd) },
            enabled  = isConnected,
            colors   = ChipDefaults.chipColors(
                backgroundColor         = if (isConnected) ColorAccent.copy(alpha = 0.15f) else ColorDisabled,
                disabledBackgroundColor = ColorDisabled
            ),
            label = {
                Text(
                    text      = "+$label",
                    color     = if (isConnected) ColorAccent else ColorLabel,
                    fontSize  = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier  = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        )
    }
}
