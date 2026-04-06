package com.marinewatch.app

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import com.marinewatch.app.ble.BleConnectionState
import com.marinewatch.app.ble.BleConstants
import com.marinewatch.app.ble.BleManager
import com.marinewatch.app.data.AutopilotData
import com.marinewatch.app.data.NavData
import com.marinewatch.app.data.PageConfig
import com.marinewatch.app.data.PerformanceData
import com.marinewatch.app.data.WindData
import com.marinewatch.app.data.loadPageConfigs
import com.marinewatch.app.data.savePageConfigs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "marine_watch_prefs"
private const val PREF_PIN   = "ble_pin_code"

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val bleManager = BleManager(application)

    val connectionState:   StateFlow<BleConnectionState> = bleManager.connectionState
    val navData:           StateFlow<NavData>             = bleManager.navData
    val windData:          StateFlow<WindData>            = bleManager.windData   // ← ajout
    val perfData:          StateFlow<PerformanceData>     = bleManager.perfData
    val autopilotData:     StateFlow<AutopilotData>       = bleManager.autopilotData
    val lastDataTimestamp: StateFlow<Long>                = bleManager.lastDataTimestamp

    private val _pageConfigs = MutableStateFlow(prefs.loadPageConfigs())
    val pageConfigs: StateFlow<List<PageConfig>> = _pageConfigs.asStateFlow()

    fun updateSlot(pageIndex: Int, slotIndex: Int, field: com.marinewatch.app.data.DataField) {
        val current  = _pageConfigs.value.toMutableList()
        val page     = current[pageIndex]
        val newSlots = page.slots.toMutableList().also { it[slotIndex] = field }
        current[pageIndex] = PageConfig(newSlots)
        _pageConfigs.value  = current
        prefs.savePageConfigs(current)
    }

    fun isDataStale(): Boolean {
        val ts = lastDataTimestamp.value
        return ts == 0L || System.currentTimeMillis() - ts > BleConstants.DATA_STALE_THRESHOLD_MS
    }

    fun startBle()   = bleManager.start()
    fun disconnect() = bleManager.disconnect()
    fun reconnect()  = bleManager.reconnect()

    fun getPinCode(): Int    = prefs.getInt(PREF_PIN, BleConstants.PASSKEY)
    fun setPinCode(pin: Int) = prefs.edit().putInt(PREF_PIN, pin).apply()

    fun sendAutopilotCommand(command: String) = bleManager.sendAutopilotCommand(command)

    override fun onCleared() {
        super.onCleared()
        bleManager.stop()
    }
}