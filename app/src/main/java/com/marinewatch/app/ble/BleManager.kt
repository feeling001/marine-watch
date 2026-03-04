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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "MarineWatch.BLE"

/**
 * Manages the full BLE lifecycle for the Marine Gateway connection:
 *  - Scans for the device by name ([BleConstants.DEVICE_NAME])
 *  - Connects and discovers the Navigation GATT service
 *  - Enables NOTIFY on the NavData characteristic
 *  - Parses incoming JSON and exposes [navData] as a StateFlow
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

    /**
     * Delay sequence in milliseconds.
     * When [reconnectAttempt] exceeds the last index the manager gives up.
     */
    private val BACKOFF_SCHEDULE_MS = longArrayOf(
        5_000L,
        10_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L
    )

    /** Current position in [BACKOFF_SCHEDULE_MS]. Reset to 0 on a successful connection. */
    private var reconnectAttempt = 0

    // ----------------------------------------------------------------
    // Public state flows observed by the ViewModel / UI
    // ----------------------------------------------------------------

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _navData = MutableStateFlow(NavData.EMPTY)
    val navData: StateFlow<NavData> = _navData.asStateFlow()

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

    // Runnable reference kept so a pending reconnect can be cancelled on demand
    private var pendingReconnectRunnable: Runnable? = null

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

    /**
     * Gracefully terminate the active GATT connection without scheduling
     * an automatic reconnect. Transitions to [BleConnectionState.DISCONNECTED].
     */
    fun disconnect() {
        isStarted = false          // suppress auto-reconnect in onConnectionStateChange
        cancelPendingReconnect()
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
        }
        closeGatt()
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected by user request")
    }

    /**
     * Stop any ongoing connection or scan and immediately restart scanning.
     * Also resets the backoff counter so reconnection starts fresh.
     * Useful to recover from a stuck state or after the PIN has been changed.
     */
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

        // autoConnect=false for faster initial connection
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "GATT connected — requesting MTU 512")
                    // Reset backoff on successful link establishment
                    reconnectAttempt = 0
                    _connectionState.value = BleConnectionState.CONNECTING
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        // Request a larger MTU before service discovery.
                        // Default BLE MTU is 23 bytes (20 usable for notify).
                        // The ESP32 JSON payload can exceed that easily.
                        // onMtuChanged() triggers discoverServices() once negotiated.
                        gatt.requestMtu(512)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT disconnected (status=$status)")
                    // GATT status 8  = connection timeout / supervision timeout
                    // GATT status 19 = remote device terminated connection
                    // GATT status 133 = infamous Android BLE stack error — always retry
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
            // Always proceed to service discovery, whether MTU negotiation succeeded or not
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

            // Enable local notification routing in the Android BLE stack
            gatt.setCharacteristicNotification(navChar, true)

            // Write CCCD to instruct the peripheral to start sending notifications.
            // API 33+ provides a non-deprecated overload that takes the value directly.
            val descriptor = navChar.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor != null) {
                writeCccd(gatt, descriptor)
                Log.i(TAG, "CCCD write initiated — awaiting NavData notifications")
            } else {
                // Descriptor missing — unlikely but we stay connected and try anyway
                Log.w(TAG, "CCCD descriptor not found; notifications may not arrive")
                _connectionState.value = BleConnectionState.CONNECTED
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == BleConstants.CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Notifications enabled — CONNECTED")
                    _connectionState.value = BleConnectionState.CONNECTED
                } else {
                    Log.e(TAG, "Failed to write CCCD: status=$status")
                    // Do not reconnect immediately — the link is still alive.
                    // The UI will show stale data until a reconnect is forced.
                }
            }
        }

        // ── CharacteristicChanged — dual override for API < 33 / ≥ 33 ──

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Only called on API < 33
            if (characteristic.uuid == BleConstants.NAV_DATA_CHAR_UUID) {
                val raw = characteristic.value ?: return
                parseNavData(raw)
            }
        }

        // Non-deprecated override introduced in API 33
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BleConstants.NAV_DATA_CHAR_UUID) {
                parseNavData(value)
            }
        }
    }

    // ----------------------------------------------------------------
    // CCCD write — non-deprecated path for API 33+, legacy for API < 33
    // ----------------------------------------------------------------

    /**
     * Writes [BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE] to [descriptor].
     *
     * On API ≥ 33 the new [BluetoothGatt.writeDescriptor] overload that accepts
     * a [ByteArray] directly is used, avoiding the deprecated setter on
     * [BluetoothGattDescriptor.value].
     *
     * On API < 33 the classic approach is used as a fallback.
     */
    @Suppress("DEPRECATION")
    private fun writeCccd(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ — non-deprecated overload
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            // API < 33 — classic approach (deprecated since API 33 but still functional)
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        }
    }

    // ----------------------------------------------------------------
    // Pairing / bonding detection
    //
    // The Android BLE stack handles the pairing dialog automatically;
    // there is no GATT callback for it. We detect the pairing window by
    // listening to BluetoothDevice.ACTION_BOND_STATE_CHANGED via a
    // BroadcastReceiver registered in the companion object, and by
    // checking the bond state when a CONNECTING transition occurs.
    //
    // The simpler approach used here: expose PAIRING state during the
    // CONNECTING window when the remote device is already in the
    // BOND_BONDING state. The OS shows the pairing dialog then.
    // ----------------------------------------------------------------

    /**
     * BroadcastReceiver that listens for bond state changes and updates
     * [_connectionState] to [BleConnectionState.PAIRING] when the OS
     * pairing dialog is active.
     *
     * Register this receiver in [MainActivity.onCreate] with
     * `IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)` and
     * unregister it in `onDestroy`.
     *
     * Example (in MainActivity):
     * ```kotlin
     * private val bondReceiver = bleManager.createBondStateReceiver()
     *
     * override fun onCreate(...) {
     *     registerReceiver(bondReceiver,
     *         IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
     * }
     * override fun onDestroy() {
     *     unregisterReceiver(bondReceiver)
     *     super.onDestroy()
     * }
     * ```
     */
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
                        // OS pairing dialog is now visible to the user
                        Log.i(TAG, "Pairing in progress — showing PAIRING state")
                        _connectionState.value = BleConnectionState.PAIRING
                    }
                    BluetoothDevice.BOND_BONDED -> {
                        // Pairing completed successfully — resume normal connection flow
                        Log.i(TAG, "Pairing successful (BOND_BONDED)")
                        if (_connectionState.value == BleConnectionState.PAIRING) {
                            _connectionState.value = BleConnectionState.CONNECTING
                        }
                    }
                    BluetoothDevice.BOND_NONE -> {
                        if (previousBondState == BluetoothDevice.BOND_BONDING) {
                            // User cancelled or pairing failed
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
            Log.e(TAG, "JSON parse error (${bytes.size} bytes, likely truncated by MTU): ${e.message}")
            Log.e(TAG, "Raw bytes (hex): ${bytes.joinToString(" ") { "%02X".format(it) }}")
        }
    }

    // ----------------------------------------------------------------
    // Reconnection with exponential backoff
    // ----------------------------------------------------------------

    /**
     * Schedules a reconnection attempt using the exponential backoff schedule.
     *
     * If [reconnectAttempt] has exceeded the last index of [BACKOFF_SCHEDULE_MS],
     * the manager gives up and transitions to [BleConnectionState.DISCONNECTED].
     * The user must then trigger [reconnect] manually.
     */
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

    /** Cancel any reconnect that is waiting in the handler queue. */
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
