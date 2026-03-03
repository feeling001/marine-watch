package com.marinewatch.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.ble.BleConstants
import com.marinewatch.app.ble.BleManager
import com.marinewatch.app.data.NavData
import kotlinx.coroutines.flow.StateFlow

private const val PREFS_NAME = "marine_watch_prefs"
private const val PREF_PIN   = "ble_pin_code"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val bleManager = BleManager(application)

    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val navData: StateFlow<NavData> = bleManager.navData
    val lastDataTimestamp: StateFlow<Long> = bleManager.lastDataTimestamp

    fun isDataStale(): Boolean {
        val ts = lastDataTimestamp.value
        return ts == 0L || System.currentTimeMillis() - ts > BleConstants.DATA_STALE_THRESHOLD_MS
    }

    fun startBle() = bleManager.start()
    fun disconnect() = bleManager.disconnect()
    fun reconnect() = bleManager.reconnect()

    fun getPinCode(): Int = prefs.getInt(PREF_PIN, BleConstants.PASSKEY)
    fun setPinCode(pin: Int) = prefs.edit().putInt(PREF_PIN, pin).apply()

    override fun onCleared() {
        super.onCleared()
        bleManager.stop()
    }
}