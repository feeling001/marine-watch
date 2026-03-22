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
import com.marinewatch.app.data.NavData
import com.marinewatch.app.data.PerformanceData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MarineWatch.BLE"

/**
 * Manages the full BLE lifecycle for the Marine Gateway connection:
 *  - Scans for the device by name ([BleConstants.DEVICE_NAME])
 *  - Connects and discovers the Navigation + Sail Performance GATT services
 *  - Enables NOTIFY on NavData and PerformanceData characteristics
 *  - Parses incoming JSON and exposes [navData] and [perfData] as StateFlows
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

    private val _perfData = MutableStateFlow(PerformanceData.EMPTY)
    val perfData: StateFlow<PerformanceData> = _perfData.asStateFlow()

    /** Timestamp (System.currentTimeMillis) of the last valid NavData packet. */
    private val _lastDataTimestamp = MutableStateFlow(0L)
    val lastDataTimestamp: StateFlow<Long> = _lastDataTimestamp.asStateFlow()

    // ----------------------------------------------------------------
    // Internal BLE objects
    // ----------------------------------------------------------------

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? get() = bluetoothAdapter?.bluetoothLeScanner

    private var gatt: BluetoothGatt? = null
    private var isStarted = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private var pendingReconnectRunnable: Runnable? = null

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
        stopScan()
        closeGatt()
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.i(TAG, "BleManager stopped")
    }

    fun disconnect() {
        isStarted = false
        cancelPendingReconnect()
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
        }
        closeGatt()
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected by user request")
    }

    fun reconnect() {
        Log.i(TAG, "Reconnect requested by user — resetting backoff counter")
        cancelPendingReconnect()
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
        }
        closeGatt()
        reconnectAttempt = 0
        isStarted = true
        startScan()
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
                // Write the next CCCD in the queue, or mark CONNECTED if done
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
    }

    // ----------------------------------------------------------------
    // Sequential CCCD write queue
    // ----------------------------------------------------------------

    /**
     * Writes the CCCD for the next characteristic in [cccdQueue].
     * If the queue is empty, all notifications are enabled and we
     * transition to [BleConnectionState.CONNECTED].
     */
    private fun writeNextCccd(gatt: BluetoothGatt) {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val next = cccdQueue.removeFirstOrNull()
        if (next == null) {
            // All CCCDs written — fully connected
            Log.i(TAG, "All CCCD writes complete — CONNECTED")
            _connectionState.value = BleConnectionState.CONNECTED
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
            BleConstants.NAV_DATA_CHAR_UUID  -> parseNavData(value)
            BleConstants.PERF_DATA_CHAR_UUID -> parsePerfData(value)
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
