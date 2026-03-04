package com.marinewatch.app

import android.Manifest
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver
import com.marinewatch.app.ui.MarineDisplay

private const val TAG = "MarineWatch.Main"

/**
 * Single-activity entry point for the Marine Watch application.
 *
 * Responsibilities:
 *  1. Request BLE runtime permissions (Android 12+ requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT)
 *  2. Register an [AmbientLifecycleObserver] to switch the display between
 *     interactive and always-on (ambient) modes
 *  3. Register the BLE bond state [BroadcastReceiver] so that the PAIRING
 *     connection state is surfaced correctly in the UI
 *  4. Start the BleManager once permissions are confirmed
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Tracks ambient mode state, observed by Compose
    private var isAmbient by mutableStateOf(false)

    // Bond state receiver — created lazily from BleManager and kept for unregistration
    private val bondReceiver by lazy { viewModel.bleManager.createBondStateReceiver() }

    // ----------------------------------------------------------------
    // Ambient mode observer
    // ----------------------------------------------------------------

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                Log.d(TAG, "Entering ambient mode")
                isAmbient = true
            }

            override fun onExitAmbient() {
                Log.d(TAG, "Exiting ambient mode")
                isAmbient = false
            }

            override fun onUpdateAmbient() {
                // Called once per minute in ambient mode.
                // The UI updates automatically via StateFlow; no manual refresh needed.
            }
        }
    )

    // ----------------------------------------------------------------
    // Permission launcher
    // ----------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.i(TAG, "All BLE permissions granted — starting BLE")
            viewModel.startBle()
        } else {
            Log.w(TAG, "Some BLE permissions denied: $results")
            // App will show a disconnected state; user can grant permissions in Settings
        }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register ambient lifecycle
        lifecycle.addObserver(ambientObserver)

        // Register bond state receiver so PAIRING state is surfaced in the UI.
        // The receiver is safe to register before BLE starts — it only reacts to
        // system intents and does nothing until a pairing dialog appears.
        registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )

        // Set up Compose UI
        setContent {
            MarineDisplay(
                viewModel = viewModel,
                isAmbient = isAmbient
            )
        }

        // Request permissions then start BLE
        requestBlePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Always unregister to avoid a leaked IntentFilter reference
        try {
            unregisterReceiver(bondReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bondReceiver was not registered: ${e.message}")
        }
    }

    // ----------------------------------------------------------------
    // BLE permission handling
    // ----------------------------------------------------------------

    private fun requestBlePermissions() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ (API 31+): new granular permissions
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // Android 11 and below
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        val missing = required.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            Log.i(TAG, "All BLE permissions already granted")
            viewModel.startBle()
        } else {
            Log.i(TAG, "Requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
