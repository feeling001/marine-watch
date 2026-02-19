package com.marinewatch.app.data

import com.google.gson.annotations.SerializedName

/**
 * Represents the JSON payload received from the Navigation BLE characteristic.
 *
 * All fields are nullable: a null value means the data has never been received
 * since boot, or it is stale (no NMEA update for > 10 seconds on the ESP32 side).
 *
 * Units:
 *  - [sog], [stw] : knots
 *  - [cog], [hdgMag], [hdgTrue] : degrees (0–360)
 *  - [depth] : metres
 *  - [lat] : decimal degrees, negative = South
 *  - [lon] : decimal degrees, negative = West
 */
data class NavData(
    @SerializedName("lat")     val lat: Float?     = null,
    @SerializedName("lon")     val lon: Float?     = null,
    @SerializedName("sog")     val sog: Float?     = null,
    @SerializedName("cog")     val cog: Float?     = null,
    @SerializedName("stw")     val stw: Float?     = null,
    @SerializedName("hdg_mag") val hdgMag: Float?  = null,
    @SerializedName("hdg_true")val hdgTrue: Float? = null,
    @SerializedName("depth")   val depth: Float?   = null
) {
    companion object {
        /** Empty state used before first BLE packet is received. */
        val EMPTY = NavData()
    }
}
