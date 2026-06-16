package com.marinewatch.app.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.marinewatch.app.data.AdminData
import com.marinewatch.app.data.AutopilotData
import com.marinewatch.app.data.NavData
import com.marinewatch.app.data.PerformanceData
import com.marinewatch.app.data.WindData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MarineWatch.BLE"

/**
 * Manages the full BLE lifecycle for the Marine Gateway connection:
 *  - Scans for the device by name ([BleConstants.DEVICE_NAME])
 *  - Connects and discovers the Navigation, Wind, Sail Performance, Autopilot
 *    and **Admin** GATT services
 *  - Enables NOTIFY on NavData, WindData, PerformanceData, AutopilotData
 *    and **AdminData** characteristics
 *  - Parses incoming JSON and exposes state flows
 *  - Writes autopilot commands via the AutopilotCmd characteristic
 *  - Writes admin commands (restart, wifi_sta, wifi_ap) via the AdminCmd characteristic
 *  - Polls RSSI every [BleConstants.RSSI_POLL_INTERVAL_MS] when connected and
 *    exposes the value via [rssi]
 *  - Auto-reconnects on link loss using exponential backoff
 *  - Detects BLE pairing events and exposes them via [connectionState]
 *
 * Reconnection schedule (then permanent abandon):
 *   attempt 1 →  5 s
 *   attempt 2 → 10 s
 *   attempt 3 → 30 s
 *   attempt 4 → 60 s
 *   attempt 5 → 120 s
 *   attempt 6 → 300 s
 *   attempt 7+→ give up → DISCONNECTED (no further retries)
 *
 * Call [start] once permissions are granted, [stop] when the owning component is destroyed.
 */
class BleManager(private val context: Context) {

    // ----------------------------------------------------------------
    // Exponential backoff schedule
    // ----------------------------------------------------------------

    private val BACKOFF_SCHEDULE_MS = longArrayOf(
        5_000L,
        10_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L
    )

    private var reconnectAttempt = 0

    // ----------------------------------------------------------------
    // Public state flows observed by the ViewModel / UI
    // ----------------------------------------------------------------

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _navData = MutableStateFlow(NavData.EMPTY)
    val navData: StateFlow<NavData> = _navData.asStateFlow()

    private val _windData = MutableStateFlow(WindData.EMPTY)
    val windData: StateFlow<WindData> = _windData.asStateFlow()

    private val _perfData = MutableStateFlow(PerformanceData.EMPTY)
    val perfData: StateFlow<PerformanceData> = _perfData.asStateFlow()

    private val _autopilotData = MutableStateFlow(AutopilotData.EMPTY)
    val autopilotData: StateFlow<AutopilotData> = _autopilotData.asStateFlow()

    private val _adminData = MutableStateFlow(AdminData.EMPTY)
    val adminData: StateFlow<AdminData> = _adminData.asStateFlow()

    /** Timestamp (System.currentTimeMillis) of the last valid NavData packet. */
    private val _lastDataTimestamp = MutableStateFlow(0L)
    val lastDataTimestamp: StateFlow<Long> = _lastDataTimestamp.asStateFlow()

    /**
     * Last RSSI reading in dBm. Updated every [BleConstants.RSSI_POLL_INTERVAL_MS]
     * while connected. Null when disconnected.
     */
    private val _rssi = MutableStateFlow(0)
    val rssi: StateFlow<Int> = _rssi.asStateFlow()

    // ----------------------------------------------------------------
    // Internal BLE objects
    // ----------------------------------------------------------------

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var autopilotCmdChar: BluetoothGattCharacteristic? = null
    private var adminCmdChar: BluetoothGattCharacteristic? = null
    private var isStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private var pendingReconnectRunnable: Runnable? = null
    private var rssiPollRunnable: Runnable? = null

    /**
     * Tracks CCCD write operations when multiple characteristics need notifications.
     * We must write CCCDs sequentially — one at a time — because the Android BLE
     * stack does not support concurrent descriptor writes. After each [onDescriptorWrite]
     * callback we pop the next entry from this queue and write it.
     */
    private val cccdQueue: ArrayDeque<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>> =
        ArrayDeque()

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Start scanning. Must be called after BLE permissions are granted. */
    fun start() {
        if (isStarted) return
        isStarted = true
        reconnectAttempt = 0
        Log.i(TAG, "BleManager started")
        startScan()
    }

