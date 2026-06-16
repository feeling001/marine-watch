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
import com.marinewatch.app.data.AdminData
import com.marinewatch.app.data.loadPageConfigs
import com.marinewatch.app.data.savePageConfigs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


private const val PREFS_NAME = "marine_watch_prefs"
private const val PREF_PIN   = "ble_pin_code"

/**
 * ViewModel that owns the [BleManager] instance and exposes its state flows
 * to the Compose UI. Survives configuration changes.
 *
 * In addition to navigation data, this ViewModel exposes:
 *  - [adminData] — system status from the Admin BLE service (uptime, IP, WiFi mode)
 *  - [rssi]      — last measured BLE signal strength in dBm
 *  - Helper methods to send admin commands (restart, WiFi config)
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {



    private val prefs: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val bleManager = BleManager(application)

    // ----------------------------------------------------------------
    // Navigation data flows
    // ----------------------------------------------------------------

    val connectionState:   StateFlow<BleConnectionState> = bleManager.connectionState
    val navData:           StateFlow<NavData>             = bleManager.navData
    val windData:          StateFlow<WindData>            = bleManager.windData   // ← ajout
    val perfData:          StateFlow<PerformanceData>     = bleManager.perfData
    val autopilotData:     StateFlow<AutopilotData>       = bleManager.autopilotData
    val lastDataTimestamp: StateFlow<Long>                = bleManager.lastDataTimestamp





    // ----------------------------------------------------------------
    // Admin data flows
    // ----------------------------------------------------------------

    /**
     * Latest parsed admin data from the ESP32 Admin service.
     * Contains uptime, IP address, WiFi mode/SSID, and free heap.
     */
    val adminData: StateFlow<AdminData> = bleManager.adminData

    /**
     * Last measured BLE RSSI in dBm (0 when not connected / not yet read).
     * Polled every [BleConstants.RSSI_POLL_INTERVAL_MS] ms while connected.
     */
    val rssi: StateFlow<Int> = bleManager.rssi

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

    // ----------------------------------------------------------------
    // Derived helpers
    // ----------------------------------------------------------------

    /** True when the last received packet is older than the stale threshold. */
    fun isDataStale(): Boolean {
        val ts = lastDataTimestamp.value
        return ts == 0L ||
               System.currentTimeMillis() - ts > BleConstants.DATA_STALE_THRESHOLD_MS
    }

    // ----------------------------------------------------------------
    // BLE control
    // ----------------------------------------------------------------

    /** Start BLE operations. Must be called after permissions are granted. */
    fun startBle() {
        bleManager.start()
    }
    fun disconnect() = bleManager.disconnect()
    fun reconnect()  = bleManager.reconnect()

    fun getPinCode(): Int    = prefs.getInt(PREF_PIN, BleConstants.PASSKEY)
    fun setPinCode(pin: Int) = prefs.edit().putInt(PREF_PIN, pin).apply()

    fun sendAutopilotCommand(command: String) = bleManager.sendAutopilotCommand(command)

    // ----------------------------------------------------------------
    // Admin commands
    // ----------------------------------------------------------------

    /**
     * Send a restart command to the ESP32.
     * The device will reboot approximately 2 seconds after receiving the command.
     * BLE clients reconnect automatically once the device re-advertises.
     *
     * @return true if the write was submitted to the GATT stack.
     */
    fun sendRestart(): Boolean =
        bleManager.sendAdminCommand("""{"command":"restart"}""")

    /**
     * Configure the ESP32 to connect to an existing WiFi network (station mode).
     * The device saves the configuration to NVS and reboots after ~3 seconds.
     *
     * If the connection to [ssid] fails within 30 s, the device automatically
     * falls back to Access Point mode.
     *
     * @param ssid     Network name (1–31 characters).
     * @param password Network password. Pass an empty string for open networks.
     * @return true if the write was submitted to the GATT stack.
     */
    fun sendWifiSta(ssid: String, password: String): Boolean {
        val escaped = password.replace("\"", "\\\"")
        val escapedSsid = ssid.replace("\"", "\\\"")
        return bleManager.sendAdminCommand(
            """{"command":"wifi_sta","ssid":"$escapedSsid","password":"$escaped"}"""
        )
    }

    /**
     * Configure the ESP32 to operate as a WiFi Access Point.
     * The device saves the configuration to NVS and reboots after ~3 seconds.
     * In AP mode the device IP is always 192.168.4.1.
     *
     * @param ssid     Access point name (1–31 characters).
     * @param password AP password. Minimum 8 characters for WPA2.
     *                 If empty or < 8 chars, the device uses its default ("marine123").
     * @return true if the write was submitted to the GATT stack.
     */
    fun sendWifiAp(ssid: String, password: String): Boolean {
        val escaped = password.replace("\"", "\\\"")
        val escapedSsid = ssid.replace("\"", "\\\"")
        return bleManager.sendAdminCommand(
            """{"command":"wifi_ap","ssid":"$escapedSsid","password":"$escaped"}"""
        )
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.stop()
    }
}
