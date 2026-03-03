package com.marinewatch.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactButton

import com.marinewatch.app.MainViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.ble.BleConstants
import com.marinewatch.app.data.NavData
import com.marinewatch.app.ui.SettingsScreen

// ----------------------------------------------------------------
// Colour palette
// ----------------------------------------------------------------

private val ColorBackground     = Color(0xFF000000)
private val ColorAmbientBg      = Color(0xFF000000)
private val ColorAccent         = Color(0xFF00BFFF)   // Deep sky blue
private val ColorLabel          = Color(0xFF607D8B)   // Blue-grey
private val ColorValue          = Color(0xFFECEFF1)   // Near-white
private val ColorValueWarn      = Color(0xFFFFC107)   // Amber — data > 5 s old
private val ColorWarning        = Color(0xFFFFC107)   // Amber
private val ColorAmbientValue   = Color(0xFF90A4AE)   // Dimmed for ambient
private val ColorAmbientLabel   = Color(0xFF455A64)

/**
 * Three freshness states for received data.
 *
 * [FRESH]  — packet received within [BleConstants.DATA_WARN_THRESHOLD_MS]
 * [WARN]   — no packet for 5–15 s: values shown in yellow
 * [STALE]  — no packet for > [BleConstants.DATA_STALE_THRESHOLD_MS]: overlay shown
 */
internal enum class DataFreshness { FRESH, WARN, STALE }

private fun dataFreshness(lastTs: Long): DataFreshness {
    if (lastTs == 0L) return DataFreshness.STALE
    val age = System.currentTimeMillis() - lastTs
    return when {
        age <= BleConstants.DATA_WARN_THRESHOLD_MS  -> DataFreshness.FRESH
        age <= BleConstants.DATA_STALE_THRESHOLD_MS -> DataFreshness.WARN
        else                                         -> DataFreshness.STALE
    }
}


@Composable
fun MarineDisplay(
    viewModel: MainViewModel,
    isAmbient: Boolean = false
) {
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsScreen(
            viewModel = viewModel,
            onDismiss = { showSettings = false }
        )
        return
    }

    val state  by viewModel.connectionState.collectAsState()
    val nav    by viewModel.navData.collectAsState()
    val lastTs by viewModel.lastDataTimestamp.collectAsState()

    val freshness = remember(lastTs) { dataFreshness(lastTs) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isAmbient) ColorAmbientBg else ColorBackground),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            BleConnectionState.CONNECTED -> {
                when {
                    freshness == DataFreshness.STALE && !isAmbient -> StaleOverlay()
                    else -> NavGrid(nav = nav, isAmbient = isAmbient, freshness = freshness)
                }
            }
            BleConnectionState.SCANNING,
            BleConnectionState.CONNECTING -> {
                if (!isAmbient) ConnectingScreen(state)
            }
            BleConnectionState.RECONNECTING -> {
                if (!isAmbient) ReconnectingScreen()
                else AmbientOfflineIndicator()
            }
            BleConnectionState.PAIRING -> {
                if (!isAmbient) PairingScreen()
            }
            BleConnectionState.DISCONNECTED -> {
                if (!isAmbient) DisconnectedScreen()
            }
        }

        // Gear button — hidden in ambient mode
        if (!isAmbient) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                CompactButton(
                    modifier = Modifier.size(28.dp),
                    onClick = { showSettings = true },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF1C2A30)
                    )
                ) {
                    Text("⚙", fontSize = 12.sp, color = ColorLabel)
                }
            }
        }
    }
}

// ----------------------------------------------------------------
// 2×2 data grid
// ----------------------------------------------------------------

/**
 * Displays the four marine data tiles in a 2×2 grid.
 *
 * @param freshness  Controls the value colour: white when fresh, yellow when
 *                   data has not been updated for more than 5 seconds.
 */
@Composable
internal fun NavGrid(
    nav: NavData,
    isAmbient: Boolean,
    freshness: DataFreshness = DataFreshness.FRESH
) {
    val valueColor = when {
        isAmbient                        -> ColorAmbientValue
        freshness == DataFreshness.WARN  -> ColorValueWarn   // ← yellow
        else                             -> ColorValue        // ← white
    }
    val labelColor = if (isAmbient) ColorAmbientLabel else ColorLabel

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top divider label (only in interactive mode)
        if (!isAmbient) {
            Text(
                text = "MARINE",
                color = ColorAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DataTile(
                label = "STW",
                value = nav.stw?.let { "%.1f".format(it) } ?: "---",
                unit  = "kn",
                valueColor = valueColor,
                labelColor = labelColor
            )
            DataTile(
                label = "DEPTH",
                value = nav.depth?.let { "%.1f".format(it) } ?: "---",
                unit  = "m",
                valueColor = valueColor,
                labelColor = labelColor
            )
        }

        if (!isAmbient) {
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            DataTile(
                label = "COG",
                value = nav.cog?.let { "%.0f°".format(it) } ?: "---",
                unit  = "",
                valueColor = valueColor,
                labelColor = labelColor
            )
            DataTile(
                label = "SOG",
                value = nav.sog?.let { "%.1f".format(it) } ?: "---",
                unit  = "kn",
                valueColor = valueColor,
                labelColor = labelColor
            )
        }
    }
}

/**
 * A single data tile: label on top, large value, small unit below.
 */
@Composable
internal fun DataTile(
    label: String,
    value: String,
    unit: String,
    valueColor: Color,
    labelColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp)
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (unit.isNotEmpty()) {
            Text(
                text = unit,
                color = labelColor,
                fontSize = 10.sp
            )
        }
    }
}

// ----------------------------------------------------------------
// Status / overlay screens
// ----------------------------------------------------------------

@Composable
fun ConnectingScreen(state: BleConnectionState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
            indicatorColor = ColorAccent
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (state) {
                BleConnectionState.SCANNING    -> "Scanning…"
                BleConnectionState.CONNECTING  -> "Connecting…"
                else                           -> "Please wait…"
            },
            color = ColorLabel,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ReconnectingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⚓", fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Signal lost",
            color = ColorWarning,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Reconnecting…",
            color = ColorLabel,
            fontSize = 11.sp
        )
    }
}

@Composable
fun PairingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
        Text("🔒", fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pairing",
            color = ColorAccent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Enter PIN shown\non the device",
            color = ColorLabel,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun DisconnectedScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("📡", fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Disconnected",
            color = ColorWarning,
            fontSize = 13.sp
        )
    }
}

@Composable
fun StaleOverlay() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("⏳", fontSize = 28.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "No data",
            color = ColorWarning,
            fontSize = 13.sp
        )
        Text(
            text = "Check ESP32",
            color = ColorLabel,
            fontSize = 11.sp
        )
    }
}

/** Minimal ambient indicator shown when disconnected in ambient mode. */
@Composable
fun AmbientOfflineIndicator() {
    Text(
        text = "- OFFLINE -",
        color = ColorAmbientLabel,
        fontSize = 11.sp,
        letterSpacing = 2.sp
    )
}
