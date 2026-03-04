package com.marinewatch.app.data

import com.google.gson.annotations.SerializedName

/**
 * Aggregates all BLE service data into a single snapshot passed to [DataField] extractors.
 *
 * [wind] is nullable because the Wind BLE service is not yet subscribed to.
 * It will be populated in a future iteration when WindData is added to BleManager.
 */
data class DisplayData(
    val nav:  NavData   = NavData.EMPTY,
    val wind: WindData? = null
)

/**
 * JSON payload received from the Wind BLE characteristic.
 *
 * Units:
 *  - [aws], [tws] : knots
 *  - [awa], [twa] : degrees, positive = starboard, negative = port (−180 to +180)
 *  - [twd]        : degrees true (0–360)
 */
data class WindData(
    @SerializedName("aws") val aws: Float? = null,
    @SerializedName("awa") val awa: Float? = null,
    @SerializedName("tws") val tws: Float? = null,
    @SerializedName("twa") val twa: Float? = null,
    @SerializedName("twd") val twd: Float? = null
) {
    companion object {
        val EMPTY = WindData()
    }
}
