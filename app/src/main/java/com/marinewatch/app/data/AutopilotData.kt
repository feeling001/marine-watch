package com.marinewatch.app.data

import com.google.gson.annotations.SerializedName

/**
 * Represents the JSON payload received from the Autopilot BLE characteristic.
 *
 * All float/string fields are nullable: a null value means the autopilot is
 * not available or the data has not yet been received.
 *
 * Units:
 *  - [headingTarget], [windTarget], [lockedHeading] : degrees (0–360)
 *  - [rudder] : degrees, positive = starboard, negative = port
 */
data class AutopilotData(
    @SerializedName("mode")           val mode:          String? = null,
    @SerializedName("status")         val status:        String? = null,
    @SerializedName("heading_target") val headingTarget: Float?  = null,
    @SerializedName("wind_target")    val windTarget:    Float?  = null,
    @SerializedName("rudder")         val rudder:        Float?  = null,
    @SerializedName("locked_heading") val lockedHeading: Float?  = null
) {
    companion object {
        /** Empty state used before first BLE packet is received. */
        val EMPTY = AutopilotData()
    }

    /** Returns true when the autopilot is actively engaged. */
    val isEngaged: Boolean get() = status == "engaged"

    /** Returns true when the autopilot is in standby. */
    val isStandby: Boolean get() = status == "standby" || status == null
}
