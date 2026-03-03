package com.marinewatch.app.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
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
 *  - Auto-reconnects on link loss
 *
 * Call [start] once permissions are granted, [stop] when the owning component is destroyed.
 */
class BleManager(private val context: Context) {

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

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    /** Start scanning. Must be called after BLE permissions are granted. */
    fun start() {
        if (isStarted) return
        isStarted = true
        Log.i(TAG, "BleManager started")
        startScan()
    }

    /** Release all BLE resources. */
    fun stop() {
        isStarted = false
        stopScan()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.i(TAG, "BleManager stopped")
    }

    /**
     * Gracefully terminate the active GATT connection without scheduling
     * an automatic reconnect. Transitions to [BleConnectionState.DISCONNECTED].
     */
    fun disconnect() {
        isStarted = false  // suppress auto-reconnect in onConnectionStateChange
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        Log.i(TAG, "Disconnected by user request")
    }

    /**
     * Stop any ongoing connection or scan and immediately restart scanning.
     * Useful to recover from a stuck state or after the PIN has been changed.
     */
    fun reconnect() {
        Log.i(TAG, "Reconnect requested by user")
        stopScan()
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
        isStarted = true
        startScan()
    }

    // ----------------------------------------------------------------
    // Scanning
    // ----------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (device.name == BleConstants.DEVICE_NAME) {
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
        bleScanner?.stopScan(scanCallback)
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
                    _connectionState.value = BleConnectionState.CONNECTING
                    if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        // Request a larger MTU before service discovery.
                        // Default BLE MTU is 23 bytes (20 usable for notify).
                        // The ESP32 JSON payload can exceed that easily.
                        // onMtuChanged() will trigger discoverServices() once negotiated.
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

            // Enable local notifications
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
            gatt.setCharacteristicNotification(navChar, true)

            // Write CCCD descriptor to tell the peripheral to send notifications
            val descriptor = navChar.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                Log.i(TAG, "CCCD written — awaiting NavData notifications")
            } else {
                // Descriptor missing — still try to read
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
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BleConstants.NAV_DATA_CHAR_UUID) {
                val raw = characteristic.value ?: return
                parseNavData(raw)
            }
        }

        // API 33+ override
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
    // Helpers
    // ----------------------------------------------------------------

    private fun cleanupGatt() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            gatt?.close()
        }
        gatt = null
        _connectionState.value = BleConnectionState.RECONNECTING
    }

    private fun scheduleReconnect() {
        Log.i(TAG, "Reconnecting in ${BleConstants.RECONNECT_DELAY_MS} ms")
        _connectionState.value = BleConnectionState.RECONNECTING
        mainHandler.postDelayed({
            if (isStarted) startScan()
        }, BleConstants.RECONNECT_DELAY_MS)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
}
