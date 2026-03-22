package com.marinewatch.app.data

import com.google.gson.annotations.SerializedName

/**
 * Represents the JSON payload received from the Sail Performance BLE characteristic.
 *
 * All float fields are nullable: a null value means the polar is not loaded,
 * or the required source data (STW, TWA, TWS) is stale on the ESP32 side.
 *
 * Units:
 *  - [vmg]       : knots — positive = upwind, negative = downwind
 *  - [polarPct]  : % of polar target speed (100 % = on polar, > 100 % = faster)
 *  - [targetStw] : knots — interpolated polar target boat speed
 */
data class PerformanceData(
    @SerializedName("vmg")          val vmg:         Float?   = null,
    @SerializedName("polar_pct")    val polarPct:    Float?   = null,
    @SerializedName("target_stw")   val targetStw:   Float?   = null,
    @SerializedName("polar_loaded") val polarLoaded: Boolean  = false
) {
    companion object {
        /** Empty state used before first BLE packet is received. */
        val EMPTY = PerformanceData()
    }
}
