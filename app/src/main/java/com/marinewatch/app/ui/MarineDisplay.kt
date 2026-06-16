package com.marinewatch.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import com.marinewatch.app.MainViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.ble.BleConstants
import com.marinewatch.app.data.AdminData
import com.marinewatch.app.data.DATA_PAGE_COUNT
import com.marinewatch.app.data.DataField
import com.marinewatch.app.data.DisplayData
import com.marinewatch.app.data.PageConfig

// ─────────────────────────────────────────────────────────────────────────────
// Navigation States
// ─────────────────────────────────────────────────────────────────────────────
private enum class AppScreen { PAGER, BLE_SETTINGS, WIFI_SETTINGS }

// ─────────────────────────────────────────────────────────────────────────────
// Colour palette
// ─────────────────────────────────────────────────────────────────────────────

private val ColorBackground   = Color(0xFF000000)
private val ColorAmbientBg    = Color(0xFF000000)
private val ColorAccent       = Color(0xFF00BFFF)
private val ColorLabel        = Color(0xFF607D8B)
private val ColorValue        = Color(0xFFECEFF1)
private val ColorValueWarn    = Color(0xFFFFC107)
private val ColorWarning      = Color(0xFFFFC107)
private val ColorAmbientValue = Color(0xFF90A4AE)
private val ColorAmbientLabel = Color(0xFF455A64)
private val ColorSuccess      = Color(0xFF69F0AE)
private val ColorDanger       = Color(0xFFFF5252)

/**
 * Page layout:
 * pages 0 … DATA_PAGE_COUNT-1  → configurable 2×2 data grids
 * page  DATA_PAGE_COUNT        → autopilot control
 * page  DATA_PAGE_COUNT+1      → configuration
 */
private val AUTOPILOT_PAGE_INDEX = DATA_PAGE_COUNT
private val CONFIG_PAGE_INDEX    = DATA_PAGE_COUNT + 1
private val TOTAL_PAGES          = DATA_PAGE_COUNT + 2

// ─────────────────────────────────────────────────────────────────────────────
// RSSI signal quality
// ─────────────────────────────────────────────────────────────────────────────

internal enum class SignalQuality { EXCELLENT, GOOD, FAIR, POOR, VERY_POOR }

private fun rssiToQuality(rssi: Int): SignalQuality = when {
    rssi == 0                          -> SignalQuality.VERY_POOR
    rssi > BleConstants.RSSI_EXCELLENT -> SignalQuality.EXCELLENT
    rssi > BleConstants.RSSI_GOOD      -> SignalQuality.GOOD
    rssi > BleConstants.RSSI_FAIR      -> SignalQuality.FAIR
    rssi > BleConstants.RSSI_POOR      -> SignalQuality.POOR
    else                               -> SignalQuality.VERY_POOR
}

