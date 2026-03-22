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

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var isAmbient by mutableStateOf(false)

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
    //
    // @Suppress: lint incorrectly flags registerForActivityResult as requiring
    // Fragment 1.3.0, but MainActivity extends ComponentActivity (not FragmentActivity).
    // This is a known AGP lint false positive — the restriction does not apply here.
    // ----------------------------------------------------------------

    @Suppress("InvalidFragmentVersionForActivityResult")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Log.i(TAG, "All BLE permissions granted — starting BLE")
            viewModel.startBle()
        } else {
            Log.w(TAG, "Some BLE permissions denied: $results")
        }
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(ambientObserver)

        registerReceiver(
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )

        setContent {
            MarineDisplay(
                viewModel = viewModel,
                isAmbient = isAmbient
            )
        }

        requestBlePermissions()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
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
