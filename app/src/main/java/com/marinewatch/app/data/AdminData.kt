package com.marinewatch.app.data

import com.google.gson.annotations.SerializedName

/**
 * Represents the JSON payload received from the Admin BLE characteristic.
 *
 * Updated at 1 Hz alongside all other service characteristics.
 *
 * Fields:
 *  - [uptimeS]     : seconds since last ESP32 boot
 *  - [datetimeUtc] : Unix timestamp (s) sourced from GPS fix; null if no fix yet
 *  - [wifiMode]    : "sta" (infrastructure) | "ap" (access point) | null
 *  - [wifiSsid]    : connected/broadcasted SSID; null if not configured
 *  - [ip]          : current device IP address; null if WiFi not ready
 *  - [freeHeap]    : free heap memory in bytes (diagnostic)
 */
data class AdminData(
    @SerializedName("uptime_s")     val uptimeS:        Long?   = null,
    @SerializedName("datetime_utc") val datetimeUtc:    Long?   = null,
    @SerializedName("wifi_mode")    val wifiMode:       String? = null,
    @SerializedName("wifi_ssid")    val wifiSsid:       String? = null,
    @SerializedName("ip")           val ip:             String? = null,
    @SerializedName("free_heap")    val freeHeap:       Long?   = null
) {
    companion object {
        /** Empty state used before first BLE packet is received. */
        val EMPTY = AdminData()
    }

    /** Human-readable uptime string, e.g. "2h 34m" or "45s". */
    fun uptimeFormatted(): String {
        val s = uptimeS ?: return "--"
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0  -> "${h}h ${m}m"
            m > 0  -> "${m}m ${sec}s"
            else   -> "${sec}s"
        }
    }

    /** True if the device is in Access Point mode. */
    val isApMode: Boolean get() = wifiMode == "ap"

    /** True if the device is in Station (infrastructure) mode. */
    val isStaMode: Boolean get() = wifiMode == "sta"
}