// ─────────────────────────────────────────────────────────────────────────────
// Data freshness
// ─────────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
// Root composable
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarineDisplay(
    viewModel: MainViewModel,
    isAmbient: Boolean = false
) {
    // Gestion de la navigation centralisée par Enum au lieu de simples booleans
    var currentScreen by remember { mutableStateOf(AppScreen.PAGER) }

    when (currentScreen) {
        AppScreen.BLE_SETTINGS -> {
            SettingsScreen(
                viewModel = viewModel,
                onDismiss = { currentScreen = AppScreen.PAGER }
            )
            return
        }
        AppScreen.WIFI_SETTINGS -> {
            WifiSettingsScreen(
                viewModel = viewModel,
                isAmbient = isAmbient,
                onBack    = { currentScreen = AppScreen.PAGER } // Retourne au Pager global
            )
            return
        }
        AppScreen.PAGER -> { /* Continue le rendu normal du Pager */ }
    }

    val state       by viewModel.connectionState.collectAsState()
    val nav         by viewModel.navData.collectAsState()
    val perf        by viewModel.perfData.collectAsState()
    val wind        by viewModel.windData.collectAsState()
    val lastTs      by viewModel.lastDataTimestamp.collectAsState()
    val pageConfigs by viewModel.pageConfigs.collectAsState()
    val admin       by viewModel.adminData.collectAsState()
    val rssi        by viewModel.rssi.collectAsState()

    

    val freshness   = remember(lastTs) { dataFreshness(lastTs) }
    val quality     = remember(rssi) { rssiToQuality(rssi) }

    // Combine nav + perf + wind into a single DisplayData snapshot for the grid
    val displayData = remember(nav, perf, wind) { DisplayData(nav = nav, perf = perf, wind = wind) }

    Box(
        modifier         = Modifier
            .fillMaxSize()
            .background(if (isAmbient) ColorAmbientBg else ColorBackground),
        contentAlignment = Alignment.Center
    ) {
        MainPager(
            state          = state,
            displayData    = displayData,
            pageConfigs    = pageConfigs,
            freshness      = freshness,
            isAmbient      = isAmbient,
            viewModel      = viewModel,
            admin          = admin,
            onOpenSettings = { currentScreen = AppScreen.BLE_SETTINGS },
            onOpenWifi     = { currentScreen = AppScreen.WIFI_SETTINGS }
        )

        // RSSI indicator — top centre, visible on all pages in interactive mode when connected
        if (!isAmbient && state == BleConnectionState.CONNECTED) {
            RssiIndicator(
                quality  = quality,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
            )
        }

        if (isAmbient && state == BleConnectionState.RECONNECTING) {
            AmbientOfflineIndicator()
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main pager — always rendered
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainPager(
    state:          BleConnectionState,
    displayData:    DisplayData,
    pageConfigs:    List<PageConfig>,
    freshness:      DataFreshness,
    isAmbient:      Boolean,
    viewModel:      MainViewModel,
    admin:          AdminData?,
    onOpenSettings: () -> Unit,
    onOpenWifi:     () -> Unit
) {
    val pagerState = rememberPagerState { TOTAL_PAGES }

    Box(modifier = Modifier.fillMaxSize()) {

        HorizontalPager(
            state    = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { pageIndex ->
            when (pageIndex) {
                in 0 until DATA_PAGE_COUNT -> {
                    DataPage(
                        bleState    = state,
                        config      = pageConfigs[pageIndex],
                        displayData = displayData,
                        freshness   = freshness,
                        isAmbient   = isAmbient,
                        pageNumber  = pageIndex + 1
                    )
                }
                AUTOPILOT_PAGE_INDEX -> {
                    AutopilotPage(
                        viewModel = viewModel,
                        isAmbient = isAmbient
                    )
                }
                else -> {
                    ConfigPage(
                        pageConfigs    = pageConfigs,
                        viewModel      = viewModel,
                        admin          = admin,
                        isAmbient      = isAmbient,
                        onOpenSettings = onOpenSettings,
                        onOpenWifi     = onOpenWifi
                    )
                }
            }
        }

        // Page indicator dots
        if (!isAmbient) {
            Row(
                modifier              = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                repeat(TOTAL_PAGES) { index ->
                    val isSelected = pagerState.currentPage == index
                    val dotSize = if (isSelected) 6.dp else 5.dp
                    val dotColor = when {
                        isSelected && index == AUTOPILOT_PAGE_INDEX -> ColorSuccess
                        isSelected                                   -> ColorAccent
                        else                                         -> ColorLabel.copy(alpha = 0.4f)
                    }
                    Box(
                        modifier = Modifier
                            .size(dotSize)
                            .background(color = dotColor, shape = RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Data page
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DataPage(
    bleState:    BleConnectionState,
    config:      PageConfig,
    displayData: DisplayData,
    freshness:   DataFreshness,
    isAmbient:   Boolean,
    pageNumber:  Int
) {
    Box(
        modifier         = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            !isAmbient && bleState != BleConnectionState.CONNECTED -> {
                when (bleState) {
                    BleConnectionState.SCANNING,
                    BleConnectionState.CONNECTING  -> ConnectingScreen(bleState)
                    BleConnectionState.RECONNECTING -> ReconnectingScreen()
                    BleConnectionState.PAIRING      -> PairingScreen()
                    BleConnectionState.DISCONNECTED -> DisconnectedScreen()
                    else                            -> Unit
                }
            }
            freshness == DataFreshness.STALE && !isAmbient -> StaleOverlay()
            else -> NavGrid(
                config      = config,
                displayData = displayData,
                isAmbient   = isAmbient,
                freshness   = freshness,
                pageNumber  = pageNumber
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// RSSI Component
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun RssiIndicator(
    quality: SignalQuality,
    modifier: Modifier = Modifier
) {
    val filledBars = when (quality) {
        SignalQuality.EXCELLENT -> 5
        SignalQuality.GOOD      -> 4
        SignalQuality.FAIR      -> 3
        SignalQuality.POOR      -> 2
        SignalQuality.VERY_POOR -> 1
    }
    val barColor = when (quality) {
        SignalQuality.EXCELLENT,
        SignalQuality.GOOD      -> ColorSuccess
        SignalQuality.FAIR,
        SignalQuality.POOR      -> ColorWarning
        SignalQuality.VERY_POOR -> ColorDanger
    }
    val emptyColor = Color(0xFF263238)

    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp)
    ) {
        for (i in 1..5) {
            val barHeight = (4 + i * 2).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(
                        color = if (i <= filledBars) barColor else emptyColor,
                        shape = RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp)
                    )
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Admin info row (uptime + IP)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AdminInfoRow(admin: AdminData?, isAmbient: Boolean) {
    val labelColor = if (isAmbient) ColorAmbientLabel else ColorLabel
    val valueColor = if (isAmbient) ColorAmbientValue else ColorValue
    val uptime = admin?.uptimeS ?: 0L

    Column(
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .background(
                color = Color(0xFF0D1B2A),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Uptime", color = labelColor, fontSize = 9.sp)
            Text(
                text = if (uptime > 0) admin?.uptimeFormatted() ?: "—" else "—",
                color = valueColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "IP", color = labelColor, fontSize = 9.sp)
            Text(
                text = admin?.ip ?: "—",
                color = valueColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 2×2 data grid
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun NavGrid(
    config:      PageConfig,
    displayData: DisplayData,
    isAmbient:   Boolean,
    freshness:   DataFreshness = DataFreshness.FRESH,
    pageNumber:  Int           = 0
) {
    val valueColor = when {
        isAmbient                       -> ColorAmbientValue
        freshness == DataFreshness.WARN -> ColorValueWarn
        else                            -> ColorValue
    }
    val labelColor = if (isAmbient) ColorAmbientLabel else ColorLabel

    Column(
        modifier                = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 20.dp),
        verticalArrangement     = Arrangement.SpaceEvenly,
        horizontalAlignment     = Alignment.CenterHorizontally
    ) {
        if (!isAmbient) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text          = "MARINE  $pageNumber/${DATA_PAGE_COUNT}",
                color         = ColorAccent,
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        Row(
            modifier                = Modifier.fillMaxWidth(),
            horizontalArrangement   = Arrangement.SpaceEvenly
        ) {
            DataTile(
                field      = config.slots[0],
                data       = displayData,
                valueColor = valueColor,
                labelColor = labelColor
            )
            DataTile(
                field      = config.slots[1],
                data       = displayData,
                valueColor = valueColor,
                labelColor = labelColor
            )
        }

        if (!isAmbient) Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier                = Modifier.fillMaxWidth(),
            horizontalArrangement   = Arrangement.SpaceEvenly
        ) {
            DataTile(
                field      = config.slots[2],
                data       = displayData,
                valueColor = valueColor,
                labelColor = labelColor
            )
            DataTile(
                field      = config.slots[3],
                data       = displayData,
                valueColor = valueColor,
                labelColor = labelColor
            )
        }
        
        if (!isAmbient) {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun DataTile(
    field:      DataField,
    data:       DisplayData,
    valueColor: Color,
    labelColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.width(80.dp)
    ) {
        Text(
            text          = field.label,
            color         = labelColor,
            fontSize      = 10.sp,
            fontWeight    = FontWeight.Medium,
            letterSpacing = 1.sp
        )
        Text(
            text        = field.extract(data),
            color       = if (field == DataField.EMPTY) Color.Transparent else valueColor,
            fontSize    = 22.sp,
            fontWeight  = FontWeight.Bold,
            textAlign   = TextAlign.Center
        )
        if (field.unit.isNotEmpty()) {
            Text(
                text     = field.unit,
                color    = labelColor,
                fontSize = 10.sp
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Config page (last page)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ConfigPage(
    pageConfigs:    List<PageConfig>,
    viewModel:      MainViewModel,
    admin:          AdminData?,
    isAmbient:      Boolean,
    onOpenSettings: () -> Unit,
    onOpenWifi:     () -> Unit // Callback branché pour l'ouverture
) {
    ScalingLazyColumn(
        modifier            = Modifier.fillMaxSize(),
        contentPadding      = PaddingValues(horizontal = 8.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text          = "⚙  CONFIGURE",
                color         = ColorAccent,
                fontSize      = 11.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }

        repeat(DATA_PAGE_COUNT) { pageIndex ->
            item {
                PageSlotEditor(
                    pageIndex   = pageIndex,
                    config      = pageConfigs[pageIndex],
                    onSlotChange = { slotIndex, field ->
                        viewModel.updateSlot(pageIndex, slotIndex, field)
                    }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }

        // BLE Settings Button
        item {
            Chip(
                modifier  = Modifier.fillMaxWidth().height(36.dp),
                onClick   = onOpenSettings,
                colors    = ChipDefaults.chipColors(
                    backgroundColor = ColorAccent.copy(alpha = 0.13f)
                ),
                label     = {
                    Text(
                        text      = "BLE Settings",
                        color     = ColorAccent,
                        fontSize  = 12.sp,
                        modifier  = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            )
        }

        // WiFi Settings Button
        item {
            Chip(
                modifier  = Modifier.fillMaxWidth().height(36.dp),
                onClick   = onOpenWifi, // Exécute maintenant le changement d'écran vers le WiFi
                colors    = ChipDefaults.chipColors(
                    backgroundColor = ColorAccent.copy(alpha = 0.13f)
                ),
                label     = {
                    Text(
                        text      = "WiFi Settings",
                        color     = ColorAccent,
                        fontSize  = 12.sp,
                        modifier  = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            )
        }

        // Admin Info Row (Uptime & IP)
        item {
            AdminInfoRow(admin = admin, isAmbient = isAmbient)
        }
        
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun PageSlotEditor(
    pageIndex:    Int,
    config:       PageConfig,
    onSlotChange: (slotIndex: Int, DataField) -> Unit
) {
    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1A20), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text          = "PAGE ${pageIndex + 1}",
            color         = ColorLabel,
            fontSize      = 9.sp,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier      = Modifier.padding(bottom = 8.dp)
        )

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SlotPicker(
                label         = "TL",
                current       = config.slots[0],
                onFieldChange = { onSlotChange(0, it) }
            )
            SlotPicker(
                label         = "TR",
                current       = config.slots[1],
                onFieldChange = { onSlotChange(1, it) }
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SlotPicker(
                label         = "BL",
                current       = config.slots[2],
                onFieldChange = { onSlotChange(2, it) }
            )
            SlotPicker(
                label         = "BR",
                current       = config.slots[3],
                onFieldChange = { onSlotChange(3, it) }
            )
        }
    }
}

@Composable
private fun SlotPicker(
    label:         String,
    current:       DataField,
    onFieldChange: (DataField) -> Unit
) {
    val fields = DataField.entries.toList()
    val idx    = fields.indexOf(current).takeIf { it >= 0 } ?: 0

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.width(64.dp)
    ) {
        Text(
            text      = label,
            color     = ColorLabel,
            fontSize  = 8.sp,
            modifier  = Modifier.padding(bottom = 2.dp)
        )
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier              = Modifier.fillMaxWidth()
        ) {
            CompactButton(
                modifier = Modifier.size(22.dp),
                onClick  = { onFieldChange(fields[(idx - 1 + fields.size) % fields.size]) },
                colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C2A30))
            ) {
                Text("◀", fontSize = 7.sp, color = ColorLabel)
            }

            Text(
                text       = if (current == DataField.EMPTY) "—" else current.label,
                color      = ColorValue,
                fontSize   = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                modifier   = Modifier.weight(1f)
            )

            CompactButton(
                modifier = Modifier.size(22.dp),
                onClick  = { onFieldChange(fields[(idx + 1) % fields.size]) },
                colors   = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1C2A30))
            ) {
                Text("▶", fontSize = 7.sp, color = ColorLabel)
            }
        }

        Text(
            text     = current.unit,
            color    = ColorLabel,
            fontSize = 8.sp,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status / overlay screens
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ConnectingScreen(state: BleConnectionState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier       = Modifier.size(32.dp),
            strokeWidth    = 3.dp,
            indicatorColor = ColorAccent
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text     = when (state) {
                BleConnectionState.SCANNING   -> "Scanning…"
                BleConnectionState.CONNECTING -> "Connecting…"
                else                          -> "Please wait…"
            },
            color    = ColorLabel,
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
            text       = "Signal lost",
            color      = ColorWarning,
            fontSize   = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text     = "Reconnecting…",
            color    = ColorLabel,
            fontSize = 11.sp
        )
    }
}

@Composable
fun PairingScreen() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = Modifier.padding(12.dp)
    ) {
        Text("🔒", fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            text       = "Pairing",
            color      = ColorAccent,
            fontSize   = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text      = "Enter PIN shown\non the device",
            color     = ColorLabel,
            fontSize  = 11.sp,
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
            text     = "Disconnected",
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
            text     = "No data",
            color    = ColorWarning,
            fontSize = 13.sp
        )
        Text(
            text     = "Check ESP32",
            color    = ColorLabel,
            fontSize = 11.sp
        )
    }
}

@Composable
fun AmbientOfflineIndicator() {
    Text(
        text          = "- OFFLINE -",
        color         = ColorAmbientLabel,
        fontSize      = 11.sp,
        letterSpacing = 2.sp
    )
}