    /** Release all BLE resources. */
    fun stop() {
        isStarted = false
        cancelPendingReconnect()
        stopRssiPolling()
        stopScan()
        closeGatt()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _rssi.value = 0
        Log.i(TAG, "BleManager stopped")
    }

    fun disconnect() {
        isStarted = false
        cancelPendingReconnect()
        stopRssiPolling()
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
        }
        closeGatt()
        _connectionState.value = BleConnectionState.DISCONNECTED
        _rssi.value = 0
        Log.i(TAG, "Disconnected by user request")
    }

    fun reconnect() {
        Log.i(TAG, "Reconnect requested by user — resetting backoff counter")
        cancelPendingReconnect()
        stopRssiPolling()
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
        }
        closeGatt()
        _rssi.value = 0
        reconnectAttempt = 0
        isStarted = true
        startScan()
    }

    /**
     * Sends an autopilot command to the Marine Gateway.
     *
     * Valid commands: "enable", "disable", "adjust+10", "adjust-10", "adjust+1", "adjust-1"
     *
     * The write is fire-and-forget (WRITE_TYPE_NO_RESPONSE), matching the ESP32
     * characteristic property. The function is a no-op if the GATT connection
     * is not ready or the characteristic was not discovered.
     */
    fun sendAutopilotCommand(command: String) {
        val char = autopilotCmdChar
        val currentGatt = gatt

        if (char == null || currentGatt == null) {
            Log.w(TAG, "sendAutopilotCommand('$command') ignored — not connected or char not found")
            return
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "sendAutopilotCommand('$command') ignored — missing BLUETOOTH_CONNECT permission")
            return
        }

        val payload = """{"command":"$command"}""".toByteArray(Charsets.UTF_8)
        Log.i(TAG, "Sending autopilot command: $command")
        writeCharacteristicRaw(currentGatt, char, payload)
    }

    /**
     * Sends an admin command to the Marine Gateway.
     *
     * Supported commands:
     *   - `restart` → { "command": "restart" }
     *   - `wifi_sta` → { "command": "wifi_sta", "ssid": "...", "password": "..." }
     *   - `wifi_ap`  → { "command": "wifi_ap",  "ssid": "...", "password": "..." }
     *
     * The write is fire-and-forget (WRITE_TYPE_NO_RESPONSE).
     * The function is a no-op if the GATT connection is not ready.
     */
    fun sendAdminCommand(jsonPayload: String): Boolean {
        val char = adminCmdChar
        val currentGatt = gatt

        if (char == null || currentGatt == null) {
            Log.w(TAG, "sendAdminCommand ignored — not connected or AdminCmd char not found")
            return false
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "sendAdminCommand ignored — missing BLUETOOTH_CONNECT permission")
            return false
        }

        Log.i(TAG, "Sending admin command: $jsonPayload")
        writeCharacteristicRaw(currentGatt, char, jsonPayload.toByteArray(Charsets.UTF_8))

        return true
    }

    // ----------------------------------------------------------------
    // Scanning
    // ----------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            val name = try { device.name } catch (e: SecurityException) { null }
            if (name == BleConstants.DEVICE_NAME) {
                Log.i(TAG, "Found ${BleConstants.DEVICE_NAME} — ${device.address}")
                stopScan()
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: errorCode=$errorCode")
            scheduleReconnect()
        }
    }

    private fun startScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.e(TAG, "Missing BLUETOOTH_SCAN permission")
            return
        }
        if (bluetoothAdapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled")
            return
        }

        Log.i(TAG, "Starting BLE scan for '${BleConstants.DEVICE_NAME}'")
        _connectionState.value = BleConnectionState.SCANNING

        val filter = ScanFilter.Builder()
            .setDeviceName(BleConstants.DEVICE_NAME)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bleScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    private fun stopScan() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return
        try {
            bleScanner?.stopScan(scanCallback)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan called when adapter was off: ${e.message}")
        }
    }

    // ----------------------------------------------------------------
    // GATT connection
    // ----------------------------------------------------------------

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission")
            return
        }
        Log.i(TAG, "Connecting to ${device.address}")
        _connectionState.value = BleConnectionState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — requesting MTU 512")
                    reconnectAttempt = 0
                    _connectionState.value = BleConnectionState.CONNECTING
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.requestMtu(512)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    cleanupGatt()
                    if (isStarted) scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "MTU negotiated: $mtu bytes (payload: ${mtu - 3} bytes)")
            } else {
                Log.w(TAG, "MTU negotiation failed (status=$status) — continuing with default MTU")
            }
            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: status=$status")
                cleanupGatt()
                scheduleReconnect()
                return
            }

            // Build the sequential CCCD write queue
            cccdQueue.clear()
            autopilotCmdChar = null
            adminCmdChar = null

            // ── Navigation service ──────────────────────────────────────────
            val navService = gatt.getService(BleConstants.NAV_SERVICE_UUID)
            if (navService == null) {
                Log.e(TAG, "Navigation service not found")
                cleanupGatt()
                scheduleReconnect()
                return
            }
            val navChar = navService.getCharacteristic(BleConstants.NAV_DATA_CHAR_UUID)
            if (navChar == null) {
                Log.e(TAG, "NavData characteristic not found")
                cleanupGatt()
                scheduleReconnect()
                return
            }

            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            gatt.setCharacteristicNotification(navChar, true)
            navChar.getDescriptor(BleConstants.CCCD_UUID)?.let {
                cccdQueue.addLast(navChar to it)
            } ?: Log.w(TAG, "NavData CCCD descriptor not found")

            // ── Wind service (optional — graceful if absent) ────────────────
            val windService = gatt.getService(BleConstants.WIND_SERVICE_UUID)
            if (windService != null) {
                val windChar = windService.getCharacteristic(BleConstants.WIND_DATA_CHAR_UUID)
                if (windChar != null) {
                    gatt.setCharacteristicNotification(windChar, true)
                    windChar.getDescriptor(BleConstants.CCCD_UUID)?.let {
                        cccdQueue.addLast(windChar to it)
                    } ?: Log.w(TAG, "WindData CCCD descriptor not found")
                } else {
                    Log.w(TAG, "WindData characteristic not found — wind data unavailable")
                }
            } else {
                Log.w(TAG, "Wind service not found — wind data unavailable")
            }

            // ── Sail Performance service (optional — graceful if absent) ────
            val perfService = gatt.getService(BleConstants.PERF_SERVICE_UUID)
            if (perfService != null) {
                val perfChar = perfService.getCharacteristic(BleConstants.PERF_DATA_CHAR_UUID)
                if (perfChar != null) {
                    gatt.setCharacteristicNotification(perfChar, true)
                    perfChar.getDescriptor(BleConstants.CCCD_UUID)?.let {
                        cccdQueue.addLast(perfChar to it)
                    } ?: Log.w(TAG, "PerformanceData CCCD descriptor not found")
                } else {
                    Log.w(TAG, "PerformanceData characteristic not found — performance data unavailable")
                }
            } else {
                Log.w(TAG, "Sail Performance service not found — performance data unavailable")
            }

            // ── Autopilot service (optional — graceful if absent) ───────────
            val apService = gatt.getService(BleConstants.AUTOPILOT_SERVICE_UUID)
            if (apService != null) {
                val apDataChar = apService.getCharacteristic(BleConstants.AUTOPILOT_DATA_CHAR_UUID)
                if (apDataChar != null) {
                    gatt.setCharacteristicNotification(apDataChar, true)
                    apDataChar.getDescriptor(BleConstants.CCCD_UUID)?.let {
                        cccdQueue.addLast(apDataChar to it)
                    } ?: Log.w(TAG, "AutopilotData CCCD descriptor not found")
                } else {
                    Log.w(TAG, "AutopilotData characteristic not found")
                }

                val cmdChar = apService.getCharacteristic(BleConstants.AUTOPILOT_CMD_CHAR_UUID)
                if (cmdChar != null) {
                    autopilotCmdChar = cmdChar
                    Log.i(TAG, "AutopilotCmd characteristic ready")
                } else {
                    Log.w(TAG, "AutopilotCmd characteristic not found — commands unavailable")
                }
            } else {
                Log.w(TAG, "Autopilot service not found — autopilot unavailable")
            }

            // ── Admin service (optional — graceful if absent) ───────────────
            val adminService = gatt.getService(BleConstants.ADMIN_SERVICE_UUID)
            if (adminService != null) {
                val adminDataChar = adminService.getCharacteristic(BleConstants.ADMIN_DATA_CHAR_UUID)
                if (adminDataChar != null) {
                    gatt.setCharacteristicNotification(adminDataChar, true)
                    adminDataChar.getDescriptor(BleConstants.CCCD_UUID)?.let {
                        cccdQueue.addLast(adminDataChar to it)
                    } ?: Log.w(TAG, "AdminData CCCD descriptor not found")
                } else {
                    Log.w(TAG, "AdminData characteristic not found")
                }

                val adminCmd = adminService.getCharacteristic(BleConstants.ADMIN_CMD_CHAR_UUID)
                if (adminCmd != null) {
                    adminCmdChar = adminCmd
                    Log.i(TAG, "AdminCmd characteristic ready")
                } else {
                    Log.w(TAG, "AdminCmd characteristic not found — admin commands unavailable")
                }
            } else {
                Log.w(TAG, "Admin service not found — admin features unavailable")
            }

            // Kick off the sequential CCCD write chain
            writeNextCccd(gatt)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "CCCD written successfully for ${descriptor.characteristic.uuid}")
                } else {
                    Log.e(TAG, "Failed to write CCCD for ${descriptor.characteristic.uuid}: status=$status")
                }
                writeNextCccd(gatt)
            }
        }

        // ── CharacteristicChanged — dual override for API < 33 / ≥ 33 ──

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val raw = characteristic.value ?: return
            dispatchCharacteristicValue(characteristic.uuid, raw)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            dispatchCharacteristicValue(characteristic.uuid, value)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "RSSI: $rssi dBm")
                _rssi.value = rssi
            } else {
                Log.w(TAG, "Failed to read RSSI: status=$status")
            }
        }
    }

    // ----------------------------------------------------------------
    // Sequential CCCD write queue
    // ----------------------------------------------------------------

    private fun writeNextCccd(gatt: BluetoothGatt) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val next = cccdQueue.removeFirstOrNull()
        if (next == null) {
            Log.i(TAG, "All CCCD writes complete — CONNECTED")
            _connectionState.value = BleConnectionState.CONNECTED
            startRssiPolling()
            return
        }
        val (_, descriptor) = next
        Log.i(TAG, "Writing CCCD for ${descriptor.characteristic.uuid}")
        writeCccd(gatt, descriptor)
    }

    // ----------------------------------------------------------------
    // Characteristic dispatch
    // ----------------------------------------------------------------

    private fun dispatchCharacteristicValue(uuid: java.util.UUID, value: ByteArray) {
        when (uuid) {
            BleConstants.NAV_DATA_CHAR_UUID       -> parseNavData(value)
            BleConstants.WIND_DATA_CHAR_UUID      -> parseWindData(value)
            BleConstants.PERF_DATA_CHAR_UUID      -> parsePerfData(value)
            BleConstants.AUTOPILOT_DATA_CHAR_UUID -> parseAutopilotData(value)
            BleConstants.ADMIN_DATA_CHAR_UUID     -> parseAdminData(value)
        }
    }

    // ----------------------------------------------------------------
    // CCCD write — non-deprecated path for API 33+, legacy for API < 33
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun writeCccd(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    // ----------------------------------------------------------------
    // Generic characteristic write helper (WRITE_NO_RESPONSE)
    // ----------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun writeCharacteristicRaw(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        payload: ByteArray
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                char,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (result != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "writeCharacteristic (API33+) returned error: $result")
            }
        } else {
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            char.value = payload
            val ok = gatt.writeCharacteristic(char)
            if (!ok) {
                Log.e(TAG, "writeCharacteristic (legacy) returned false")
            }
        }
    }

    // ----------------------------------------------------------------
    // Pairing / bonding detection
    // ----------------------------------------------------------------

    fun createBondStateReceiver(): android.content.BroadcastReceiver =
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )
                val previousBondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                )

                Log.d(TAG, "Bond state changed: $previousBondState → $bondState")

                when (bondState) {
                    BluetoothDevice.BOND_BONDING -> {
                        Log.i(TAG, "Pairing in progress — showing PAIRING state")
                        _connectionState.value = BleConnectionState.PAIRING
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        Log.i(TAG, "Pairing successful (BOND_BONDED)")
                        if (_connectionState.value == BleConnectionState.PAIRING) {
                            _connectionState.value = BleConnectionState.CONNECTING
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (previousBondState == BluetoothDevice.BOND_BONDING) {
                            Log.w(TAG, "Pairing failed or cancelled")
                            _connectionState.value = BleConnectionState.DISCONNECTED
                        }
                    }
                }
            }
        }

    // ----------------------------------------------------------------
    // RSSI polling
    // ----------------------------------------------------------------

    private fun startRssiPolling() {
        stopRssiPolling()
        val runnable = object : Runnable {
            override fun run() {
                val currentGatt = gatt
                if (currentGatt != null &&
                    _connectionState.value == BleConnectionState.CONNECTED &&
                    hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
                ) {
                    currentGatt.readRemoteRssi()
                    mainHandler.postDelayed(this, BleConstants.RSSI_POLL_INTERVAL_MS)
                }
            }
        }
        rssiPollRunnable = runnable
        mainHandler.postDelayed(runnable, BleConstants.RSSI_POLL_INTERVAL_MS)
        Log.d(TAG, "RSSI polling started")
    }

    private fun stopRssiPolling() {
        rssiPollRunnable?.let {
            mainHandler.removeCallbacks(it)
            rssiPollRunnable = null
            Log.d(TAG, "RSSI polling stopped")
        }
    }

    // ----------------------------------------------------------------
    // JSON parsing
    // ----------------------------------------------------------------

    private fun parseNavData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "NavData received: ${bytes.size} bytes → $json")
        try {
            val data = gson.fromJson(json, NavData::class.java)
            _navData.value = data
            _lastDataTimestamp.value = System.currentTimeMillis()
            Log.d(TAG, "NavData parsed OK: stw=${data.stw} depth=${data.depth} cog=${data.cog} sog=${data.sog}")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "NavData JSON parse error (${bytes.size} bytes): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun parseWindData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "WindData received: ${bytes.size} bytes → $json")
        try {
            val data = gson.fromJson(json, WindData::class.java)
            _windData.value = data
            Log.d(TAG, "WindData parsed OK: aws=${data.aws} awa=${data.awa} tws=${data.tws} twa=${data.twa} twd=${data.twd}")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "WindData JSON parse error (${bytes.size} bytes): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun parsePerfData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "PerfData received: ${bytes.size} bytes → $json")
        try {
            val data = gson.fromJson(json, PerformanceData::class.java)
            _perfData.value = data
            Log.d(TAG, "PerfData parsed OK: vmg=${data.vmg} polar_pct=${data.polarPct} target_stw=${data.targetStw} polar_loaded=${data.polarLoaded}")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "PerfData JSON parse error (${bytes.size} bytes): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun parseAutopilotData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "AutopilotData received: ${bytes.size} bytes → $json")
        try {
            val data = gson.fromJson(json, AutopilotData::class.java)
            _autopilotData.value = data
            Log.d(TAG, "AutopilotData parsed OK: mode=${data.mode} status=${data.status} heading_target=${data.headingTarget}")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "AutopilotData JSON parse error (${bytes.size} bytes): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    private fun parseAdminData(bytes: ByteArray) {
        val json = bytes.toString(Charsets.UTF_8)
        Log.d(TAG, "AdminData received: ${bytes.size} bytes → $json")
        try {
            val data = gson.fromJson(json, AdminData::class.java)
            _adminData.value = data
            Log.d(TAG, "AdminData parsed OK: uptime=${data.uptimeS}s ip=${data.ip} wifi=${data.wifiMode}/${data.wifiSsid}")
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "AdminData JSON parse error (${bytes.size} bytes): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    // ----------------------------------------------------------------
    // Reconnection with exponential backoff
    // ----------------------------------------------------------------

    private fun scheduleReconnect() {
        if (reconnectAttempt >= BACKOFF_SCHEDULE_MS.size) {
            Log.w(
                TAG,
                "All ${BACKOFF_SCHEDULE_MS.size} reconnection attempts exhausted — giving up"
            )
            _connectionState.value = BleConnectionState.DISCONNECTED
            isStarted = false
            return
        }

        val delayMs = BACKOFF_SCHEDULE_MS[reconnectAttempt]
        Log.i(
            TAG,
            "Reconnect attempt ${reconnectAttempt + 1}/${BACKOFF_SCHEDULE_MS.size} " +
                    "scheduled in ${delayMs / 1000} s"
        )
        _connectionState.value = BleConnectionState.RECONNECTING

        val runnable = Runnable {
            pendingReconnectRunnable = null
            if (isStarted) {
                reconnectAttempt++
                startScan()
            }
        }
        pendingReconnectRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelPendingReconnect() {
        pendingReconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingReconnectRunnable = null
            Log.d(TAG, "Pending reconnect cancelled")
        }
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private fun cleanupGatt() {
        cccdQueue.clear()
        autopilotCmdChar = null
        adminCmdChar = null
        stopRssiPolling()
        _rssi.value = 0
        closeGatt()
        _connectionState.value = BleConnectionState.RECONNECTING
    }

    private fun closeGatt() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            try {
                gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Exception while closing GATT: ${e.message}")
            }
        }
        gatt = null
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
}