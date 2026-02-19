package com.marinewatch.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.ble.BleConstants
import com.marinewatch.app.ble.BleManager
import com.marinewatch.app.data.NavData
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

/**
 * ViewModel that owns the [BleManager] instance and exposes its state flows
 * to the Compose UI. Survives configuration changes.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = BleManager(application)

    /** Current BLE connection state. */
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState

    /** Latest parsed navigation data from the ESP32. */
    val navData: StateFlow<NavData> = bleManager.navData

    /** Timestamp of the last received NavData packet (epoch ms, 0 = never). */
    val lastDataTimestamp: StateFlow<Long> = bleManager.lastDataTimestamp

    /** True when the last received packet is older than the stale threshold. */
    fun isDataStale(): Boolean {
        val ts = lastDataTimestamp.value
        return ts == 0L ||
               System.currentTimeMillis() - ts > BleConstants.DATA_STALE_THRESHOLD_MS
    }

    /** Start BLE operations. Must be called after permissions are granted. */
    fun startBle() {
        bleManager.start()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.stop()
    }
}
